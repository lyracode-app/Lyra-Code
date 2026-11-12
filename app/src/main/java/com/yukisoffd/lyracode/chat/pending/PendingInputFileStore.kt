package com.yukisoffd.lyracode.chat.pending

import com.yukisoffd.lyracode.workspace.UploadedFileManager
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URI
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID

/** Outcome of reading one persisted queue document. */
internal sealed interface PendingQueueDocument {
    data class Present(val raw: String) : PendingQueueDocument
    data object Missing : PendingQueueDocument
    data class Unreadable(val cause: IOException) : PendingQueueDocument
}

/**
 * Persists one versioned queue document per conversation under [QUEUE_DIRECTORY].
 *
 * Writes are atomic: the document is written and fsynced to a temporary file, then renamed over
 * the target. A failed write leaves the previous document intact and is reported to the caller;
 * it never deletes the target first. Reads distinguish a missing document from a transient read
 * failure. Corrupt or unknown-version documents are quarantined rather than deleted, so their
 * attachment references stay visible to [isAttachmentReferencedElsewhere].
 *
 * [isAttachmentReferencedElsewhere] excludes the conversation being mutated from the persisted
 * queue scan because the caller already checks that conversation's remaining in-memory queue.
 * Delivered user messages are checked for every conversation, including the mutated one. Any
 * failure to inspect a possible holder is treated as "still referenced".
 */
