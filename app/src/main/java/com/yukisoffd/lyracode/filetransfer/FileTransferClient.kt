package com.yukisoffd.lyracode.filetransfer

import android.content.Context
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.SftpProgressMonitor
import com.yukisoffd.lyracode.R
import com.yukisoffd.lyracode.data.AppSettings
import com.yukisoffd.lyracode.data.FileTransferServerConfig
import com.yukisoffd.lyracode.webdav.TransferProgress
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.Charset
import java.util.Properties
import javax.net.ssl.SSLSocketFactory

data class FileTransferFile(
    val path: String,
    val directory: Boolean,
    val size: Long,
    val modified: String,
)

class FileTransferClient internal constructor(private val stringResource: (Int, Array<out Any>) -> String) {
    constructor(context: Context) : this({ id, args -> context.getString(id, *args) })

    private fun message(id: Int, vararg args: Any): String = stringResource(id, args)

    fun test(server: FileTransferServerConfig): Result<List<FileTransferFile>> = runCatching {
        list(server, server.initialPath.ifBlank { "/" })
    }

    fun list(server: FileTransferServerConfig, path: String = server.initialPath): List<FileTransferFile> {
        return when (server.protocol) {
            AppSettings.FILE_TRANSFER_SFTP -> sftpList(server, path)
            AppSettings.FILE_TRANSFER_FTPS, AppSettings.FILE_TRANSFER_FTP -> FtpSession(server).use { it.list(path) }
            else -> error(message(R.string.error_ft_unsupported_protocol, server.protocol))
        }
    }

    fun search(server: FileTransferServerConfig, query: String, basePath: String = server.initialPath, limit: Int = 80): List<FileTransferFile> {
        val cleanQuery = query.trim().lowercase()
        require(cleanQuery.isNotBlank()) { message(R.string.error_ft_search_keyword_empty) }
        val result = ArrayList<FileTransferFile>()
        val queue = ArrayDeque<String>()
        queue.add(basePath.ifBlank { "/" })
        var visited = 0
        while (queue.isNotEmpty() && result.size < limit && visited < 500) {
            val path = queue.removeFirst()
            visited++
            runCatching { list(server, path) }.getOrDefault(emptyList()).forEach { file ->
                if (file.path != path && file.path.lowercase().contains(cleanQuery)) result += file
                if (file.directory && file.path != path && queue.size < 200) queue.add(file.path)
            }
        }
        return result.take(limit)
    }

    fun download(server: FileTransferServerConfig, remotePath: String, onProgress: (TransferProgress) -> Unit = {}): ByteArray {
        return when (server.protocol) {
            AppSettings.FILE_TRANSFER_SFTP -> sftpDownload(server, remotePath, onProgress)
            AppSettings.FILE_TRANSFER_FTPS, AppSettings.FILE_TRANSFER_FTP -> FtpSession(server).use { it.download(remotePath, onProgress) }
            else -> error(message(R.string.error_ft_unsupported_protocol, server.protocol))
        }
    }

    fun upload(server: FileTransferServerConfig, remotePath: String, bytes: ByteArray, onProgress: (TransferProgress) -> Unit = {}) {
        when (server.protocol) {
            AppSettings.FILE_TRANSFER_SFTP -> sftpUpload(server, remotePath, bytes, onProgress)
            AppSettings.FILE_TRANSFER_FTPS, AppSettings.FILE_TRANSFER_FTP -> FtpSession(server).use { it.upload(remotePath, bytes, onProgress) }
            else -> error(message(R.string.error_ft_unsupported_protocol, server.protocol))
        }
    }

    fun serversJson(servers: List<FileTransferServerConfig>): String {
        return JSONObject()
            .put("schema", "lyra_file_transfer_servers_v1")
            .put("servers", JSONArray().also { array ->
                servers.forEach {
                    array.put(
                        JSONObject()
                            .put("id", it.id)
                            .put("name", it.name)
                            .put("protocol", it.protocol)
                            .put("host", if (it.hideAddressInDrawer) "(hidden)" else it.host)
                            .put("port", it.port)
                            .put("username", it.username)
                            .put("initial_path", it.initialPath)
                            .put("enabled", it.enabled),
                    )
                }
            })
            .toString()
    }

