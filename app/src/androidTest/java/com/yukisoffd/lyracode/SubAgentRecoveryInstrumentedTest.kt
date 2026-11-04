package com.yukisoffd.lyracode

import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.Environment
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yukisoffd.lyracode.data.*
import com.yukisoffd.lyracode.workspace.NativeFileManager
import com.yukisoffd.lyracode.workspace.TextBackupStore
import com.yukisoffd.lyracode.workspace.WorkspaceManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SubAgentRecoveryInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun withDatabase(block: (Context, File) -> Unit) {
        val directory = File(context.cacheDir, "pr59-db-${UUID.randomUUID()}").apply { mkdirs() }
        val isolated = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getDatabasePath(name: String): File = File(directory, name)
            override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase =
                SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name), factory)
            override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, errorHandler: DatabaseErrorHandler?): SQLiteDatabase =
                SQLiteDatabase.openDatabase(getDatabasePath(name).path, factory, SQLiteDatabase.CREATE_IF_NECESSARY, errorHandler)
        }
        try { block(isolated, File(directory, "lyra_conversations.db")) }
        finally { directory.deleteRecursively() }
    }

    @Test fun version12DatabaseUpgradesAndKeepsHistory() = withDatabase { isolated, path ->
        var conversation = 0L
        var message = 0L
        ConversationStore(isolated).use { store ->
            conversation = store.createConversation("test", "test")
            message = store.addMessage(conversation, "user", "history before upgrade")
            // The preceding schema is identical except for the new table and version.
            store.writableDatabase.execSQL("DROP TABLE sub_agent_tasks")
            store.writableDatabase.version = 12
        }
        assertTrue(path.isFile)
        ConversationStore(isolated).use { store ->
            assertEquals(13, store.readableDatabase.version)
            assertEquals("history before upgrade", store.message(message)?.content)
            assertNotNull(store.conversation(conversation))
            store.beginSubAgentBatch(conversation, "migrated", message, listOf(seed(0, false)))
            assertEquals("migrated", store.openSubAgentBatch(conversation)?.batchId)
        }
    }

    @Test fun unfinishedBatchSurvivesReopeningAndEditReportsStartedWrites() = withDatabase { isolated, _ ->
        var parent = 0L
        var user = 0L
        var child = 0L
        ConversationStore(isolated).use { store ->
            parent = store.createConversation("test", "test", workspaceUri = "file:///fixture")
            user = store.addMessage(parent, "user", "perform tasks")
            child = store.createConversation("test", "test", mode = ConversationStore.MODE_SUBAGENT)
            store.beginSubAgentBatch(parent, "durable-batch", user, listOf(seed(0, false), seed(1, true)))
            store.attachSubAgentTaskChild(parent, "durable-batch", 0, child)
        }
        ConversationStore(isolated).use { store ->
            val batch = requireNotNull(store.openSubAgentBatch(parent))
            assertEquals(user, batch.userMessageId)
            assertEquals(listOf(child, 0L), batch.tasks.map { it.childConversationId })
            assertEquals(listOf(SubAgentTaskStatus.RUNNING, SubAgentTaskStatus.PENDING), batch.tasks.map { it.status })
            assertEquals(listOf(InterruptedWritePath("src/output.txt", "durable-batch", "file:///fixture")), store.openSubAgentWriteTasks())
            val closed = store.deleteMessagesAfter(parent, user)
            assertEquals(listOf(child), closed.map { it.childConversationId })
            assertNull(store.openSubAgentBatch(parent))
            assertTrue(store.openSubAgentWriteTasks().isEmpty())
        }
        ConversationStore(isolated).use { assertNull(it.openSubAgentBatch(parent)) }
    }

    @Test fun recoveryCopyUsesStoredWorkspaceAndSurvivesLaterWrites() {
        assertTrue("Grant MANAGE_EXTERNAL_STORAGE with appops before this test", Environment.isExternalStorageManager())
        val root = File(Environment.getExternalStorageDirectory(), "pr59-backup-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val first = File(root, "first").apply { mkdirs() }
            val second = File(root, "second").apply { mkdirs() }
            val firstUri = Uri.fromFile(first).toString()
            val manager = WorkspaceManager(context, AppSettings(context))
            val files = NativeFileManager(context, manager)
            manager.setActiveWorkspaceUri(firstUri)
            files.writeFile("output.txt", "original").getOrThrow()
            files.writeFile("output.txt", "interrupted").getOrThrow()
            manager.setActiveWorkspaceUri(Uri.fromFile(second).toString())
            files.writeFile("output.txt", "unrelated").getOrThrow()
            val write = InterruptedWritePath("output.txt", "12345678-batch", firstUri)
            val preserved = TextBackupStore.interruptedBackupPath(write.path, write.batchId)
            assertEquals(listOf(preserved), files.preserveInterruptedBackups(listOf(write)))
            assertEquals("original", File(first, preserved).readText())
            assertFalse(File(second, preserved).exists())
            manager.setActiveWorkspaceUri(firstUri)
            files.writeFile("output.txt", "later").getOrThrow()
            files.preserveInterruptedBackups(listOf(write))
            assertEquals("original", File(first, preserved).readText())
            assertEquals("interrupted", File(first, "output.txt.bak").readText())
            assertEquals("later", File(first, "output.txt").readText())
            assertFalse(File(first, "$preserved.tmp").exists())
        } finally { root.deleteRecursively() }
    }

    private fun seed(index: Int, readOnly: Boolean) = SubAgentTaskSeed(
        index, "task $index", if (readOnly) emptyList() else listOf("src/output.txt"), readOnly,
        "expected $index", "capability", "agent",
    )
}
