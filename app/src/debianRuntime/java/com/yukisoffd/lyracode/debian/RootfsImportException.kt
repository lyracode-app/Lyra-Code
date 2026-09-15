package com.yukisoffd.lyracode.debian

internal enum class RootfsImportProblem {
    ARCHITECTURE_MISMATCH, MISSING_SHELL, INVALID_SHELL, INVALID_ARCHIVE, CANNOT_READ, OTHER,
}

internal class RootfsImportException(
    val problem: RootfsImportProblem,
    val expected: ProotArchitecture? = null,
    val actualMachine: Int? = null,
    cause: Throwable? = null,
) : IllegalArgumentException(when (problem) {
    RootfsImportProblem.ARCHITECTURE_MISMATCH -> "This device requires ${expected?.rootfsName}; the rootfs shell has ELF machine $actualMachine."
    RootfsImportProblem.MISSING_SHELL -> "Not a Linux rootfs: /bin/bash and /bin/sh are missing."
    RootfsImportProblem.INVALID_SHELL -> "The rootfs shell is missing, invalid, or has a broken symbolic link."
    RootfsImportProblem.INVALID_ARCHIVE -> "Invalid or damaged rootfs archive. Import a complete .tar.gz, .tgz or .tar file."
    RootfsImportProblem.CANNOT_READ -> "Cannot read the selected rootfs file. Select an accessible local copy."
    RootfsImportProblem.OTHER -> "Rootfs import failed. Check available storage and file access, then retry."
}, cause)