    private fun sftpList(server: FileTransferServerConfig, rawPath: String): List<FileTransferFile> = withSftp(server) { channel ->
        val path = normalizePath(rawPath.ifBlank { "/" })
        @Suppress("UNCHECKED_CAST")
        val entries = channel.ls(path) as java.util.Vector<ChannelSftp.LsEntry>
        entries.mapNotNull { entry ->
            val name = entry.filename
            if (name == "." || name == "..") return@mapNotNull null
            val childPath = joinPath(path, name)
            FileTransferFile(
                path = childPath,
                directory = entry.attrs.isDir,
                size = entry.attrs.size,
                modified = runCatching { java.util.Date(entry.attrs.mTime.toLong() * 1000L).toString() }.getOrDefault(""),
            )
        }
    }

    private fun sftpDownload(server: FileTransferServerConfig, remotePath: String, onProgress: (TransferProgress) -> Unit): ByteArray = withSftp(server) { channel ->
        val attrs = runCatching { channel.stat(remotePath) }.getOrNull()
        val total = attrs?.size ?: 0L
        val output = ByteArrayOutputStream()
        channel.get(remotePath).use { input ->
            readWithProgress(input, output, message(R.string.ft_downloading, remotePath.substringAfterLast('/')), total, onProgress)
        }
        output.toByteArray()
    }

    private fun sftpUpload(server: FileTransferServerConfig, remotePath: String, bytes: ByteArray, onProgress: (TransferProgress) -> Unit) = withSftp(server) { channel ->
        ensureSftpParents(channel, remotePath)
        val started = System.currentTimeMillis()
        var done = 0L
        channel.put(
            bytes.inputStream(),
            remotePath,
            object : SftpProgressMonitor {
                override fun init(op: Int, src: String?, dest: String?, max: Long) = Unit

                override fun count(count: Long): Boolean {
                    done += count
                    val elapsed = (System.currentTimeMillis() - started).coerceAtLeast(1)
                    onProgress(TransferProgress(message(R.string.ft_uploading, remotePath.substringAfterLast('/')), done, bytes.size.toLong(), done * 1000 / elapsed))
                    return true
                }

                override fun end() = Unit
            },
        )
    }

    private fun <T> withSftp(server: FileTransferServerConfig, block: (ChannelSftp) -> T): T {
        val jsch = JSch()
        if (server.usePrivateKey) {
            jsch.addIdentity(
                "lyra-${server.id}",
                server.privateKey.toByteArray(),
                null,
                server.passphrase.takeIf { it.isNotBlank() }?.toByteArray(),
            )
        }
        val session = jsch.getSession(server.username, server.host, server.port)
        if (!server.usePrivateKey) session.setPassword(server.password)
        session.setConfig(Properties().apply {
            put("StrictHostKeyChecking", "no")
            put("PreferredAuthentications", if (server.usePrivateKey) "publickey,password" else "password,keyboard-interactive")
        })
        session.connect(20_000)
        val channel = session.openChannel("sftp") as ChannelSftp
        channel.connect(20_000)
        return try {
            block(channel)
        } finally {
            channel.disconnect()
            session.disconnect()
        }
    }

    private fun ensureSftpParents(channel: ChannelSftp, remotePath: String) {
        val parts = normalizePath(remotePath).trim('/').split('/').dropLast(1).filter { it.isNotBlank() }
        var current = ""
        parts.forEach { part ->
            current += "/$part"
            runCatching { channel.mkdir(current) }
        }
    }