internal class PendingInputFileStore(
    private val filesDir: File,
    private val conversationExists: (Long) -> Boolean = { true },
    private val deliveredAttachmentUris: () -> Set<String> = { emptySet() },
    private val attachmentDirectory: File = File(filesDir, UploadedFileManager.CHAT_UPLOAD_DIRECTORY),
) {
    /** Raw document text, or null when the document does not exist. Read failures throw. */
    @Synchronized
    fun readQueue(conversationId: Long): String? = when (val document = readQueueDocument(conversationId)) {
        PendingQueueDocument.Missing -> null
        is PendingQueueDocument.Present -> document.raw
        is PendingQueueDocument.Unreadable -> throw document.cause
    }

    @Synchronized
    fun readQueueDocument(conversationId: Long): PendingQueueDocument {
        val file = queueFile(conversationId)
        return try {
            // readAttributes distinguishes a missing file from an inaccessible one. File.exists()
            // returns false for both, which could turn a transient failure into data loss.
            Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)
            PendingQueueDocument.Present(file.readText())
        } catch (_: NoSuchFileException) {
            PendingQueueDocument.Missing
        } catch (error: Exception) {
            PendingQueueDocument.Unreadable(error as? IOException ?: IOException("Could not read pending queue", error))
        }
    }

    /**
     * Atomically replaces the document. A null or blank document deletes it. A failure to write
     * or rename throws after the temporary file is removed, leaving the old document untouched.
     */
    @Synchronized
    fun writeQueue(conversationId: Long, raw: String?) {
        val target = queueFile(conversationId)
        if (raw.isNullOrBlank()) {
            if (target.exists() && !target.delete()) {
                throw IOException("Could not delete pending queue document ${target.name}")
            }
            return
        }
        val directory = queueDirectory().apply { mkdirs() }
        if (!directory.isDirectory) throw IOException("Could not create pending queue directory")
        val temporary = File(directory, "${target.name}.tmp")
        try {
            FileOutputStream(temporary).use { output ->
                output.write(raw.toByteArray(Charsets.UTF_8))
                output.flush()
                output.fd.sync()
            }
            moveReplacing(temporary, target)
        } catch (error: Throwable) {
            temporary.delete()
            throw error
        }
    }

    /**
     * Moves an unreadable document aside without deleting it. The move is conditional on the
     * document still having [expectedRaw], so a concurrent successful write is never quarantined.
     * Returns true when this call quarantined the document. Quarantined documents are scanned by
     * [isAttachmentReferencedElsewhere].
     */
    @Synchronized
    fun quarantineUnreadableQueue(conversationId: Long, expectedRaw: String): Boolean {
        val source = queueFile(conversationId)
        if (!source.isFile) return false
        val current = runCatching { source.readText() }.getOrNull() ?: return false
        if (current != expectedRaw) return false
        val quarantine = File(
            queueDirectory(),
            "${source.name}$QUARANTINE_SUFFIX-${System.currentTimeMillis()}-${UUID.randomUUID()}",
        )
        return runCatching {
            Files.move(source.toPath(), quarantine.toPath(), StandardCopyOption.ATOMIC_MOVE)
            true
        }.getOrDefault(false)
    }

    /** Queue document ids present on disk, including documents of deleted conversations. */
    @Synchronized
    fun allConversationIds(): Set<Long> = queueIds().orEmpty()

    /**
     * Whether [attachment] still has a backing payload. App-private files are checked on disk;
     * `content://` URIs and unknown schemes cannot be inspected and are treated as present.
     */
    fun attachmentExists(attachment: PendingAttachment): Boolean {
        val uri = attachment.uri.trim()
        if (uri.isEmpty()) return false
        if (uri.startsWith(CONTENT_SCHEME, ignoreCase = true)) return true
        val rawPath = localPath(uri) ?: return true
        return runCatching { File(rawPath).exists() }.getOrDefault(true)
    }

    /**
     * Deletes [attachment] only when it is a file inside the app-private upload directory.
     * Content URIs and files outside that directory are never deleted here.
     */
    fun deleteAttachment(attachment: PendingAttachment): Boolean {
        val file = appPrivateAttachmentFile(attachment) ?: return false
        return runCatching { file.delete() }.getOrDefault(false)
    }

    /** Whether another persisted queue or a delivered user message still owns the attachment. */
    fun isAttachmentReferencedElsewhere(conversationId: Long, attachment: PendingAttachment): Boolean {
        val uri = attachment.uri.trim()
        if (uri.isEmpty()) return false
        if (uri in deliveredAttachmentUris()) return true
        if (localPath(uri) != null && quarantinedDocumentsReference(uri)) return true
        return otherPersistedQueuesReference(conversationId, uri)
    }

    private fun otherPersistedQueuesReference(conversationId: Long, uri: String): Boolean {
        val queuedConversationIds = queueIds() ?: return true
        for (queuedConversationId in queuedConversationIds) {
            if (queuedConversationId == conversationId) continue
            if (!runCatching { conversationExists(queuedConversationId) }.getOrDefault(true)) continue
            val raw = when (val document = readQueueDocument(queuedConversationId)) {
                PendingQueueDocument.Missing -> continue
                is PendingQueueDocument.Unreadable -> return true
                is PendingQueueDocument.Present -> document.raw
            }
            if (decodePendingInputQueue(queuedConversationId, raw).any { item ->
                    item.capture.attachments.any { it.uri.trim() == uri }
                }
            ) {
                return true
            }
        }
        return false
    }

    /**
     * Any quarantine file we cannot inspect keeps the attachment. A queue-directory listing
     * failure is also conservative: the file could still be referenced by a document unseen here.
     */
    private fun quarantinedDocumentsReference(uri: String): Boolean {
        val directory = queueDirectory()
        if (!directory.exists()) return false
        val files = directory.listFiles() ?: return true
        return files.asSequence()
            .filter { it.isFile && it.name.contains(QUARANTINE_MARKER) }
            .any { file -> runCatching { file.readText().contains(uri) }.getOrDefault(true) }
    }

    /** Null when the queue directory cannot be listed; callers that decide deletions stay safe. */
    private fun queueIds(): Set<Long>? = runCatching {
        queueDirectory().listFiles()
            ?.asSequence()
            ?.filter { it.isFile && it.name.endsWith(DOCUMENT_SUFFIX) }
            ?.mapNotNull { it.name.removeSuffix(DOCUMENT_SUFFIX).toLongOrNull()?.takeIf { id -> id > 0L } }
            ?.toSet()
            .orEmpty()
    }.getOrNull()

    private fun moveReplacing(source: File, target: File) {
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun appPrivateAttachmentFile(attachment: PendingAttachment): File? {
        val rawPath = localPath(attachment.uri.trim()) ?: return null
        val file = runCatching { File(rawPath).canonicalFile }.getOrNull() ?: return null
        val directory = runCatching { attachmentDirectory.canonicalFile }.getOrNull() ?: return null
        return file.takeIf { it.parentFile == directory }
    }

    /** Local path for plain and `file://` URIs; null for content and unknown schemes. */
    private fun localPath(uri: String): String? = when {
        uri.isEmpty() -> null
        uri.startsWith(CONTENT_SCHEME, ignoreCase = true) -> null
        uri.startsWith(FILE_SCHEME, ignoreCase = true) -> runCatching { URI(uri).path }.getOrNull()
        hasScheme(uri) -> null
        else -> uri
    }

    private fun hasScheme(uri: String): Boolean {
        val separator = uri.indexOf(':')
        if (separator <= 0) return false
        val scheme = uri.substring(0, separator)
        return scheme.first().isLetter() && scheme.all { it.isLetterOrDigit() || it in "+-." }
    }

    private fun queueDirectory(): File = File(filesDir, QUEUE_DIRECTORY)

    private fun queueFile(conversationId: Long): File = File(queueDirectory(), "$conversationId$DOCUMENT_SUFFIX")

    private companion object {
        const val QUEUE_DIRECTORY = "queued_followups"
        const val DOCUMENT_SUFFIX = ".json"
        const val QUARANTINE_SUFFIX = ".corrupt"
        const val QUARANTINE_MARKER = ".json$QUARANTINE_SUFFIX-"
        const val CONTENT_SCHEME = "content:"
        const val FILE_SCHEME = "file:"
    }
}
