package com.yukisoffd.lyracode.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase

// V4 BPE and added-token rules invalidate V3 greedy estimates, including historical message counts.
internal const val LOCAL_TOKEN_CACHE_VERSION = 2

/** Only schema changes run during upgrade. Historical tokenization is deferred to background reads. */
internal fun createUsageTokenIndex(db: SQLiteDatabase) {
    db.execSQL("ALTER TABLE messages ADD COLUMN token_revision INTEGER NOT NULL DEFAULT 0")
    db.execSQL("ALTER TABLE messages ADD COLUMN token_version INTEGER NOT NULL DEFAULT 0")
    db.execSQL("ALTER TABLE messages ADD COLUMN prompt_tokens INTEGER NOT NULL DEFAULT 0")
    db.execSQL("ALTER TABLE messages ADD COLUMN output_tokens INTEGER NOT NULL DEFAULT 0")
    db.execSQL("""CREATE INDEX idx_messages_token_metadata ON messages
        (conversation_id, id, role, created_at, model, token_version, prompt_tokens, output_tokens)""")
    db.execSQL("CREATE INDEX idx_messages_usage_period ON messages(created_at, role, conversation_id)")
    db.execSQL("""
        CREATE TABLE usage_token_index_state (
            conversation_id INTEGER PRIMARY KEY,
            revision INTEGER NOT NULL DEFAULT 0,
            indexed_revision INTEGER NOT NULL DEFAULT -1,
            dirty_from_id INTEGER NOT NULL DEFAULT 0,
            token_version INTEGER NOT NULL DEFAULT 0
        )
    """.trimIndent())
    db.execSQL("INSERT INTO usage_token_index_state(conversation_id) SELECT id FROM conversations")
    db.execSQL("""
        CREATE TABLE usage_estimated_requests (
            assistant_message_id INTEGER PRIMARY KEY,
            conversation_id INTEGER NOT NULL,
            created_at INTEGER NOT NULL,
            model TEXT NOT NULL,
            input_tokens INTEGER NOT NULL,
            output_tokens INTEGER NOT NULL
        )
    """.trimIndent())
    db.execSQL("CREATE INDEX idx_usage_estimates_period ON usage_estimated_requests(created_at)")
    db.execSQL("CREATE INDEX idx_usage_estimates_conversation ON usage_estimated_requests(conversation_id)")
    // SQL triggers cover import, branch, truncation, attachment repair and all direct message writes.
    for ((event, ref) in listOf("INSERT" to "NEW", "DELETE" to "OLD")) {
        db.execSQL("""
            CREATE TRIGGER usage_tokens_after_${event.lowercase()} AFTER $event ON messages BEGIN
                INSERT OR IGNORE INTO usage_token_index_state(conversation_id) VALUES ($ref.conversation_id);
                UPDATE usage_token_index_state SET
                    dirty_from_id=CASE WHEN indexed_revision<0 THEN 0 WHEN revision=indexed_revision
                        THEN $ref.id ELSE MIN(dirty_from_id,$ref.id) END,
                    revision=revision+1 WHERE conversation_id=$ref.conversation_id;
                ${if (event == "DELETE") "DELETE FROM usage_estimated_requests WHERE assistant_message_id=OLD.id;" else ""}
            END
        """.trimIndent())
    }
    db.execSQL("""
        CREATE TRIGGER usage_tokens_after_update AFTER UPDATE OF content, thinking, raw_json, role, model ON messages
        WHEN OLD.content IS NOT NEW.content OR OLD.thinking IS NOT NEW.thinking OR OLD.raw_json IS NOT NEW.raw_json
            OR OLD.role IS NOT NEW.role OR OLD.model IS NOT NEW.model
        BEGIN
            UPDATE messages SET token_revision=OLD.token_revision+1, token_version=0 WHERE id=NEW.id;
            DELETE FROM usage_estimated_requests WHERE assistant_message_id=NEW.id;
            INSERT OR IGNORE INTO usage_token_index_state(conversation_id) VALUES (NEW.conversation_id);
            UPDATE usage_token_index_state SET
                dirty_from_id=CASE WHEN indexed_revision<0 THEN 0 WHEN revision=indexed_revision
                    THEN NEW.id ELSE MIN(dirty_from_id,NEW.id) END,
                revision=revision+1 WHERE conversation_id=NEW.conversation_id;
        END
    """.trimIndent())
    db.execSQL("""
        CREATE TRIGGER usage_tokens_after_conversation_delete AFTER DELETE ON conversations BEGIN
            DELETE FROM usage_token_index_state WHERE conversation_id=OLD.id;
            DELETE FROM usage_estimated_requests WHERE conversation_id=OLD.id;
        END
    """.trimIndent())
}

