package com.yukisoffd.lyracode.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import com.yukisoffd.lyracode.R
import com.yukisoffd.lyracode.workspace.UploadedFileManager
import com.yukisoffd.lyracode.workspace.externalizeInlineAttachmentDataUrls
import com.yukisoffd.lyracode.workspace.inlineLocalAttachmentDataUrls
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

data class Conversation(
    val id: Long,
    val title: String,
    val status: String,
    val profileId: String,
    val model: String,
    val createdAt: Long,
    val updatedAt: Long,
    val pinnedAt: Long,
    val archivedAt: Long,
    val mode: String = ConversationStore.MODE_NORMAL,
    val workspaceUri: String = "",
    val compressedContext: String = "",
    val compressedThroughMessageId: Long = 0L,
    val autoCompressionMode: String = ConversationStore.AUTO_COMPRESSION_OFF,
    val autoCompressionTurnThreshold: Int = ConversationStore.DEFAULT_AUTO_COMPRESSION_TURNS,
    val autoCompressionTokenThreshold: Long = ConversationStore.DEFAULT_AUTO_COMPRESSION_TOKENS,
    val projectId: Long = 0L,
)

data class ChatProject(
    val id: Long,
    val name: String,
    val workspaceUri: String,
    val createdAt: Long,
    val updatedAt: Long,
    val pinnedAt: Long,
    val archivedAt: Long,
)

data class ChatMessage(
    val id: Long,
    val conversationId: Long,
    val role: String,
    val content: String,
    val thinking: String,
    val profileId: String,
    val model: String,
    val toolCallId: String?,
    val rawJson: String?,
    val tokensPerSecond: Double = 0.0,
    val deepSeekCacheHitRate: Double? = null,
    val createdAt: Long,
)

data class UsageMessageEvent(
    val messageId: Long,
    val conversationId: Long,
    val role: String,
    val createdAt: Long,
)

data class UsageModelRequest(
    val assistantMessageId: Long,
    val conversationId: Long,
    val createdAt: Long,
    val inputTokens: Long,
    val outputTokens: Long,
    val model: String = "",
)

