package com.yukisoffd.lyracode.workspace

/**
 * Keeps a copy of an interrupted write's undo backup that a later write can never replace.
 *
 * An ordinary write replaces `<path>.bak` with the file's current content, so the backup holds
 * at most the state before the latest write. [NativeFileManager] makes that copy in place, so a
 * kill during it can leave a partial `.bak`. This store makes only the interrupted-write copy,
 * which is only as complete as the `.bak` it copies. The store writes that copy to a temporary
 * name before publication; temporary copies are never treated as backups.
 *
 * This requires a provider whose rename publishes the complete file at exactly the requested
 * name, without exposing partial content, and whose failed rename creates no destination. SAF
 * does not guarantee atomic rename, so a failed rename fails the copy.
 *
 * A write task that started in an open sub-agent batch may be interrupted at any point. Its
 * `<path>.bak` is the only copy from before that write, so the batch's recovery path copies it
 * once to `<path>.<batch>.interrupted.bak`, a name no ordinary write produces.
 *
 * The batch is part of the name because the store cannot tell a retry of the same batch from a
 * second interruption of the same path in a later batch. A batch-named copy is never blocked by
 * an earlier batch's copy, and a report lists the copy of the batch it is reporting on.
 *
 * The file operations arrive as lambdas so the policy runs in a JVM unit test;
 * [NativeFileManager] supplies the SAF implementation.
 */
internal class TextBackupStore(
    private val exists: (String) -> Boolean,
    private val copy: (source: String, target: String) -> Unit,
    private val remove: (String) -> Boolean,
    private val rename: (path: String, newName: String) -> Boolean,
) {
    /**
     * Copies the backup to the batch's never-replaced name, once. Returns true when this call
     * made the copy; false when there is no backup yet or this batch's copy already exists.
     */
    fun preserveInterrupted(path: String, batchId: String): Boolean {
        if (path.endsWith(BACKUP_SUFFIX, ignoreCase = true)) return false
        val backup = backupPath(path)
        val preserved = interruptedBackupPath(path, batchId)
        if (exists(preserved) || !exists(backup)) return false
        val temporary = temporaryPath(preserved)
        // Complete the copy before the preserved name exists, so a kill during this copy cannot
        // leave a partial file under the preserved name.
        copy(backup, temporary)
        if (exists(preserved)) {
            remove(temporary)
            return false
        }
        if (!rename(temporary, preserved.substringAfterLast('/'))) {
            throw IllegalStateException("无法发布中断备份: $preserved")
        }
        return true
    }

    companion object {
        const val BACKUP_SUFFIX = ".bak"
        const val INTERRUPTED_SUFFIX = ".interrupted.bak"
        const val TEMPORARY_SUFFIX = ".tmp"

        fun backupPath(path: String): String = "$path$BACKUP_SUFFIX"

        fun interruptedBackupPath(path: String, batchId: String): String =
            "$path.${batchKey(batchId)}$INTERRUPTED_SUFFIX"

        /**
         * The batch id as a file-name segment. The first eight letters and digits of a UUID are
         * enough to tell two batches' copies apart, and keep the name readable in the workspace.
         */
        private fun batchKey(batchId: String): String =
            batchId.filter { it.isLetterOrDigit() }.take(8).ifBlank { "0" }

        fun temporaryPath(path: String): String = "$path$TEMPORARY_SUFFIX"
    }
}
