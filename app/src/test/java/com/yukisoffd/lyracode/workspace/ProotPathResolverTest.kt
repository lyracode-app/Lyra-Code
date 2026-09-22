package com.yukisoffd.lyracode.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProotPathResolverTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun guestAbsolutePathsStayInsideRootfs() {
        val root = temporary.newFolder("linux").canonicalFile
        assertEquals(root.resolve("root/project"), resolveProotPath(root, "/root/project"))
        assertEquals(root, resolveProotPath(root, "/"))
    }

    @Test fun normalizesGuestPathsWithoutRequiringTheTargetToExist() {
        val root = temporary.newFolder("linux").canonicalFile
        assertEquals(root.resolve("root/new file"), resolveProotPath(root, "/root//src/.././new file"))
    }

    @Test fun rejectsEscapeAboveTheLinuxRoot() {
        val root = temporary.newFolder("linux")
        assertThrows(IllegalArgumentException::class.java) { resolveProotPath(root, "/../outside") }
        assertThrows(IllegalArgumentException::class.java) { resolveProotPath(root, "/root/../../outside") }
    }
}