class ConversationStore(private val appContext: Context, inMemory: Boolean = false) : SQLiteOpenHelper(
    appContext.applicationContext,
    if (inMemory) null else "lyra_conversations.db",
    null,
    11,
) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE conversations (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                title TEXT NOT NULL,
                status TEXT NOT NULL,
                profile_id TEXT NOT NULL,
                model TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                pinned_at INTEGER NOT NULL DEFAULT 0,
                archived_at INTEGER NOT NULL DEFAULT 0,
                mode TEXT NOT NULL DEFAULT 'normal',
                workspace_uri TEXT NOT NULL DEFAULT '',
                compressed_context TEXT NOT NULL DEFAULT '',
                compressed_through_message_id INTEGER NOT NULL DEFAULT 0,
                auto_compression_mode TEXT NOT NULL DEFAULT 'off',
                auto_compression_turn_threshold INTEGER NOT NULL DEFAULT 20,
                auto_compression_token_threshold INTEGER NOT NULL DEFAULT 131072,
                project_id INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
        )
        createProjectsTable(db)
        db.execSQL(
            """
            CREATE TABLE messages (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                conversation_id INTEGER NOT NULL,
                role TEXT NOT NULL,
                content TEXT NOT NULL,
                thinking TEXT NOT NULL,
                profile_id TEXT NOT NULL,
                model TEXT NOT NULL,
                tool_call_id TEXT,
                raw_json TEXT,
                tokens_per_second REAL NOT NULL DEFAULT 0,
                deepseek_cache_hit_rate REAL NOT NULL DEFAULT -1,
                created_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        createUsageTables(db)
    }

    private fun createUsageTables(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS usage_message_events (
                message_id INTEGER PRIMARY KEY,
                conversation_id INTEGER NOT NULL,
                role TEXT NOT NULL,
                created_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS usage_model_requests (
                assistant_message_id INTEGER PRIMARY KEY,
                conversation_id INTEGER NOT NULL,
                created_at INTEGER NOT NULL,
                input_tokens INTEGER NOT NULL,
                output_tokens INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_usage_message_events_created_at ON usage_message_events(created_at)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_usage_model_requests_created_at ON usage_model_requests(created_at)")
    }

    private fun createProjectsTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS projects (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                workspace_uri TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                pinned_at INTEGER NOT NULL DEFAULT 0,
                archived_at INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_conversations_project_id ON conversations(project_id)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE conversations ADD COLUMN pinned_at INTEGER NOT NULL DEFAULT 0")
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE conversations ADD COLUMN mode TEXT NOT NULL DEFAULT 'normal'")
            db.execSQL("ALTER TABLE conversations ADD COLUMN roleplay_id TEXT NOT NULL DEFAULT ''")
        }
        if (oldVersion < 4) {
            db.execSQL("ALTER TABLE conversations ADD COLUMN workspace_uri TEXT NOT NULL DEFAULT ''")
        }
        if (oldVersion < 5) {
            db.execSQL("ALTER TABLE messages ADD COLUMN tokens_per_second REAL NOT NULL DEFAULT 0")
        }
        if (oldVersion < 6) {
            createUsageTables(db)
            db.execSQL(
                """
                INSERT OR IGNORE INTO usage_message_events (message_id, conversation_id, role, created_at)
                SELECT id, conversation_id, role, created_at FROM messages
                """.trimIndent(),
            )
        }
        if (oldVersion < 7) {
            db.execSQL("DELETE FROM usage_message_events WHERE conversation_id IN (SELECT id FROM conversations WHERE mode = 'roleplay')")
            db.execSQL("DELETE FROM usage_model_requests WHERE conversation_id IN (SELECT id FROM conversations WHERE mode = 'roleplay')")
            db.execSQL("DELETE FROM messages WHERE conversation_id IN (SELECT id FROM conversations WHERE mode = 'roleplay')")
            db.execSQL("DELETE FROM conversations WHERE mode = 'roleplay'")
            db.execSQL(
                """
                CREATE TABLE conversations_new (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    title TEXT NOT NULL,
                    status TEXT NOT NULL,
                    profile_id TEXT NOT NULL,
                    model TEXT NOT NULL,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL,
                    pinned_at INTEGER NOT NULL DEFAULT 0,
                    mode TEXT NOT NULL DEFAULT 'normal',
                    workspace_uri TEXT NOT NULL DEFAULT ''
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO conversations_new (id, title, status, profile_id, model, created_at, updated_at, pinned_at, mode, workspace_uri)
                SELECT id, title, status, profile_id, model, created_at, updated_at, pinned_at, mode, workspace_uri
                FROM conversations
                """.trimIndent(),
            )
            db.execSQL("DROP TABLE conversations")
            db.execSQL("ALTER TABLE conversations_new RENAME TO conversations")
        }
        if (oldVersion < 8) {
            db.execSQL("ALTER TABLE conversations ADD COLUMN archived_at INTEGER NOT NULL DEFAULT 0")
        }
        if (oldVersion < 9) {
            db.execSQL("ALTER TABLE conversations ADD COLUMN compressed_context TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE conversations ADD COLUMN compressed_through_message_id INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE conversations ADD COLUMN auto_compression_mode TEXT NOT NULL DEFAULT 'off'")
            db.execSQL("ALTER TABLE conversations ADD COLUMN auto_compression_turn_threshold INTEGER NOT NULL DEFAULT 20")
            db.execSQL("ALTER TABLE conversations ADD COLUMN auto_compression_token_threshold INTEGER NOT NULL DEFAULT 131072")
        }
        if (oldVersion < 10) {
            db.execSQL("ALTER TABLE conversations ADD COLUMN project_id INTEGER NOT NULL DEFAULT 0")
            createProjectsTable(db)
        }
        if (oldVersion < 11) {
            db.execSQL("ALTER TABLE messages ADD COLUMN deepseek_cache_hit_rate REAL NOT NULL DEFAULT -1")
        }
    }

    fun createConversation(
        profileId: String,
        model: String,
        title: String = appContext.getString(R.string.default_conversation_title),
        mode: String = MODE_NORMAL,
        workspaceUri: String = "",
        projectId: Long = 0L,
    ): Long {
        val now = System.currentTimeMillis()
        return writableDatabase.insert(
            "conversations",
            null,
            ContentValues().apply {
                put("title", title)
                put("status", STATUS_IDLE)
                put("profile_id", profileId)
                put("model", model)
                put("created_at", now)
                put("updated_at", now)
                put("mode", mode)
                put("workspace_uri", workspaceUri)
                put("project_id", projectId.coerceAtLeast(0L))
            },
        )
    }

    fun createConversationBranch(
        sourceConversationId: Long,
        throughMessageId: Long,
        title: String,
    ): Long {
        val source = conversation(sourceConversationId) ?: return -1L
        val target = message(throughMessageId) ?: return -1L
        if (target.conversationId != sourceConversationId || target.role !in setOf("user", "assistant")) {
            return -1L
        }
        val sourceMessages = messages(sourceConversationId).filter { it.id <= throughMessageId }
        if (sourceMessages.none { it.id == throughMessageId }) return -1L

        val db = writableDatabase
        val now = System.currentTimeMillis()
        db.beginTransaction()
        return try {
            val branchId = db.insertOrThrow(
                "conversations",
                null,
                ContentValues().apply {
                    put("title", title.take(120))
                    put("status", STATUS_IDLE)
                    put("profile_id", source.profileId)
                    put("model", source.model)
                    put("created_at", now)
                    put("updated_at", now)
                    put("pinned_at", 0L)
                    put("archived_at", 0L)
                    put("mode", source.mode)
                    put("workspace_uri", source.workspaceUri)
                    put("compressed_context", "")
                    put("compressed_through_message_id", 0L)
                    put("auto_compression_mode", source.autoCompressionMode)
                    put("auto_compression_turn_threshold", source.autoCompressionTurnThreshold)
                    put("auto_compression_token_threshold", source.autoCompressionTokenThreshold)
                    put("project_id", source.projectId)
                },
            )
            sourceMessages.forEach { sourceMessage ->
                db.insertOrThrow(
                    "messages",
                    null,
                    ContentValues().apply {
                        put("conversation_id", branchId)
                        put("role", sourceMessage.role)
                        put("content", sourceMessage.content)
                        put("thinking", sourceMessage.thinking)
                        put("profile_id", sourceMessage.profileId)
                        put("model", sourceMessage.model)
                        put("tool_call_id", sourceMessage.toolCallId)
                        put("raw_json", sourceMessage.rawJson)
                        put("tokens_per_second", sourceMessage.tokensPerSecond)
                        put("deepseek_cache_hit_rate", sourceMessage.deepSeekCacheHitRate ?: -1.0)
                        put("created_at", sourceMessage.createdAt)
                    },
                )
            }
            db.setTransactionSuccessful()
            branchId
        } finally {
            db.endTransaction()
        }
    }

    private fun createImportedConversation(
        profileId: String,
        model: String,
        title: String,
        status: String,
        createdAt: Long,
        updatedAt: Long,
        pinnedAt: Long,
        archivedAt: Long,
        mode: String,
        workspaceUri: String,
        compressedContext: String,
        autoCompressionMode: String,
        autoCompressionTurnThreshold: Int,
        autoCompressionTokenThreshold: Long,
        projectId: Long,
    ): Long {
        return writableDatabase.insert(
            "conversations",
            null,
            ContentValues().apply {
                put("title", title.take(120))
                put("status", status)
                put("profile_id", profileId)
                put("model", model)
                put("created_at", createdAt)
                put("updated_at", updatedAt)
                put("pinned_at", pinnedAt)
                put("archived_at", archivedAt)
                put("mode", mode)
                put("workspace_uri", workspaceUri)
                put("compressed_context", compressedContext)
                put("auto_compression_mode", autoCompressionMode)
                put("auto_compression_turn_threshold", autoCompressionTurnThreshold)
                put("auto_compression_token_threshold", autoCompressionTokenThreshold)
                put("project_id", projectId.coerceAtLeast(0L))
            },
        )
    }

    fun conversations(
        mode: String? = null,
        includeSubAgents: Boolean = false,
        archived: Boolean? = false,
    ): List<Conversation> {
        val clauses = mutableListOf<String>()
        val args = mutableListOf<String>()
        mode?.let {
            clauses += "mode=?"
            args += it
        }
        if (mode == null && !includeSubAgents) {
            clauses += "mode<>?"
            args += MODE_SUBAGENT
        }
        archived?.let {
            clauses += if (it) "archived_at>0" else "archived_at=0"
        }
        val cursor = readableDatabase.query(
            "conversations",
            CONVERSATION_COLUMNS,
            clauses.joinToString(" AND ").ifBlank { null },
            args.toTypedArray().ifEmpty { null },
            null,
            null,
            if (archived == true) {
                "archived_at DESC"
            } else {
                "CASE WHEN pinned_at > 0 THEN 0 ELSE 1 END ASC, pinned_at DESC, updated_at DESC"
            },
        )
        return cursor.use {
            buildList {
                while (it.moveToNext()) {
                    add(
                        Conversation(
                            id = it.getLong(0),
                            title = it.getString(1),
                            status = it.getString(2),
                            profileId = it.getString(3),
                            model = it.getString(4),
                            createdAt = it.getLong(5),
                            updatedAt = it.getLong(6),
                            pinnedAt = it.getLong(7),
                            archivedAt = it.getLong(8),
                            mode = it.getString(9),
                            workspaceUri = it.getString(10),
                            compressedContext = it.getString(11),
                            compressedThroughMessageId = it.getLong(12),
                            autoCompressionMode = it.getString(13),
                            autoCompressionTurnThreshold = it.getInt(14),
                            autoCompressionTokenThreshold = it.getLong(15),
                            projectId = it.getLong(16),
                        ),
                    )
                }
            }
        }
    }

    fun conversation(id: Long): Conversation? {
        return readableDatabase.query(
            "conversations",
            CONVERSATION_COLUMNS,
            "id=?",
            arrayOf(id.toString()),
            null,
            null,
            null,
        ).use {
            if (!it.moveToFirst()) return null
            Conversation(
                id = it.getLong(0), title = it.getString(1), status = it.getString(2),
                profileId = it.getString(3), model = it.getString(4), createdAt = it.getLong(5),
                updatedAt = it.getLong(6), pinnedAt = it.getLong(7), archivedAt = it.getLong(8),
                mode = it.getString(9), workspaceUri = it.getString(10), compressedContext = it.getString(11),
                compressedThroughMessageId = it.getLong(12), autoCompressionMode = it.getString(13),
                autoCompressionTurnThreshold = it.getInt(14), autoCompressionTokenThreshold = it.getLong(15),
                projectId = it.getLong(16),
            )
        }
    }

    fun setPinned(id: Long, pinned: Boolean) {
        writableDatabase.update(
            "conversations",
            ContentValues().apply { put("pinned_at", if (pinned) System.currentTimeMillis() else 0L) },
            "id=?",
            arrayOf(id.toString()),
        )
    }

    fun setArchived(id: Long, archived: Boolean) {
        writableDatabase.update(
            "conversations",
            ContentValues().apply { put("archived_at", if (archived) System.currentTimeMillis() else 0L) },
            "id=?",
            arrayOf(id.toString()),
        )
    }

    fun deleteConversation(id: Long) {
        writableDatabase.delete("usage_model_requests", "conversation_id=?", arrayOf(id.toString()))
        writableDatabase.delete("usage_message_events", "conversation_id=?", arrayOf(id.toString()))
        writableDatabase.delete("messages", "conversation_id=?", arrayOf(id.toString()))
        writableDatabase.delete("conversations", "id=?", arrayOf(id.toString()))
    }


    fun setConversationMeta(
        id: Long,
        title: String? = null,
        status: String? = null,
        profileId: String? = null,
        model: String? = null,
        workspaceUri: String? = null,
        projectId: Long? = null,
    ) {
        val now = System.currentTimeMillis()
        val values = ContentValues().apply {
            title?.let { put("title", it.take(120)) }
            status?.let { put("status", it) }
            profileId?.let { put("profile_id", it) }
            model?.let { put("model", it) }
            workspaceUri?.let { put("workspace_uri", it) }
            projectId?.let { put("project_id", it.coerceAtLeast(0L)) }
            put("updated_at", now)
        }
        val db = writableDatabase
        db.update("conversations", values, "id=?", arrayOf(id.toString()))
        db.update(
            "projects",
            ContentValues().apply { put("updated_at", now) },
            "id=(SELECT project_id FROM conversations WHERE id=?)",
            arrayOf(id.toString()),
        )
    }

    fun createProject(name: String, workspaceUri: String): Long {
        val now = System.currentTimeMillis()
        return writableDatabase.insert(
            "projects",
            null,
            ContentValues().apply {
                put("name", name.trim().ifBlank { appContext.getString(R.string.default_project_name) }.take(120))
                put("workspace_uri", workspaceUri)
                put("created_at", now)
                put("updated_at", now)
            },
        )
    }

    private fun createImportedProject(
        name: String,
        workspaceUri: String,
        createdAt: Long,
        updatedAt: Long,
        pinnedAt: Long,
        archivedAt: Long,
    ): Long = writableDatabase.insert(
        "projects",
        null,
        ContentValues().apply {
            put("name", name.trim().ifBlank { appContext.getString(R.string.default_project_name) }.take(120))
            put("workspace_uri", workspaceUri)
            put("created_at", createdAt)
            put("updated_at", updatedAt)
            put("pinned_at", pinnedAt)
            put("archived_at", archivedAt)
        },
    )

    private fun importedProjectId(
        name: String,
        workspaceUri: String,
        createdAt: Long,
        updatedAt: Long,
        archivedAt: Long,
    ): Long? = readableDatabase.query(
        "projects",
        arrayOf("id"),
        "name=? AND workspace_uri=? AND created_at=? AND updated_at=? AND archived_at=?",
        arrayOf(
            name.take(120),
            workspaceUri,
            createdAt.toString(),
            updatedAt.toString(),
            archivedAt.toString(),
        ),
        null,
        null,
        null,
    ).use { cursor ->
        if (cursor.moveToFirst()) cursor.getLong(0) else null
    }

    fun projects(archived: Boolean = false): List<ChatProject> {
        return readableDatabase.query(
            "projects",
            PROJECT_COLUMNS,
            if (archived) "archived_at>0" else "archived_at=0",
            null,
            null,
            null,
            if (archived) {
                "archived_at DESC"
            } else {
                "CASE WHEN pinned_at > 0 THEN 0 ELSE 1 END ASC, pinned_at DESC, updated_at DESC"
            },
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.toProject())
            }
        }
    }

    fun project(id: Long): ChatProject? {
        return readableDatabase.query(
            "projects",
            PROJECT_COLUMNS,
            "id=?",
            arrayOf(id.toString()),
            null,
            null,
            null,
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.toProject() else null
        }
    }

    fun conversationsForProject(projectId: Long, archived: Boolean? = false): List<Conversation> =
        conversations(archived = archived).filter { it.projectId == projectId }

    fun renameProject(id: Long, name: String) {
        writableDatabase.update(
            "projects",
            ContentValues().apply {
                put("name", name.trim().take(120))
                put("updated_at", System.currentTimeMillis())
            },
            "id=?",
            arrayOf(id.toString()),
        )
    }

    fun updateProjectWorkspace(id: Long, workspaceUri: String) {
        val db = writableDatabase
        db.update(
            "projects",
            ContentValues().apply {
                put("workspace_uri", workspaceUri)
                put("updated_at", System.currentTimeMillis())
            },
            "id=?",
            arrayOf(id.toString()),
        )
        db.update(
            "conversations",
            ContentValues().apply {
                put("workspace_uri", workspaceUri)
                put("updated_at", System.currentTimeMillis())
            },
            "project_id=?",
            arrayOf(id.toString()),
        )
    }

    fun setProjectPinned(id: Long, pinned: Boolean) {
        writableDatabase.update(
            "projects",
            ContentValues().apply {
                put("pinned_at", if (pinned) System.currentTimeMillis() else 0L)
                put("updated_at", System.currentTimeMillis())
            },
            "id=?",
            arrayOf(id.toString()),
        )
    }

    fun setProjectArchived(id: Long, archived: Boolean) {
        writableDatabase.update(
            "projects",
            ContentValues().apply {
                put("archived_at", if (archived) System.currentTimeMillis() else 0L)
                put("updated_at", System.currentTimeMillis())
            },
            "id=?",
            arrayOf(id.toString()),
        )
    }

    fun deleteProject(id: Long) {
        val conversationIds = conversationsForProject(id, archived = null).map { it.id }
        val db = writableDatabase
        db.beginTransaction()
        try {
            conversationIds.forEach { conversationId ->
                db.delete("usage_model_requests", "conversation_id=?", arrayOf(conversationId.toString()))
                db.delete("usage_message_events", "conversation_id=?", arrayOf(conversationId.toString()))
                db.delete("messages", "conversation_id=?", arrayOf(conversationId.toString()))
                db.delete("conversations", "id=?", arrayOf(conversationId.toString()))
            }
            db.delete("projects", "id=?", arrayOf(id.toString()))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun android.database.Cursor.toProject(): ChatProject = ChatProject(
        id = getLong(0),
        name = getString(1),
        workspaceUri = getString(2),
        createdAt = getLong(3),
        updatedAt = getLong(4),
        pinnedAt = getLong(5),
        archivedAt = getLong(6),
    )

    fun setCompressedContext(id: Long, summary: String, throughMessageId: Long) {
        writableDatabase.update(
            "conversations",
            ContentValues().apply {
                put("compressed_context", summary.trim())
                put("compressed_through_message_id", throughMessageId.coerceAtLeast(0L))
                put("updated_at", System.currentTimeMillis())
            },
            "id=?",
            arrayOf(id.toString()),
        )
    }

    fun clearCompressedContext(id: Long) = setCompressedContext(id, "", 0L)

    fun setAutoCompression(
        id: Long,
        mode: String,
        turnThreshold: Int,
        tokenThreshold: Long,
    ) {
        val normalizedMode = mode.takeIf { it in AUTO_COMPRESSION_MODES } ?: AUTO_COMPRESSION_OFF
        writableDatabase.update(
            "conversations",
            ContentValues().apply {
                put("auto_compression_mode", normalizedMode)
                put("auto_compression_turn_threshold", turnThreshold.coerceIn(1, 10_000))
                put("auto_compression_token_threshold", tokenThreshold.coerceIn(1_024L, 16_777_216L))
                put("updated_at", System.currentTimeMillis())
            },
            "id=?",
            arrayOf(id.toString()),
        )
    }

    fun addMessage(
        conversationId: Long,
        role: String,
        content: String,
        thinking: String = "",
        profileId: String = "",
        model: String = "",
        toolCallId: String? = null,
        rawJson: String? = null,
        tokensPerSecond: Double = 0.0,
        deepSeekCacheHitRate: Double? = null,
    ): Long {
        val now = System.currentTimeMillis()
        val storedContent = messageContentForStorage(content)
        val id = writableDatabase.insert(
            "messages",
            null,
            ContentValues().apply {
                put("conversation_id", conversationId)
                put("role", role)
                put("content", storedContent)
                put("thinking", thinking)
                put("profile_id", profileId)
                put("model", model)
                put("tool_call_id", toolCallId)
                put("raw_json", rawJson)
                put("tokens_per_second", tokensPerSecond.coerceAtLeast(0.0))
                put("deepseek_cache_hit_rate", deepSeekCacheHitRate?.coerceIn(0.0, 100.0) ?: -1.0)
                put("created_at", now)
            },
        )
        setConversationMeta(conversationId)
        recordUsageMessageEvent(id, conversationId, role, now)
        return id
    }

    private fun addImportedMessage(
        conversationId: Long,
        role: String,
        content: String,
        thinking: String = "",
        profileId: String = "",
        model: String = "",
        toolCallId: String? = null,
        rawJson: String? = null,
        tokensPerSecond: Double = 0.0,
        deepSeekCacheHitRate: Double? = null,
        createdAt: Long,
    ): Long {
        val storedContent = messageContentForStorage(content)
        val id = writableDatabase.insert(
            "messages",
            null,
            ContentValues().apply {
                put("conversation_id", conversationId)
                put("role", role)
                put("content", storedContent)
                put("thinking", thinking)
                put("profile_id", profileId)
                put("model", model)
                put("tool_call_id", toolCallId)
                put("raw_json", rawJson)
                put("tokens_per_second", tokensPerSecond.coerceAtLeast(0.0))
                put("deepseek_cache_hit_rate", deepSeekCacheHitRate?.coerceIn(0.0, 100.0) ?: -1.0)
                put("created_at", createdAt)
            },
        )
        recordUsageMessageEvent(id, conversationId, role, createdAt)
        return id
    }

    fun updateMessage(
        id: Long,
        content: String? = null,
        thinking: String? = null,
        rawJson: String? = null,
        tokensPerSecond: Double? = null,
        deepSeekCacheHitRate: Double? = null,
    ) {
        val values = ContentValues().apply {
            content?.let { put("content", it) }
            thinking?.let { put("thinking", it) }
            rawJson?.let { put("raw_json", it) }
            tokensPerSecond?.let { put("tokens_per_second", it.coerceAtLeast(0.0)) }
            deepSeekCacheHitRate?.let { put("deepseek_cache_hit_rate", it.coerceIn(0.0, 100.0)) }
        }
        writableDatabase.update("messages", values, "id=?", arrayOf(id.toString()))
        val conversationId = readableDatabase.query("messages", arrayOf("conversation_id"), "id=?", arrayOf(id.toString()), null, null, null).use {
            if (it.moveToFirst()) it.getLong(0) else null
        }
        conversationId?.let { setConversationMeta(it) }
    }

    fun message(id: Long): ChatMessage? {
        return readableDatabase.query(
            "messages",
            arrayOf("id", "conversation_id", "role", "content", "thinking", "profile_id", "model", "tool_call_id", "raw_json", "tokens_per_second", "deepseek_cache_hit_rate", "created_at"),
            "id=?",
            arrayOf(id.toString()),
            null,
            null,
            null,
        ).use {
            if (!it.moveToFirst()) return null
            ChatMessage(
                id = it.getLong(0),
                conversationId = it.getLong(1),
                role = it.getString(2),
                content = it.getString(3),
                thinking = it.getString(4),
                profileId = it.getString(5),
                model = it.getString(6),
                toolCallId = if (it.isNull(7)) null else it.getString(7),
                rawJson = if (it.isNull(8)) null else it.getString(8),
                tokensPerSecond = it.getDouble(9),
                deepSeekCacheHitRate = it.getDouble(10).takeIf { rate -> rate >= 0.0 },
                createdAt = it.getLong(11),
            )
        }
    }

    fun deleteMessagesAfter(conversationId: Long, messageId: Long) {
        writableDatabase.delete(
            "messages",
            "conversation_id=? AND id>?",
            arrayOf(conversationId.toString(), messageId.toString()),
        )
        conversation(conversationId)?.takeIf { it.compressedThroughMessageId >= messageId }?.let {
            clearCompressedContext(conversationId)
        }
        setConversationMeta(conversationId)
    }

    fun recordUsageModelRequest(assistantMessageId: Long, inputTokens: Long, outputTokens: Long) {
        val message = message(assistantMessageId) ?: return
        if (message.role.lowercase() != "assistant") return
        writableDatabase.insertWithOnConflict(
            "usage_model_requests",
            null,
            ContentValues().apply {
                put("assistant_message_id", assistantMessageId)
                put("conversation_id", message.conversationId)
                put("created_at", message.createdAt)
                put("input_tokens", inputTokens.coerceAtLeast(0L))
                put("output_tokens", outputTokens.coerceAtLeast(0L))
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun usageMessageEvents(startAt: Long, endAt: Long): List<UsageMessageEvent> {
        return readableDatabase.query(
            "usage_message_events",
            arrayOf("message_id", "conversation_id", "role", "created_at"),
            "created_at>=? AND created_at<?",
            arrayOf(startAt.toString(), endAt.toString()),
            null,
            null,
            "created_at ASC, message_id ASC",
        ).use {
            buildList {
                while (it.moveToNext()) {
                    add(UsageMessageEvent(it.getLong(0), it.getLong(1), it.getString(2), it.getLong(3)))
                }
            }
        }
    }

    fun usageModelRequests(startAt: Long, endAt: Long): List<UsageModelRequest> {
        return readableDatabase.rawQuery(
            """
            SELECT r.assistant_message_id,
                   r.conversation_id,
                   r.created_at,
                   r.input_tokens,
                   r.output_tokens,
                   COALESCE(m.model, '')
            FROM usage_model_requests AS r
            LEFT JOIN messages AS m ON m.id = r.assistant_message_id
            WHERE r.created_at >= ? AND r.created_at < ?
            ORDER BY r.created_at ASC, r.assistant_message_id ASC
            """.trimIndent(),
            arrayOf(startAt.toString(), endAt.toString()),
        ).use {
            buildList {
                while (it.moveToNext()) {
                    add(
                        UsageModelRequest(
                            assistantMessageId = it.getLong(0),
                            conversationId = it.getLong(1),
                            createdAt = it.getLong(2),
                            inputTokens = it.getLong(3),
                            outputTokens = it.getLong(4),
                            model = it.getString(5).orEmpty(),
                        ),
                    )
                }
            }
        }
    }

    fun usageModelRequestMessageIds(): Set<Long> {
        return readableDatabase.query(
            "usage_model_requests",
            arrayOf("assistant_message_id"),
            null,
            null,
            null,
            null,
            null,
        ).use {
            buildSet {
                while (it.moveToNext()) add(it.getLong(0))
            }
        }
    }

    private fun recordUsageMessageEvent(messageId: Long, conversationId: Long, role: String, createdAt: Long) {
        writableDatabase.insertWithOnConflict(
            "usage_message_events",
            null,
            ContentValues().apply {
                put("message_id", messageId)
                put("conversation_id", conversationId)
                put("role", role)
                put("created_at", createdAt)
            },
            SQLiteDatabase.CONFLICT_IGNORE,
        )
    }

    private fun messageContentForStorage(content: String): String {
        if (!content.contains("\"data_url\"")) return content
        return externalizeInlineAttachmentDataUrls(content, ::persistChatAttachment).content
    }

    private fun messageContentForExport(content: String): String = inlineLocalAttachmentDataUrls(content) { uri, _ ->
        readAttachmentBytes(uri, MAX_STORED_ATTACHMENT_BYTES)
    }

    private fun persistChatAttachment(name: String, mimeType: String, bytes: ByteArray): String {
        require(bytes.size <= MAX_STORED_ATTACHMENT_BYTES) { "Attachment is too large to persist." }
        val directory = File(appContext.filesDir, UploadedFileManager.CHAT_UPLOAD_DIRECTORY).apply { mkdirs() }
        val extension = attachmentExtension(name, mimeType)
        val target = File(directory, "recovered_${UUID.randomUUID()}$extension")
        target.outputStream().use { it.write(bytes) }
        return target.absolutePath
    }

    private fun attachmentExtension(name: String, mimeType: String): String {
        name.substringAfterLast('.', "")
            .takeIf { it.length in 1..10 && it.all(Char::isLetterOrDigit) }
            ?.let { return ".$it" }
        return when (mimeType.lowercase()) {
            "image/png" -> ".png"
            "image/jpeg" -> ".jpg"
            "image/gif" -> ".gif"
            "image/webp" -> ".webp"
            "video/mp4" -> ".mp4"
            "audio/mpeg" -> ".mp3"
            "audio/wav" -> ".wav"
            else -> ".bin"
        }
    }

    private fun readAttachmentBytes(uriText: String, maxBytes: Int): ByteArray? = runCatching {
        val input = when {
            uriText.startsWith("file://", ignoreCase = true) -> {
                val path = Uri.parse(uriText).path ?: return@runCatching null
                File(path).inputStream()
            }
            File(uriText).isFile -> File(uriText).inputStream()
            else -> appContext.contentResolver.openInputStream(Uri.parse(uriText)) ?: return@runCatching null
        }
        input.use {
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var total = 0
            while (true) {
                val read = it.read(buffer)
                if (read < 0) break
                total += read
                require(total <= maxBytes) { "Attachment exceeds ${maxBytes / 1024 / 1024} MB." }
                output.write(buffer, 0, read)
            }
            output.toByteArray()
        }
    }.getOrNull()

    /**
     * Older builds stored media data URLs inside the messages.content SQLite row. A single photo could
     * exceed Android's CursorWindow limit, making the conversation crash every time it was reopened.
     * Read those rows in bounded SQL substrings and replace the inline bytes with an app-private path
     * before the normal message query touches them.
     */
    private fun repairInlineMediaAttachments(conversationId: Long) {
        val candidates = readableDatabase.rawQuery(
            "SELECT id, length(content) FROM messages WHERE conversation_id=? AND content LIKE '%<lyra_attachment_v1>%' AND content LIKE '%\"data_url\"%'",
            arrayOf(conversationId.toString()),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getLong(0) to cursor.getInt(1))
            }
        }
        candidates.forEach { (messageId, contentLength) ->
            val content = readMessageContentInChunks(messageId, contentLength) ?: return@forEach
            val repaired = externalizeInlineAttachmentDataUrls(content, ::persistChatAttachment)
            if (repaired.attachmentCount <= 0) return@forEach
            writableDatabase.update(
                "messages",
                ContentValues().apply { put("content", repaired.content) },
                "id=?",
                arrayOf(messageId.toString()),
            )
        }
    }

    private fun readMessageContentInChunks(messageId: Long, contentLength: Int): String? = runCatching {
        buildString(contentLength.coerceAtLeast(0)) {
            var offset = 1
            while (offset <= contentLength) {
                val chunk = readableDatabase.rawQuery(
                    "SELECT substr(content, ?, ?) FROM messages WHERE id=?",
                    arrayOf(offset.toString(), MESSAGE_CONTENT_CHUNK_CHARS.toString(), messageId.toString()),
                ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0).orEmpty() else "" }
                if (chunk.isEmpty()) break
                append(chunk)
                offset += MESSAGE_CONTENT_CHUNK_CHARS
            }
        }
    }.getOrNull()

    fun messages(conversationId: Long): List<ChatMessage> {
        repairInlineMediaAttachments(conversationId)
        val cursor = readableDatabase.query(
            "messages",
            arrayOf(
                "id",
                "conversation_id",
                "role",
                "CASE WHEN length(content) <= $MAX_CURSOR_MESSAGE_CONTENT_CHARS THEN content ELSE NULL END AS bounded_content",
                "length(content)",
                "thinking",
                "profile_id",
                "model",
                "tool_call_id",
                "raw_json",
                "tokens_per_second",
                "deepseek_cache_hit_rate",
                "created_at",
            ),
            "conversation_id=?",
            arrayOf(conversationId.toString()),
            null,
            null,
            "id ASC",
        )
        return cursor.use {
            buildList {
                while (it.moveToNext()) {
                    val messageId = it.getLong(0)
                    val content = if (it.isNull(3)) {
                        readMessageContentInChunks(messageId, it.getInt(4)).orEmpty()
                    } else {
                        it.getString(3).orEmpty()
                    }
                    add(
                        ChatMessage(
                            id = messageId,
                            conversationId = it.getLong(1),
                            role = it.getString(2),
                            content = content,
                            thinking = it.getString(5),
                            profileId = it.getString(6),
                            model = it.getString(7),
                            toolCallId = if (it.isNull(8)) null else it.getString(8),
                            rawJson = if (it.isNull(9)) null else it.getString(9),
                            tokensPerSecond = it.getDouble(10),
                            deepSeekCacheHitRate = it.getDouble(11).takeIf { rate -> rate >= 0.0 },
                            createdAt = it.getLong(12),
                        ),
                    )
                }
            }
        }
    }

    fun exportJson(): JSONObject {
        return JSONObject()
            .put("schema", "lyra_conversations_backup_v1")
            .put("projects", JSONArray().also { array ->
                (projects(archived = false) + projects(archived = true)).forEach { project ->
                    array.put(
                        JSONObject()
                            .put("id", project.id)
                            .put("name", project.name)
                            .put("workspaceUri", project.workspaceUri)
                            .put("createdAt", project.createdAt)
                            .put("updatedAt", project.updatedAt)
                            .put("pinnedAt", project.pinnedAt)
                            .put("archivedAt", project.archivedAt),
                    )
                }
            })
            .put("conversations", JSONArray().also { array ->
                conversations(archived = null).forEach { conversation ->
                    array.put(
                        JSONObject()
                            .put("id", conversation.id)
                            .put("title", conversation.title)
                            .put("status", conversation.status)
                            .put("profileId", conversation.profileId)
                            .put("model", conversation.model)
                            .put("createdAt", conversation.createdAt)
                            .put("updatedAt", conversation.updatedAt)
                            .put("pinnedAt", conversation.pinnedAt)
                            .put("archivedAt", conversation.archivedAt)
                            .put("mode", conversation.mode)
                            .put("workspaceUri", conversation.workspaceUri)
                            .put("projectId", conversation.projectId)
                            .put("compressedContext", conversation.compressedContext)
                            .put("compressedMessageCount", messages(conversation.id).count { it.id <= conversation.compressedThroughMessageId })
                            .put("autoCompressionMode", conversation.autoCompressionMode)
                            .put("autoCompressionTurnThreshold", conversation.autoCompressionTurnThreshold)
                            .put("autoCompressionTokenThreshold", conversation.autoCompressionTokenThreshold)
                            .put("messages", JSONArray().also { messages ->
                                messages(conversation.id).forEach { message ->
                                    messages.put(
                                        JSONObject()
                                            .put("role", message.role)
                                            .put("content", messageContentForExport(message.content))
                                            .put("thinking", message.thinking)
                                            .put("profileId", message.profileId)
                                            .put("model", message.model)
                                            .put("toolCallId", message.toolCallId)
                                            .put("rawJson", message.rawJson)
                                            .put("tokensPerSecond", message.tokensPerSecond)
                                            .put("deepSeekCacheHitRate", message.deepSeekCacheHitRate)
                                            .put("createdAt", message.createdAt),
                                    )
                                }
                            }),
                    )
                }
            })
    }

    fun importJson(root: JSONObject, mode: String): String {
        val array = root.optJSONArray("conversations") ?: return appContext.getString(R.string.notice_no_compatible_data)
        if (mode == "replace") {
            writableDatabase.delete("usage_model_requests", null, null)
            writableDatabase.delete("usage_message_events", null, null)
            writableDatabase.delete("messages", null, null)
            writableDatabase.delete("conversations", null, null)
            writableDatabase.delete("projects", null, null)
        }
        val importedProjectIds = mutableMapOf<Long, Long>()
        val projectArray = root.optJSONArray("projects") ?: JSONArray()
        for (projectIndex in 0 until projectArray.length()) {
            val project = projectArray.optJSONObject(projectIndex) ?: continue
            val exportedId = project.optLong("id", 0L)
            val createdAt = project.optLong("createdAt", System.currentTimeMillis())
            val name = project.optString("name").ifBlank { appContext.getString(R.string.default_project_name) }
            val workspaceUri = project.optString("workspaceUri")
            val updatedAt = project.optLong("updatedAt", createdAt)
            val archivedAt = project.optLong("archivedAt", 0L)
            val importedId = if (mode != "replace") {
                importedProjectId(name, workspaceUri, createdAt, updatedAt, archivedAt)
            } else {
                null
            } ?: createImportedProject(
                name = name,
                workspaceUri = workspaceUri,
                createdAt = createdAt,
                updatedAt = updatedAt,
                pinnedAt = project.optLong("pinnedAt", 0L),
                archivedAt = archivedAt,
            )
            if (exportedId > 0L && importedId > 0L) importedProjectIds[exportedId] = importedId
        }
        var importedConversations = 0
        var importedMessages = 0
        var skippedConversations = 0
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val importedMode = item.optString("mode").ifBlank { MODE_NORMAL }
            if (importedMode == "roleplay") {
                skippedConversations++
                continue
            }
            val title = item.optString("title").ifBlank { appContext.getString(R.string.default_import_title) }
            val exportedCreatedAt = item.optLong("createdAt", System.currentTimeMillis())
            val exportedUpdatedAt = item.optLong("updatedAt", exportedCreatedAt)
            val messages = item.optJSONArray("messages") ?: JSONArray()
            val mappedProjectId = importedProjectIds[item.optLong("projectId", 0L)] ?: 0L
            if (mode != "replace" && importedConversationExists(
                    title = title,
                    profileId = item.optString("profileId"),
                    model = item.optString("model"),
                    createdAt = exportedCreatedAt,
                    updatedAt = exportedUpdatedAt,
                    mode = importedMode,
                    archivedAt = item.optLong("archivedAt", 0L),
                    projectId = mappedProjectId,
                    messages = messages,
                )
            ) {
                skippedConversations++
                continue
            }
            val conversationId = createImportedConversation(
                profileId = item.optString("profileId"),
                model = item.optString("model"),
                title = title,
                status = item.optString("status").ifBlank { STATUS_IDLE },
                createdAt = exportedCreatedAt,
                updatedAt = exportedUpdatedAt,
                pinnedAt = item.optLong("pinnedAt", 0L),
                archivedAt = item.optLong("archivedAt", 0L),
                mode = importedMode,
                workspaceUri = item.optString("workspaceUri"),
                compressedContext = item.optString("compressedContext"),
                autoCompressionMode = item.optString("autoCompressionMode").ifBlank { AUTO_COMPRESSION_OFF },
                autoCompressionTurnThreshold = item.optInt("autoCompressionTurnThreshold", DEFAULT_AUTO_COMPRESSION_TURNS),
                autoCompressionTokenThreshold = item.optLong("autoCompressionTokenThreshold", DEFAULT_AUTO_COMPRESSION_TOKENS),
                projectId = mappedProjectId,
            )
            for (messageIndex in 0 until messages.length()) {
                val message = messages.optJSONObject(messageIndex) ?: continue
                addImportedMessage(
                    conversationId = conversationId,
                    role = message.optString("role"),
                    content = message.optString("content"),
                    thinking = message.optString("thinking"),
                    profileId = message.optString("profileId"),
                    model = message.optString("model"),
                    toolCallId = message.optString("toolCallId").ifBlank { null },
                    rawJson = message.optString("rawJson").ifBlank { null },
                    tokensPerSecond = message.optDouble("tokensPerSecond", 0.0),
                    deepSeekCacheHitRate = message.optDouble("deepSeekCacheHitRate", -1.0).takeIf { it >= 0.0 },
                    createdAt = message.optLong("createdAt", exportedCreatedAt + messageIndex),
                )
                importedMessages++
            }
            val compressedCount = item.optInt("compressedMessageCount", 0).coerceAtLeast(0)
            if (item.optString("compressedContext").isNotBlank() && compressedCount > 0) {
                messages(conversationId).getOrNull(compressedCount - 1)?.let { through ->
                    setCompressedContext(conversationId, item.optString("compressedContext"), through.id)
                }
            }
            importedConversations++
        }
        return buildString {
            append(appContext.getString(R.string.import_result_conversations, importedConversations, importedMessages))
            if (skippedConversations > 0) append(appContext.getString(R.string.import_result_skipped, skippedConversations))
        }
    }

    private fun importedConversationExists(
        title: String,
        profileId: String,
        model: String,
        createdAt: Long,
        updatedAt: Long,
        mode: String,
        archivedAt: Long,
        projectId: Long,
        messages: JSONArray,
    ): Boolean {
        val exportedSignature = importedMessagesSignature(messages)
        return readableDatabase.query(
            "conversations",
            arrayOf("id"),
            "title=? AND profile_id=? AND model=? AND created_at=? AND updated_at=? AND mode=? AND archived_at=? AND project_id=?",
            arrayOf(
                title.take(120),
                profileId,
                model,
                createdAt.toString(),
                updatedAt.toString(),
                mode,
                archivedAt.toString(),
                projectId.toString(),
            ),
            null,
            null,
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                if (storedMessagesSignature(cursor.getLong(0)) == exportedSignature) return@use true
            }
            false
        }
    }

    private fun importedMessagesSignature(messages: JSONArray): String {
        return buildString {
            append(messages.length()).append('|')
            for (index in 0 until messages.length()) {
                val message = messages.optJSONObject(index) ?: continue
                append(message.optString("role")).append('\u001F')
                append(message.optString("content")).append('\u001F')
                append(message.optString("thinking")).append('\u001F')
                append(message.optString("profileId")).append('\u001F')
                append(message.optString("model")).append('\u001F')
                append(message.optString("toolCallId")).append('\u001F')
                append(message.optString("rawJson")).append('\u001F')
                append(message.optLong("createdAt")).append('\u001E')
            }
        }
    }

    private fun storedMessagesSignature(conversationId: Long): String {
        return buildString {
            val rows = messages(conversationId)
            append(rows.size).append('|')
            rows.forEach { message ->
                append(message.role).append('\u001F')
                append(messageContentForExport(message.content)).append('\u001F')
                append(message.thinking).append('\u001F')
                append(message.profileId).append('\u001F')
                append(message.model).append('\u001F')
                append(message.toolCallId.orEmpty()).append('\u001F')
                append(message.rawJson.orEmpty()).append('\u001F')
                append(message.createdAt).append('\u001E')
            }
        }
    }

    fun openAiMessages(conversationId: Long, excludeMessageId: Long? = null, maxMessages: Int = 40): JSONArray {
        val source = messages(conversationId).filter { it.id != excludeMessageId }
        val groups = mutableListOf<List<JSONObject>>()
        var index = 0
        while (index < source.size) {
            val message = source[index]
            if (message.role == "tool") {
                index++
                continue
            }

            val json = message.toOpenAiJson()
            if (json.hasToolCalls()) {
                val requiredToolIds = json.toolCallIds()
                val toolMessages = mutableListOf<JSONObject>()
                var next = index + 1
                while (next < source.size && source[next].role == "tool") {
                    val tool = source[next]
                    val toolCallId = tool.toolCallId.orEmpty()
                    if (toolCallId in requiredToolIds) {
                        toolMessages.add(tool.toToolJson())
                    }
                    next++
                }
                val returnedToolIds = toolMessages.map { it.optString("tool_call_id") }.toSet()
                if (requiredToolIds.isNotEmpty() && returnedToolIds.containsAll(requiredToolIds)) {
                    groups.add(listOf(json) + toolMessages)
                }
                index = next
            } else {
                groups.add(listOf(json))
                index++
            }
        }
        return groups.takeLastMessages(maxMessages)
    }

    private fun ChatMessage.toOpenAiJson(): JSONObject {
        val raw = rawJson?.takeIf { it.isNotBlank() }?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?.also { sanitizeAssistantRaw(it) }
        return raw ?: JSONObject()
            .put("role", role)
            .put("content", if (role == "assistant") cleanGeneratedText(content) else content)
            .apply {
                if (role == "assistant" && thinking.isNotBlank()) {
                    put("reasoning_content", cleanGeneratedText(thinking))
                }
            }
    }

    private fun ChatMessage.toToolJson(): JSONObject = JSONObject()
        .put("role", "tool")
        .put("tool_call_id", toolCallId)
        .put("content", content)

    private fun JSONObject.hasToolCalls(): Boolean {
        if (optString("role") != "assistant") return false
        return (optJSONArray("tool_calls")?.length() ?: 0) > 0
    }

    private fun JSONObject.toolCallIds(): Set<String> {
        val calls = optJSONArray("tool_calls") ?: return emptySet()
        return buildSet {
            for (index in 0 until calls.length()) {
                calls.optJSONObject(index)?.optString("id").orEmpty().takeIf { it.isNotBlank() }?.let { add(it) }
            }
        }
    }

    private fun List<List<JSONObject>>.takeLastMessages(maxMessages: Int): JSONArray {
        val selected = ArrayDeque<List<JSONObject>>()
        var count = 0
        asReversed().forEach { group ->
            if (selected.isNotEmpty() && count + group.size > maxMessages) return@forEach
            selected.addFirst(group)
            count += group.size
        }
        return JSONArray().apply {
            selected.forEach { group -> group.forEach { put(it) } }
        }
    }

    private fun sanitizeAssistantRaw(raw: JSONObject) {
        if (raw.optString("role") != "assistant") return
        if (raw.has("content") && !raw.isNull("content")) {
            raw.put("content", cleanGeneratedText(raw.optString("content")))
        }
        if (raw.has("reasoning_content") && !raw.isNull("reasoning_content")) {
            raw.put("reasoning_content", cleanGeneratedText(raw.optString("reasoning_content")))
        }
    }

    private fun cleanGeneratedText(text: String): String {
        return text.replace(Regex("(?:null){4,}", RegexOption.IGNORE_CASE), "").trim()
    }

    companion object {
        const val AUTO_COMPRESSION_OFF = "off"
        const val AUTO_COMPRESSION_TURNS = "turns"
        const val AUTO_COMPRESSION_TOKENS = "tokens"
        const val DEFAULT_AUTO_COMPRESSION_TURNS = 20
        const val DEFAULT_AUTO_COMPRESSION_TOKENS = 131_072L
        private const val MAX_STORED_ATTACHMENT_BYTES = 8 * 1024 * 1024
        private const val MESSAGE_CONTENT_CHUNK_CHARS = 256 * 1024
        private const val MAX_CURSOR_MESSAGE_CONTENT_CHARS = 128 * 1024
        val AUTO_COMPRESSION_MODES = setOf(AUTO_COMPRESSION_OFF, AUTO_COMPRESSION_TURNS, AUTO_COMPRESSION_TOKENS)
        private val CONVERSATION_COLUMNS = arrayOf(
            "id", "title", "status", "profile_id", "model", "created_at", "updated_at",
            "pinned_at", "archived_at", "mode", "workspace_uri", "compressed_context",
            "compressed_through_message_id", "auto_compression_mode",
            "auto_compression_turn_threshold", "auto_compression_token_threshold", "project_id",
        )
        private val PROJECT_COLUMNS = arrayOf(
            "id", "name", "workspace_uri", "created_at", "updated_at", "pinned_at", "archived_at",
        )
        const val STATUS_IDLE = "idle"
        const val STATUS_RUNNING = "running"
        const val STATUS_INTERRUPTED = "interrupted"
        const val MODE_NORMAL = "normal"
        const val MODE_SUBAGENT = "subagent"
        const val MODE_TASK = "task"
    }
}