    private inner class FtpSession(private val server: FileTransferServerConfig) : AutoCloseable {
        private val charset: Charset = runCatching { Charset.forName(server.encoding.ifBlank { "UTF-8" }) }.getOrDefault(Charsets.UTF_8)
        private var control: Socket = connectControl()
        private var reader = BufferedReader(InputStreamReader(control.getInputStream(), charset))
        private var writer = OutputStreamWriter(control.getOutputStream(), charset)

        init {
            try {
                var hello = readResponse()
                if (hello.code == 120) hello = readResponse()
                require(hello.code == 220) { message(R.string.error_ftp_connect_failed, hello.text) }
                if (server.protocol == AppSettings.FILE_TRANSFER_FTPS && server.explicitFtps) {
                    command("AUTH TLS", 234)
                    control = wrapSsl(control)
                    reader = BufferedReader(InputStreamReader(control.getInputStream(), charset))
                    writer = OutputStreamWriter(control.getOutputStream(), charset)
                }
                val user = command("USER ${server.username.ifBlank { "anonymous" }}", expected = null)
                val login = if (user.code == 331) command("PASS ${server.password}", expected = null) else user
                require(login.code == 230) { message(R.string.error_ftp_login_failed, login.text) }
                command("TYPE I", 200)
                if (server.protocol == AppSettings.FILE_TRANSFER_FTPS) {
                    command("PBSZ 0", 200)
                    command("PROT P", 200)
                }
            } catch (error: Throwable) {
                runCatching { control.close() }
                throw error
            }
        }

        fun list(path: String): List<FileTransferFile> {
            val clean = normalizePath(path.ifBlank { "/" })
            val text = readListing("MLSD", clean)
            return if (text == null) {
                parseList(requireNotNull(readListing("LIST", clean)), clean)
            } else {
                parseMlsd(text, clean)
            }
        }

        private fun readListing(verb: String, path: String): String? = openPassiveDataSocket().use { data ->
            val response = command("$verb $path", expected = null)
            if (verb == "MLSD" && response.code in listOf(500, 501, 502, 504)) return@use null
            require(response.code in listOf(125, 150, 226)) { message(R.string.error_ftp_list_failed, response.text) }
            val text = data.getInputStream().bufferedReader(charset).use { it.readText() }
            if (response.code != 226) completeTransfer(R.string.error_ftp_list_failed)
            text
        }

        fun download(remotePath: String, onProgress: (TransferProgress) -> Unit): ByteArray {
            val output = ByteArrayOutputStream()
            openPassiveDataSocket().use { data ->
                val response = command("RETR ${normalizePath(remotePath)}", expected = null)
                require(response.code in listOf(125, 150)) { message(R.string.error_ftp_download_failed, response.text) }
                data.getInputStream().use { input ->
                    readWithProgress(input, output, message(R.string.ft_downloading, remotePath.substringAfterLast('/')), 0L, onProgress)
                }
            }
            completeTransfer(R.string.error_ftp_download_failed)
            return output.toByteArray()
        }

        fun upload(remotePath: String, bytes: ByteArray, onProgress: (TransferProgress) -> Unit) {
            ensureParents(remotePath)
            openPassiveDataSocket().use { data ->
                val response = command("STOR ${normalizePath(remotePath)}", expected = null)
                require(response.code in listOf(125, 150)) { message(R.string.error_ftp_upload_failed, response.text) }
                val started = System.currentTimeMillis()
                data.getOutputStream().use { output ->
                    var done = 0L
                    bytes.inputStream().use { input ->
                        val buffer = ByteArray(32 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            done += read
                            val elapsed = (System.currentTimeMillis() - started).coerceAtLeast(1)
                            onProgress(TransferProgress(message(R.string.ft_uploading, remotePath.substringAfterLast('/')), done, bytes.size.toLong(), done * 1000 / elapsed))
                        }
                    }
                }
            }
            completeTransfer(R.string.error_ftp_upload_failed)
        }

        private fun completeTransfer(errorMessage: Int) {
            val response = readResponse()
            require(response.code in listOf(226, 250)) { message(errorMessage, response.text) }
        }

        private fun ensureParents(remotePath: String) {
            normalizePath(remotePath).trim('/').split('/').dropLast(1).filter { it.isNotBlank() }.fold("") { acc, part ->
                val next = "$acc/$part"
                runCatching { command("MKD $next", expected = null) }
                next
            }
        }

        private fun openPassiveDataSocket(): Socket {
            require(server.passiveMode) { message(R.string.error_ftp_passive_only) }
            val extended = command("EPSV", expected = null)
            val port = if (extended.code == 229) {
                val value = Regex("""\(([!-~])\1\1(\d+)\1\)""").find(extended.text)?.groupValues?.get(2)?.toIntOrNull()
                require(value != null && value in 1..65535) { message(R.string.error_ftp_pasv_parse_failed, extended.text) }
                value
            } else {
                require(extended.code in listOf(500, 501, 502, 504, 522)) {
                    message(R.string.error_ftp_command_failed, "EPSV", extended.text)
                }
                val response = command("PASV", 227)
                val numbers = Regex("""\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)\s*\)""")
                    .find(response.text)?.groupValues?.drop(1)?.mapNotNull { it.toIntOrNull() }
                require(numbers != null && numbers.size == 6 && numbers.all { it in 0..255 }) {
                    message(R.string.error_ftp_pasv_parse_failed, response.text)
                }
                (numbers[4] * 256 + numbers[5]).also {
                    require(it > 0) { message(R.string.error_ftp_pasv_parse_failed, response.text) }
                }
            }
            // Reuse the actual control peer: PASV often advertises an unreachable LAN address behind NAT.
            val socket = Socket()
            return try {
                socket.soTimeout = 20_000
                socket.connect(InetSocketAddress(control.inetAddress, port), 20_000)
                if (server.protocol == AppSettings.FILE_TRANSFER_FTPS) wrapSsl(socket) else socket
            } catch (error: Throwable) {
                runCatching { socket.close() }
                throw error
            }
        }

        private fun connectControl(): Socket {
            val socket = if (server.protocol == AppSettings.FILE_TRANSFER_FTPS && !server.explicitFtps) {
                SSLSocketFactory.getDefault().createSocket() as Socket
            } else {
                Socket()
            }
            return try {
                socket.soTimeout = 20_000
                socket.connect(InetSocketAddress(server.host, server.port), 20_000)
                socket
            } catch (error: Throwable) {
                runCatching { socket.close() }
                throw error
            }
        }

        private fun wrapSsl(socket: Socket): Socket {
            val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
            return (factory.createSocket(socket, server.host, socket.port, true) as Socket).apply { soTimeout = 20_000 }
        }

        private fun command(command: String, expected: Int?): FtpResponse {
            writer.write("$command\r\n")
            writer.flush()
            val response = readResponse()
            if (expected != null && response.code != expected) error(message(R.string.error_ftp_command_failed, command, response.text))
            return response
        }

        private fun readResponse(): FtpResponse {
            val first = reader.readLine() ?: error(message(R.string.error_ftp_disconnected))
            val code = first.take(3).toIntOrNull() ?: error(message(R.string.error_ftp_response_parse_failed, first))
            val lines = mutableListOf(first)
            if (first.length > 3 && first[3] == '-') {
                while (true) {
                    val line = reader.readLine() ?: error(message(R.string.error_ftp_disconnected))
                    lines += line
                    if (line.startsWith("$code ")) break
                }
            }
            return FtpResponse(code, lines.joinToString("\n"))
        }

        override fun close() {
            runCatching { writer.write("QUIT\r\n"); writer.flush() }
            runCatching { control.close() }
        }
    }

