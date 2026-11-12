package com.yukisoffd.lyracode

import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteOpenHelper
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
            assertEquals(14, store.readableDatabase.version)
            assertEquals("history before upgrade", store.message(message)?.content)
            assertNotNull(store.conversation(conversation))
            store.beginSubAgentBatch(conversation, "migrated", message, listOf(seed(0, false)))
            assertEquals("migrated", store.openSubAgentBatch(conversation)?.batchId)
        }
    }

    @Test fun version13MigrationKeepsBatchRowsAndRepointSurvivesReopening() = withDatabase { isolated, _ ->
        var parent = 0L
        var user = 0L
        ConversationStore(isolated).use { store ->
            parent = store.createConversation("test", "test")
            user = store.addMessage(parent, "user", "saved version-13 turn")
            store.beginSubAgentBatch(parent, "version13-batch", user, listOf(seed(0, true)))
            val db = store.writableDatabase
            // Historical version-13 schema, before tool_call_id existed.
            db.execSQL("""CREATE TABLE legacy_sub_agent_tasks (
                parent_conversation_id INTEGER NOT NULL, batch_id TEXT NOT NULL, task_index INTEGER NOT NULL,
                child_conversation_id INTEGER NOT NULL DEFAULT 0, task_text TEXT NOT NULL,
                write_paths TEXT NOT NULL DEFAULT '[]', read_only INTEGER NOT NULL DEFAULT 0,
                expected_output TEXT NOT NULL DEFAULT '', capability_hint TEXT NOT NULL DEFAULT '',
                preferred_agent TEXT NOT NULL DEFAULT '', user_message_id INTEGER NOT NULL DEFAULT 0,
                status TEXT NOT NULL, delivered INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL,
                PRIMARY KEY (parent_conversation_id, batch_id, task_index))""")
            db.execSQL("""INSERT INTO legacy_sub_agent_tasks SELECT parent_conversation_id, batch_id, task_index,
                child_conversation_id, task_text, write_paths, read_only, expected_output, capability_hint,
                preferred_agent, user_message_id, status, delivered, created_at, updated_at FROM sub_agent_tasks""")
            db.execSQL("DROP TABLE sub_agent_tasks")
            db.execSQL("ALTER TABLE legacy_sub_agent_tasks RENAME TO sub_agent_tasks")
            db.execSQL("CREATE INDEX idx_sub_agent_tasks_open ON sub_agent_tasks(parent_conversation_id, delivered)")
            db.version = 13
        }
        ConversationStore(isolated).use { store ->
            val batch = requireNotNull(store.openSubAgentBatch(parent))
            assertEquals(14, store.readableDatabase.version)
            assertEquals("", batch.toolCallId)
            assertEquals(user, batch.userMessageId)
            assertEquals("task 0", batch.tasks.single().taskText)
            assertEquals("saved version-13 turn", store.message(user)?.content)
            store.setSubAgentBatchToolCallId(parent, batch.batchId, "retry-call")
        }
        ConversationStore(isolated).use { store ->
            assertEquals("retry-call", store.openSubAgentBatch(parent)?.toolCallId)
            store.markSubAgentBatchDelivered(parent, "version13-batch")
            store.setSubAgentBatchToolCallId(parent, "version13-batch", "must-not-repoint-delivered")
            store.readableDatabase.rawQuery("SELECT tool_call_id FROM sub_agent_tasks WHERE batch_id='version13-batch'", null).use {
                assertTrue(it.moveToFirst())
                assertEquals("retry-call", it.getString(0))
            }
        }
    }

    @Test fun version13DowngradeIsRejectedWithoutDamagingVersion14History() = withDatabase { isolated, _ ->
        var message = 0L
        ConversationStore(isolated).use { store ->
            val parent = store.createConversation("test", "test")
            message = store.addMessage(parent, "user", "keep after rejected downgrade")
        }
        object : SQLiteOpenHelper(isolated, "lyra_conversations.db", null, 13) {
            override fun onCreate(db: SQLiteDatabase) = error("Existing database expected")
            override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = error("Downgrade expected")
        }.use { legacy ->
            val error = assertThrows(SQLiteException::class.java) { legacy.writableDatabase }
            assertTrue(error.message.orEmpty(), error.message.orEmpty().contains("14 to 13"))
        }
        ConversationStore(isolated).use { store ->
            assertEquals(14, store.readableDatabase.version)
            assertEquals("keep after rejected downgrade", store.message(message)?.content)
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
