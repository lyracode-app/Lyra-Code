package com.yukisoffd.lyracode.filetransfer

import com.yukisoffd.lyracode.data.FileTransferServerConfig
import org.junit.Assert.*
import org.junit.Test
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import kotlin.concurrent.thread

class FileTransferClientTest {
    private val client = FileTransferClient { id, args -> "$id: ${args.joinToString()}" }

    @Test fun listsUsingEpsvWithoutRequestingPasv() {
        Fixture().use { server ->
            val files = client.test(server.config).getOrThrow()
            assertEquals(listOf("/folder", "/hello world.txt"), files.map { it.path })
            assertTrue(files[0].directory)
            assertEquals(7L, files[1].size)
            assertTrue(server.commands.contains("EPSV"))
            assertFalse(server.commands.contains("PASV"))
        }
    }

    @Test fun pasvUsesControlPeerWhenServerAdvertisesPrivateAddress() {
        Fixture(epsv = false).use { server ->
            assertEquals(2, client.list(server.config).size)
            assertTrue(server.commands.containsAll(listOf("EPSV", "PASV", "MLSD /")))
        }
    }

    @Test fun unsupportedMlsdFallsBackToListWithNewDataConnection() {
        Fixture(epsv = false, mlsdReply = 502).use { server ->
            val files = client.list(server.config, "/docs")
            assertEquals(listOf("/docs/folder", "/docs/hello world.txt"), files.map { it.path })
            assertTrue(files[0].directory)
            assertEquals(7L, files[1].size)
            assertEquals(2, server.commands.count { it == "PASV" })
            assertTrue(server.commands.contains("LIST /docs"))
        }
    }

    @Test fun mlsdPermissionFailureIsReportedWithoutListRetry() {
        Fixture(mlsdReply = 550).use { server ->
            assertTrue(client.test(server.config).exceptionOrNull()?.message.orEmpty().contains("550"))
            assertFalse(server.commands.any { it.startsWith("LIST") })
        }
    }

    @Test fun downloadsThroughNatUsingPasvPort() {
        Fixture(epsv = false).use { server ->
            assertArrayEquals(server.payload, client.download(server.config, "/hello world.txt"))
            assertTrue(server.commands.contains("RETR /hello world.txt"))
        }
    }

    @Test fun uploadsThroughNatUsingPasvPort() {
        Fixture(epsv = false).use { server ->
            client.upload(server.config, "/folder/upload.bin", server.payload)
            assertArrayEquals(server.payload, server.uploaded)
            assertTrue(server.commands.containsAll(listOf("MKD /folder", "STOR /folder/upload.bin")))
        }
    }

    @Test fun alreadyAuthenticatedUserDoesNotSendPassword() {
        Fixture(userReply = 230).use { server ->
            assertEquals(2, client.list(server.config).size)
            assertFalse(server.commands.any { it.startsWith("PASS ") })
        }
    }

    @Test fun rejectedUserStopsBeforePasswordAndClosesConnection() {
        Fixture(userReply = 530).use { server ->
            val error = client.test(server.config).exceptionOrNull()
            assertTrue(error?.message.orEmpty().contains("530"))
            assertFalse(server.commands.any { it.startsWith("PASS ") || it == "TYPE I" })
            server.awaitClosed()
        }
    }

    @Test fun rejectedPasswordIsReportedAtLogin() {
        Fixture(passwordReply = 530).use { server ->
            val error = client.test(server.config).exceptionOrNull()
            assertTrue(error?.message.orEmpty().contains("530"))
            assertFalse(error?.message.orEmpty().contains(server.config.password))
            assertFalse(server.commands.contains("TYPE I"))
            server.awaitClosed()
        }
    }

    @Test fun normalLoginConsumesEachReplyOnceAndHandlesMultilineGreeting() {
        Fixture(delayedGreeting = true).use { server ->
            client.list(server.config)
            assertEquals(listOf("USER test-user", "PASS test-password", "TYPE I", "EPSV", "MLSD /"), server.commands.take(5))
        }
    }

    @Test fun failedFinalReplyRejectsListingDownloadAndUpload() {
        for (operation in listOf("list", "download", "upload")) {
            Fixture(completionReply = 426).use { server ->
                val error = runCatching {
                    when (operation) {
                        "list" -> client.list(server.config)
                        "download" -> client.download(server.config, "/file.bin")
                        else -> client.upload(server.config, "/file.bin", server.payload)
                    }
                }.exceptionOrNull()
                assertTrue("$operation must reject an incomplete transfer", error?.message.orEmpty().contains("426"))
            }
        }
    }

    @Test fun epsvAcceptsAlternativeDelimiterAndRejectsInvalidPort() {
        Fixture(epsvDelimiter = '!').use { server ->
            assertEquals(2, client.list(server.config).size)
        }
        Fixture(invalidPort = true).use { server ->
            assertTrue(client.test(server.config).isFailure)
            assertFalse(server.commands.any { it.startsWith("MLSD") || it == "PASV" })
        }
    }