    private data class FtpResponse(val code: Int, val text: String)

    companion object {
        private fun parseMlsd(text: String, basePath: String): List<FileTransferFile> {
            return text.lineSequence().mapNotNull { line ->
                val clean = line.trim()
                if (clean.isBlank()) return@mapNotNull null
                val split = clean.indexOf(' ')
                if (split <= 0) return@mapNotNull null
                val facts = clean.substring(0, split).split(';')
                val name = clean.substring(split + 1).trim()
                if (name == "." || name == ".." || name.isBlank()) return@mapNotNull null
                val type = facts.firstOrNull { it.startsWith("type=", true) }?.substringAfter('=').orEmpty()
                val size = facts.firstOrNull { it.startsWith("size=", true) }?.substringAfter('=')?.toLongOrNull() ?: 0L
                val modified = facts.firstOrNull { it.startsWith("modify=", true) }?.substringAfter('=').orEmpty()
                FileTransferFile(joinPath(basePath, name), type.equals("dir", true), size, modified)
            }.toList()
        }

        private fun parseList(text: String, basePath: String): List<FileTransferFile> {
            return text.lineSequence().mapNotNull { line ->
                val clean = line.trim()
                if (clean.isBlank()) return@mapNotNull null
                val parts = clean.split(Regex("\\s+"))
                if (parts.size < 9) return@mapNotNull null
                val name = parts.drop(8).joinToString(" ")
                if (name == "." || name == "..") return@mapNotNull null
                FileTransferFile(
                    path = joinPath(basePath, name),
                    directory = clean.startsWith("d"),
                    size = parts.getOrNull(4)?.toLongOrNull() ?: 0L,
                    modified = parts.drop(5).take(3).joinToString(" "),
                )
            }.toList()
        }

        private fun normalizePath(path: String): String {
            val clean = path.trim().replace('\\', '/').replace(Regex("/+"), "/")
            return if (clean.startsWith("/")) clean else "/$clean"
        }

        private fun joinPath(parent: String, child: String): String {
            val cleanParent = normalizePath(parent).trimEnd('/')
            return if (cleanParent.isBlank()) "/$child" else "$cleanParent/$child"
        }

        private fun readWithProgress(
            input: java.io.InputStream,
            output: ByteArrayOutputStream,
            title: String,
            total: Long,
            onProgress: (TransferProgress) -> Unit,
        ) {
            val started = System.currentTimeMillis()
            val buffer = ByteArray(32 * 1024)
            var done = 0L
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                output.write(buffer, 0, read)
                done += read
                val elapsed = (System.currentTimeMillis() - started).coerceAtLeast(1)
                onProgress(TransferProgress(title, done, total, done * 1000 / elapsed))
            }
        }
    }
}
