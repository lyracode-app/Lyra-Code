package com.yukisoffd.lyracode

import androidx.test.platform.app.InstrumentationRegistry
import androidx.documentfile.provider.ProotDocumentFile
import com.yukisoffd.lyracode.workspace.ProotWorkspace
import com.yukisoffd.lyracode.workspace.resolveProotPath
import java.nio.file.Files
import java.nio.file.Paths
import org.junit.Assert.*
import org.junit.Test

class ProotWorkspaceInstrumentedTest {
    @Test fun workspaceIdentityRoundTripsLinuxIdAndUnicodePath() {
        val uri = android.net.Uri.parse(ProotWorkspace.uri("debian-123", "/root/项目 #1").toString())
        assertEquals(ProotWorkspace.SCHEME, uri.scheme)
        assertEquals("debian-123", uri.authority)
        assertEquals("/root/项目 #1", uri.path)
    }

    @Test fun privateDocumentsReadWriteAndResolveLinuxLinks() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = Files.createTempDirectory(context.cacheDir.toPath(), "workspace-test").toFile()
        try {
            root.resolve("root/project").mkdirs()
            root.resolve("usr/lib").mkdirs()
            val target = root.resolve("usr/lib/test.bin").apply { writeBytes(byteArrayOf(0, 1, -1)) }
            Files.createSymbolicLink(root.resolve("root/project/link").toPath(), Paths.get("/usr/lib/test.bin"))
            Files.createSymbolicLink(root.resolve("root/project/relative").toPath(), Paths.get("../../usr/lib"))
            val workspace = ProotDocumentFile(root, "/root/project")
            val link = requireNotNull(workspace.findFile("link"))
            assertArrayEquals(target.readBytes(), context.contentResolver.openInputStream(link.uri)!!.use { it.readBytes() })
            assertEquals(target, resolveProotPath(root, "/root/project/relative/test.bin"))
            val file = requireNotNull(workspace.createFile("application/octet-stream", "new.bin"))
            context.contentResolver.openOutputStream(file.uri, "wt")!!.use { it.write(byteArrayOf(2, 3)) }
            assertArrayEquals(byteArrayOf(2, 3), root.resolve("root/project/new.bin").readBytes())
            assertTrue(file.renameTo("renamed.bin"))
            assertTrue(link.delete())
            assertTrue(target.exists())
            assertTrue(workspace.delete())
            assertTrue(target.exists())
        } finally {
            Files.walk(root.toPath()).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }

    @Test fun rejectsSymbolicLinkCycles() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = Files.createTempDirectory(context.cacheDir.toPath(), "workspace-cycle").toFile()
        try {
            Files.createSymbolicLink(root.resolve("loop").toPath(), Paths.get("/loop"))
            assertThrows(IllegalArgumentException::class.java) { resolveProotPath(root, "/loop") }
        } finally {
            Files.deleteIfExists(root.resolve("loop").toPath())
            root.delete()
        }
    }
}
