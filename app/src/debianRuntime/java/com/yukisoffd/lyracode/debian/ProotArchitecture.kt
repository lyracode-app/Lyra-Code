package com.yukisoffd.lyracode.debian

import java.io.DataInputStream
import java.io.File

internal enum class ProotArchitecture(val abi: String, val elfMachine: Int, val rootfsName: String) {
    ARM64("arm64-v8a", 183, "arm64/aarch64"),
    X86_64("x86_64", 62, "amd64/x86_64");

    companion object {
        // Android extracts the selected ABI into nativeLibraryDir. Inspect that pair,
        // since emulators can advertise ARM translation as well as native x86_64.
        fun installed(nativeLibraryDir: File, supportedAbis: List<String>): ProotArchitecture? {
            val executable = readElfMachine(File(nativeLibraryDir, "libproot_exec.so")) ?: return null
            val loader = readElfMachine(File(nativeLibraryDir, "libproot_loader.so")) ?: return null
            return entries.firstOrNull { it.abi in supportedAbis && it.elfMachine == executable && executable == loader }
        }

        fun readElfMachine(file: File, require64Bit: Boolean = true): Int? = runCatching {
            DataInputStream(file.inputStream().buffered()).use { input ->
                val header = ByteArray(20)
                input.readFully(header)
                if (header[0] != 0x7f.toByte() || header[1] != 'E'.code.toByte() ||
                    header[2] != 'L'.code.toByte() || header[3] != 'F'.code.toByte() ||
                    (header[4] != 2.toByte() && (require64Bit || header[4] != 1.toByte())) || header[5] != 1.toByte()
                ) return@use null
                (header[18].toInt() and 0xff) or ((header[19].toInt() and 0xff) shl 8)
            }
        }.getOrNull()
    }
}
