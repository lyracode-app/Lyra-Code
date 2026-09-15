package com.yukisoffd.lyracode.debian

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProotArchitectureTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun elf(name: String, machine: Int, elfClass: Int = 2): File =
        File(temporary.root, name).apply {
            writeBytes(ByteArray(20).apply {
                this[0] = 0x7f
                this[1] = 'E'.code.toByte()
                this[2] = 'L'.code.toByte()
                this[3] = 'F'.code.toByte()
                this[4] = elfClass.toByte()
                this[5] = 1
                this[18] = machine.toByte()
                this[19] = (machine shr 8).toByte()
            })
        }

    @Test fun emulatorUsesExtractedX86EvenWhenArmTranslationIsAdvertisedFirst() {
        elf("libproot_exec.so", 62)
        elf("libproot_loader.so", 62)
        assertEquals(ProotArchitecture.X86_64, ProotArchitecture.installed(temporary.root, listOf("arm64-v8a", "x86_64")))
    }

    @Test fun armDeviceUsesArmPair() {
        elf("libproot_exec.so", 183)
        elf("libproot_loader.so", 183)
        assertEquals(ProotArchitecture.ARM64, ProotArchitecture.installed(temporary.root, listOf("arm64-v8a")))
    }

    @Test fun mismatchedMissingAndUnsupportedLibrariesAreRejected() {
        elf("libproot_exec.so", 62)
        assertNull(ProotArchitecture.installed(temporary.root, listOf("x86_64")))
        elf("libproot_loader.so", 183)
        assertNull(ProotArchitecture.installed(temporary.root, listOf("x86_64", "arm64-v8a")))
        elf("libproot_loader.so", 62)
        assertNull(ProotArchitecture.installed(temporary.root, listOf("arm64-v8a")))
    }

    @Test fun malformedAnd32BitShellsAreRejected() {
        assertNull(ProotArchitecture.readElfMachine(elf("shell", 62, elfClass = 1)))
        val shell = elf("shell", 62)
        shell.writeText("#!/bin/sh\n")
        assertNull(ProotArchitecture.readElfMachine(shell))
        assertEquals(183, ProotArchitecture.readElfMachine(elf("shell", 183)))
        assertEquals(62, ProotArchitecture.readElfMachine(elf("shell", 62)))
    }
}