    @Test fun pasvRejectsOutOfRangePortComponents() {
        Fixture(epsv = false, invalidPort = true).use { server ->
            assertTrue(client.test(server.config).isFailure)
            assertFalse(server.commands.any { it.startsWith("MLSD") })
        }
    }

    private class Fixture(
        private val epsv: Boolean = true,
        private val mlsdReply: Int = 150,
        private val userReply: Int = 331,
        private val passwordReply: Int = 230,
        private val completionReply: Int = 226,
        private val delayedGreeting: Boolean = false,
        private val epsvDelimiter: Char = '|',
        private val invalidPort: Boolean = false,
    ) : Closeable {
        private val address = InetAddress.getByName("127.0.0.1")
        private val listener = ServerSocket(0, 1, address).apply { soTimeout = 5000 }
        private var dataListener: ServerSocket? = null
        @Volatile private var control: Socket? = null
        @Volatile private var failure: Throwable? = null
        @Volatile private var stopping = false
        @Volatile var uploaded = byteArrayOf()
            private set
        private val received: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val commands: List<String>
            get() = synchronized(received) { received.toList() }
        val payload = byteArrayOf(0, 1, 2, 10, 13, 127, -1)
        val config = FileTransferServerConfig(
            id = "test", name = "Test FTP", protocol = "ftp", host = "127.0.0.1", port = listener.localPort,
            username = "test-user", password = "test-password", usePrivateKey = false, privateKey = "", passphrase = "",
            initialPath = "/", note = "", encoding = "UTF-8", passiveMode = true, explicitFtps = true,
            multiThread = false, syncPermissions = false, hideAddressInDrawer = false, enabled = true,
        )
        private val worker = thread(isDaemon = true, name = "ftp-test-server") {
            try {
                listener.accept().use { socket ->
                    control = socket
                    socket.soTimeout = 5000
                    val reader = socket.getInputStream().bufferedReader()
                    val writer = socket.getOutputStream().bufferedWriter()
                    fun reply(text: String) { writer.write("$text\r\n"); writer.flush() }
                    if (delayedGreeting) reply("120 Please wait")
                    reply("220-Welcome\r\nTest FTP server\r\n220 Ready")
                    while (true) {
                        val command = reader.readLine() ?: break
                        received += command
                        when (command.substringBefore(' ')) {
                            "USER" -> reply("$userReply User result")
                            "PASS" -> reply("$passwordReply Password result")
                            "TYPE", "MKD" -> reply("200 OK")
                            "EPSV", "PASV" -> {
                                if (command == "EPSV" && !epsv) {
                                    reply("502 EPSV unsupported")
                                } else {
                                    dataListener?.close()
                                    val data = ServerSocket(0, 1, address).apply { soTimeout = 5000 }
                                    dataListener = data
                                    val port = data.localPort
                                    if (command == "EPSV") {
                                        reply("229 Entering Extended Passive Mode ($epsvDelimiter$epsvDelimiter$epsvDelimiter${if (invalidPort) 70000 else port}$epsvDelimiter)")
                                    } else {
                                        reply("227 Entering Passive Mode (10,0,67,23,${if (invalidPort) 256 else port / 256},${port % 256})")
                                    }
                                }
                            }
                            "MLSD", "LIST", "RETR", "STOR" -> {
                                val data = requireNotNull(dataListener)
                                if (command.startsWith("MLSD") && mlsdReply != 150) {
                                    reply("$mlsdReply MLSD unavailable")
                                    data.accept().close()
                                } else {
                                    reply("150 Opening data connection")
                                    data.accept().use { transfer ->
                                        transfer.soTimeout = 5000
                                        when (command.substringBefore(' ')) {
                                            "MLSD" -> transfer.getOutputStream().write((
                                                "type=cdir; .\r\ntype=pdir; ..\r\n" +
                                                    "type=dir;modify=20261026000000; folder\r\n" +
                                                    "type=file;size=7;modify=20261026000000; hello world.txt\r\n"
                                                ).toByteArray())
                                            "LIST" -> transfer.getOutputStream().write((
                                                "drwxr-xr-x 2 user group 0 Oct 26 00:00 folder\r\n" +
                                                    "-rw-r--r-- 1 user group 7 Oct 26 00:00 hello world.txt\r\n"
                                                ).toByteArray())
                                            "RETR" -> transfer.getOutputStream().write(payload)
                                            "STOR" -> uploaded = transfer.getInputStream().readBytes()
                                        }
                                    }
                                    reply("$completionReply Transfer result")
                                }
                                data.close()
                                dataListener = null
                            }
                            "QUIT" -> break
                            else -> error("Unexpected FTP command: $command")
                        }
                    }
                }
            } catch (error: Throwable) {
                if (!stopping) failure = error
            }
        }

        fun awaitClosed() {
            worker.join(2000)
            assertFalse("FTP control connection should be closed", worker.isAlive)
            failure?.let { throw AssertionError("FTP fixture failed", it) }
        }

        override fun close() {
            try {
                awaitClosed()
            } finally {
                stopping = true
                dataListener?.close()
                control?.close()
                listener.close()
                worker.join(1000)
            }
        }
    }
}