/** Compare revisions when publishing: a streaming edit must not receive an older snapshot's count. */
internal fun ConversationStore.messageTokenCosts(message: ChatMessage, tokenizer: DeepSeekV4Tokenizer): MessageTokenCosts {
    val version = tokenizer.cacheVersion
    if (message.tokenRevision >= 0L) {
        if (message.tokenCacheVersion == version) message.cachedTokenCosts?.let { return it }
        readableDatabase.rawQuery(
            "SELECT prompt_tokens, output_tokens FROM messages WHERE id=? AND token_revision=? AND token_version=?",
            arrayOf(message.id.toString(), message.tokenRevision.toString(), version.toString()),
        ).use { cursor ->
            if (cursor.moveToFirst()) return MessageTokenCosts(cursor.getLong(0), cursor.getLong(1))
        }
    }
    val costs = message.calculateTokenCosts(tokenizer::count)
    if (message.tokenRevision >= 0L) {
        writableDatabase.update("messages", ContentValues().apply {
            put("token_version", version)
            put("prompt_tokens", costs.prompt)
            put("output_tokens", costs.output)
        }, "id=? AND token_revision=?", arrayOf(message.id.toString(), message.tokenRevision.toString()))
    }
    return costs
}

/** Warm reads never open message bodies or initialize the tokenizer. Work is scoped to legacy requests in range. */
internal fun ConversationStore.prepareLegacyUsage(
    startAt: Long,
    endAt: Long,
    tokenizer: () -> DeepSeekV4Tokenizer,
    checkCancelled: () -> Unit,
) = synchronized(usageTokenIndexLock) {
    checkCancelled()
    val candidates = readableDatabase.rawQuery("""
        SELECT DISTINCT m.conversation_id FROM messages m
        JOIN usage_token_index_state s ON s.conversation_id=m.conversation_id
        LEFT JOIN usage_model_requests r ON r.assistant_message_id=m.id
        WHERE m.created_at>=? AND m.created_at<? AND lower(m.role)='assistant'
          AND r.assistant_message_id IS NULL
          AND (s.revision<>s.indexed_revision OR s.token_version<>?)
    """.trimIndent(), arrayOf(startAt.toString(), endAt.toString(), LOCAL_TOKEN_CACHE_VERSION.toString())).use { cursor ->
        buildList { while (cursor.moveToNext()) add(cursor.getLong(0)) }
    }
    for (conversationId in candidates) {
        checkCancelled()
        val counter = tokenizer()
        while (!rebuildLegacyUsage(conversationId, counter, checkCancelled)) checkCancelled()
    }
}

