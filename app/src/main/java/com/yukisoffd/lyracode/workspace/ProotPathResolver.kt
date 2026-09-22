package com.yukisoffd.lyracode.workspace

import java.io.File
import java.nio.file.Files
import java.util.ArrayDeque

/** Resolves Linux absolute symlinks against rootfs, not Android's root directory. */
internal fun resolveProotPath(
    rootfs: File,
    guestPath: String,
    followLastLink: Boolean = true,
    escapeMessage: () -> String = { "The path escapes the Linux root directory" },
    linkMessage: () -> String = { "Symbolic link cycle or too many symbolic links" },
): File {
    val root = rootfs.canonicalFile
    val pending = ArrayDeque(guestPath.split('/').filter { it.isNotEmpty() })
    val resolved = mutableListOf<String>()
    var links = 0
    while (pending.isNotEmpty()) {
        when (val segment = pending.removeFirst()) {
            "." -> Unit
            ".." -> {
                require(resolved.isNotEmpty(), escapeMessage)
                resolved.removeAt(resolved.lastIndex)
            }
            else -> {
                resolved.add(segment)
                val candidate = File(root, resolved.joinToString("/"))
                if ((followLastLink || pending.isNotEmpty()) && Files.isSymbolicLink(candidate.toPath())) {
                    require(++links <= 40, linkMessage)
                    val target = Files.readSymbolicLink(candidate.toPath()).toString()
                    resolved.removeAt(resolved.lastIndex)
                    if (target.startsWith('/')) resolved.clear()
                    target.split('/').filter { it.isNotEmpty() }.asReversed().forEach(pending::addFirst)
                }
            }
        }
    }
    return File(root, resolved.joinToString("/"))
}