private fun ConversationStore.rebuildLegacyUsage(
    conversationId: Long,
    tokenizer: DeepSeekV4Tokenizer,
    checkCancelled: () -> Unit,
): Boolean {
    val args = arrayOf(conversationId.toString())
    val (revision, fromId) = readableDatabase.rawQuery(
        "SELECT revision, dirty_from_id, token_version, indexed_revision FROM usage_token_index_state WHERE conversation_id=?", args,
    ).use { cursor ->
        if (!cursor.moveToFirst()) return true
        cursor.getLong(0) to if (cursor.getInt(2) == tokenizer.cacheVersion && cursor.getLong(3) >= 0L) cursor.getLong(1) else 0L
    }
    val maxId = readableDatabase.rawQuery("SELECT COALESCE(MAX(id),0) FROM messages WHERE conversation_id=?", args)
        .use { cursor -> cursor.moveToFirst(); cursor.getLong(0) }
    var afterId = (fromId - 1).coerceAtLeast(0L)
    // Keyset pagination bounds allocations; each field is read through ConversationStore's CursorWindow-safe reader.
    while (true) {
        checkCancelled()
        val ids = readableDatabase.rawQuery("""
            SELECT id FROM messages WHERE conversation_id=? AND id>? AND id<=? AND token_version<>?
              AND lower(role) IN ('user','tool','assistant') ORDER BY id LIMIT 64
        """.trimIndent(), arrayOf(conversationId.toString(), afterId.toString(), maxId.toString(), tokenizer.cacheVersion.toString()))
            .use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getLong(0)) } }
        if (ids.isEmpty()) break
        // Do expensive text work outside the write transaction, then publish a page in one commit.
        data class PendingCosts(val id: Long, val revision: Long, val costs: MessageTokenCosts)
        val pending = ArrayList<PendingCosts>(ids.size)
        for (id in ids) {
            checkCancelled()
            message(id)?.let { pending += PendingCosts(it.id, it.tokenRevision, it.calculateTokenCosts(tokenizer::count)) }
        }
        val cacheDb = writableDatabase
        cacheDb.beginTransaction()
        try {
            cacheDb.compileStatement("""UPDATE messages SET token_version=?, prompt_tokens=?, output_tokens=?
                WHERE id=? AND token_revision=?""").use { update ->
                for ((id, messageRevision, costs) in pending) {
                    update.bindLong(1, tokenizer.cacheVersion.toLong())
                    update.bindLong(2, costs.prompt)
                    update.bindLong(3, costs.output)
                    update.bindLong(4, id)
                    update.bindLong(5, messageRevision)
                    update.executeUpdateDelete()
                }
            }
            cacheDb.setTransactionSuccessful()
        } finally {
            cacheDb.endTransaction()
        }
        afterId = ids.last()
    }
    checkCancelled()
    val db = writableDatabase
    db.beginTransaction()
    try {
        val unchanged = db.rawQuery("SELECT revision FROM usage_token_index_state WHERE conversation_id=?", args)
            .use { cursor -> cursor.moveToFirst() && cursor.getLong(0) == revision }
        if (!unchanged) return false
        db.delete("usage_estimated_requests", "conversation_id=? AND assistant_message_id>=?", arrayOf(conversationId.toString(), fromId.toString()))
        db.compileStatement("""
            INSERT INTO usage_estimated_requests
            (assistant_message_id, conversation_id, created_at, model, input_tokens, output_tokens) VALUES (?,?,?,?,?,?)
        """.trimIndent()).use { insert ->
            db.rawQuery("""
                SELECT id, role, created_at, model, prompt_tokens, output_tokens FROM messages
                WHERE conversation_id=? AND id>=? AND lower(role) IN ('user','tool','assistant') ORDER BY id
            """.trimIndent(), arrayOf(conversationId.toString(), fromId.toString())).use { cursor ->
                var contextTokens = db.rawQuery("""SELECT COALESCE(SUM(prompt_tokens),0) FROM messages
                    WHERE conversation_id=? AND id<? AND lower(role) IN ('user','tool','assistant')""",
                    arrayOf(conversationId.toString(), fromId.toString())).use { prefix -> prefix.moveToFirst(); prefix.getLong(0) }
                while (cursor.moveToNext()) {
                    checkCancelled()
                    if (cursor.getString(1).equals("assistant", ignoreCase = true)) {
                        insert.bindLong(1, cursor.getLong(0))
                        insert.bindLong(2, conversationId)
                        insert.bindLong(3, cursor.getLong(2))
                        insert.bindString(4, cursor.getString(3))
                        insert.bindLong(5, 1024L + contextTokens)
                        insert.bindLong(6, cursor.getLong(5))
                        insert.executeInsert()
                    }
                    contextTokens += cursor.getLong(4)
                }
            }
        }
        db.execSQL("UPDATE usage_token_index_state SET indexed_revision=revision, token_version=? WHERE conversation_id=?",
            arrayOf<Any>(tokenizer.cacheVersion, conversationId))
        db.setTransactionSuccessful()
        return true
    } finally {
        db.endTransaction()
    }
}

internal data class UsageEventCounts(val conversations: Int, val users: Int, val assistants: Int, val tools: Int)

internal fun ConversationStore.usageEventCounts(startAt: Long, endAt: Long): UsageEventCounts =
    readableDatabase.rawQuery("""
        SELECT COUNT(DISTINCT CASE WHEN lower(role)='user' THEN conversation_id END),
            COALESCE(SUM(CASE WHEN lower(role)='user' THEN 1 ELSE 0 END),0),
            COALESCE(SUM(CASE WHEN lower(role)='assistant' THEN 1 ELSE 0 END),0),
            COALESCE(SUM(CASE WHEN lower(role)='tool' THEN 1 ELSE 0 END),0)
        FROM usage_message_events WHERE created_at>=? AND created_at<?
    """.trimIndent(), arrayOf(startAt.toString(), endAt.toString())).use { cursor ->
        cursor.moveToFirst()
        UsageEventCounts(cursor.getInt(0), cursor.getInt(1), cursor.getInt(2), cursor.getInt(3))
    }

internal fun ConversationStore.usageTokenSamples(startAt: Long, endAt: Long): List<ModelUsageSample> =
    readableDatabase.rawQuery("""
        SELECT model, COUNT(*), SUM(input_tokens), SUM(output_tokens) FROM (
            SELECT COALESCE(m.model,'') AS model, r.input_tokens, r.output_tokens
            FROM usage_model_requests r LEFT JOIN messages m ON m.id=r.assistant_message_id
            WHERE r.created_at>=? AND r.created_at<?
            UNION ALL
            SELECT e.model, e.input_tokens, e.output_tokens FROM usage_estimated_requests e
            LEFT JOIN usage_model_requests r ON r.assistant_message_id=e.assistant_message_id
            WHERE e.created_at>=? AND e.created_at<? AND r.assistant_message_id IS NULL
        ) GROUP BY model
    """.trimIndent(), arrayOf(startAt.toString(), endAt.toString(), startAt.toString(), endAt.toString())).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(ModelUsageSample(
                modelName = cursor.getString(0), requestCount = cursor.getInt(1),
                inputTokens = cursor.getLong(2), outputTokens = cursor.getLong(3),
            ))
        }
    }
