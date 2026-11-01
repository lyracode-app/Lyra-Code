package com.yukisoffd.lyracode

import android.database.sqlite.SQLiteDatabase
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yukisoffd.lyracode.data.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.system.measureNanoTime

@RunWith(AndroidJUnit4::class)
class UsageTokenIndexInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val tokenizer get() = DeepSeekV4Tokenizer.get(context)
    private fun ConversationStore.newConversation() = createConversation("test", "deepseek-chat", title = "token test")
    private fun ConversationStore.calculate() = UsageStatisticsRepository(context, this).calculate(UsageStatsPeriod.TOTAL)
    private fun ConversationStore.scalar(sql: String) = readableDatabase.rawQuery(sql, null).use { it.moveToFirst(); it.getLong(0) }

    @Test fun bundledTokenizerLoadsV4Rules() {
        // Surface parsing failures directly instead of letting the production fallback mask them.
        context.assets.open("deepseek-v4.bin").buffered().use(DeepSeekBpeEncoder::read)
        assertEquals(LOCAL_TOKEN_CACHE_VERSION, tokenizer.cacheVersion)
        assertEquals(3L, tokenizer.count("<｜begin▁of▁file｜><｜end▁of▁file｜>｜DSML｜"))
        assertEquals(4L, tokenizer.count("你好，世界！"))
        assertEquals(1L, tokenizer.count(" "))
        val fixture = InstrumentationRegistry.getInstrumentation().context.assets.open("deepseek-v4-counts.json")
            .bufferedReader().use { JSONObject(it.readText()) }.getJSONArray("cases")
        for (index in 0 until fixture.length()) {
            val case = fixture.getJSONObject(index)
            assertEquals("V4 reference case $index", case.getLong("count"), tokenizer.count(case.getString("text")))
        }
    }

    @Test fun oldV3CountsAreRebuiltAndRecordedRequestsArePreserved() {
        ConversationStore(context, inMemory = true).use { store ->
            val conversation = store.newConversation()
            store.addMessage(conversation, "user", "<｜begin▁of▁file｜>你好😀")
            store.addMessage(conversation, "assistant", "｜DSML｜result")
            val recorded = store.addMessage(conversation, "assistant", "recorded response")
            store.recordUsageModelRequest(recorded, 123, 45)
            store.calculate()
            // Simulate a fully indexed V3 history with stale estimates and no message edits.
            store.writableDatabase.execSQL("UPDATE messages SET token_version=1, prompt_tokens=999999, output_tokens=999999")
            store.writableDatabase.execSQL("UPDATE usage_token_index_state SET token_version=1")
            store.writableDatabase.execSQL("UPDATE usage_estimated_requests SET input_tokens=999999, output_tokens=999999")
            val result = store.calculate()
            assertEquals(store.referenceTokens(), result.userInputTokens to result.aiOutputTokens)
            assertEquals(0L, store.scalar("SELECT COUNT(*) FROM messages WHERE token_version<>$LOCAL_TOKEN_CACHE_VERSION"))
            assertEquals(123L, store.scalar("SELECT input_tokens FROM usage_model_requests WHERE assistant_message_id=$recorded"))
            assertEquals(45L, store.scalar("SELECT output_tokens FROM usage_model_requests WHERE assistant_message_id=$recorded"))
            store.prepareLegacyUsage(Long.MIN_VALUE, Long.MAX_VALUE, { error("Rebuilt history loaded tokenizer again") }, {})
        }
    }

    /** Independent version of the previous full-history calculation, including repeated context. */
    private fun ConversationStore.referenceTokens(): Pair<Long, Long> {
        val requests = usageModelRequests(Long.MIN_VALUE, Long.MAX_VALUE)
        val recordedIds = requests.map { it.assistantMessageId }.toSet()
        var input = requests.sumOf { it.inputTokens }
        var output = requests.sumOf { it.outputTokens }
        for (conversation in conversations(includeSubAgents = true, archived = null)) {
            var prefix = 0L
            for (message in messages(conversation.id)) {
                if (message.role.lowercase() !in setOf("user", "tool", "assistant")) continue
                val body = tokenizer.count(message.content)
                val assistantOutput = body + tokenizer.count(message.thinking) + tokenizer.count(toolCallsOutputText(message.rawJson))
                if (message.role == "assistant" && message.id !in recordedIds) {
                    input += 1024 + prefix
                    output += assistantOutput
                }
                prefix += 8 + if (message.role == "assistant") assistantOutput else body
            }
        }
        return input to output
    }

    @Test fun cacheMatchesLegacyTotalsAndWarmReadsNeverRequestTokenizer() {
        ConversationStore(context, inMemory = true).use { store ->
            repeat(3) { n ->
                val conversation = store.newConversation()
                repeat(5) { turn ->
                    store.addMessage(conversation, "user", "你好，request $turn", model = "deepseek-chat")
                    val assistant = store.addMessage(conversation, "assistant", "result $n/$turn", thinking = "reasoning",
                        model = if (turn % 2 == 0) "provider/deepseek-chat" else "DEEPSEEK-CHAT",
                        rawJson = """{"tool_calls":[{"function":{"name":"read_file","arguments":"{}"}}]}""")
                    store.addMessage(conversation, "tool", "tool result $turn")
                    if (turn == 3) store.recordUsageModelRequest(assistant, 123, 45)
                }
                if (n == 1) store.setArchived(conversation, true)
            }
            val expected = store.referenceTokens()
            val result = store.calculate()
            assertEquals(expected, result.userInputTokens to result.aiOutputTokens)
            assertEquals(15, result.modelRequestCount)
            assertEquals(15, result.userMessageCount)
            assertEquals(15, result.modelUsage.single().requestCount)
            store.prepareLegacyUsage(Long.MIN_VALUE, Long.MAX_VALUE, { error("Warm statistics loaded tokenizer") }, {})
            val samples = store.usageTokenSamples(Long.MIN_VALUE, Long.MAX_VALUE)
            assertEquals(expected, samples.sumOf { it.inputTokens } to samples.sumOf { it.outputTokens })
            assertEquals(result, store.calculate())
        }
    }

    @Test fun streamingEditsInvalidateCountsAndOldSnapshotsCannotPublish() {
        ConversationStore(context, inMemory = true).use { store ->
            val conversation = store.newConversation()
            val user = store.addMessage(conversation, "user", "initial question")
            store.addMessage(conversation, "assistant", "answer")
            val oldSnapshot = store.message(user)!!
            store.updateMessage(user, content = "edited question ".repeat(30))
            store.messageTokenCosts(oldSnapshot, tokenizer)
            assertEquals(0, store.message(user)!!.tokenCacheVersion)
            store.calculate()
            val cached = store.message(user)!!
            assertEquals(LOCAL_TOKEN_CACHE_VERSION, cached.tokenCacheVersion)
            store.updateMessage(user, content = cached.content, tokensPerSecond = 25.0)
            assertEquals(cached.tokenRevision, store.message(user)!!.tokenRevision)
            assertEquals(cached.cachedTokenCosts, store.message(user)!!.cachedTokenCosts)
            val synthetic = cached.copy(content = "compressed summary", tokenRevision = -1)
            assertEquals(synthetic.calculateTokenCosts(tokenizer::count), store.messageTokenCosts(synthetic, tokenizer))
            assertEquals(cached.cachedTokenCosts, store.message(user)!!.cachedTokenCosts)
            assertEquals(store.referenceTokens(), store.calculate().let { it.userInputTokens to it.aiOutputTokens })
        }
    }

    @Test fun appendEditTruncateBranchImportAndDeleteKeepEstimatesCurrent() {
        ConversationStore(context, inMemory = true).use { store ->
            val conversation = store.newConversation()
            val first = store.addMessage(conversation, "user", "first")
            val assistant = store.addMessage(conversation, "assistant", "answer")
            store.calculate()
            store.addMessage(conversation, "tool", "tool result")
            val latest = store.addMessage(conversation, "assistant", "next answer")
            assertEquals(store.referenceTokens(), store.calculate().let { it.userInputTokens to it.aiOutputTokens })
            assertEquals(4L, store.scalar("SELECT COUNT(*) FROM messages WHERE token_version=$LOCAL_TOKEN_CACHE_VERSION"))
            store.updateMessage(assistant, thinking = "new reasoning", rawJson = """{"tool_calls":[{"name":"test","input":{"a":1}}]}""")
            assertEquals(1L, store.scalar("SELECT COUNT(*) FROM messages WHERE token_version=0"))
            assertEquals(store.referenceTokens(), store.calculate().let { it.userInputTokens to it.aiOutputTokens })
            store.recordUsageModelRequest(latest, 10, 2)
            assertEquals(store.referenceTokens(), store.calculate().let { it.userInputTokens to it.aiOutputTokens })
            store.deleteMessagesAfter(conversation, assistant)
            assertEquals(store.referenceTokens(), store.calculate().let { it.userInputTokens to it.aiOutputTokens })
            store.createConversationBranch(conversation, assistant, "branch")
            assertEquals(store.referenceTokens(), store.calculate().let { it.userInputTokens to it.aiOutputTokens })
            val backup = store.exportJson()
            store.importJson(backup, "replace")
            assertEquals(store.referenceTokens(), store.calculate().let { it.userInputTokens to it.aiOutputTokens })
            store.conversations(includeSubAgents = true, archived = null).forEach { store.deleteConversation(it.id) }
            assertEquals(0L, store.scalar("SELECT COUNT(*) FROM usage_estimated_requests"))
            assertEquals(0L, store.scalar("SELECT COUNT(*) FROM usage_token_index_state"))
            assertEquals(0, store.calculate().modelRequestCount)
            assertTrue(first > 0)
        }
    }

    @Test fun recordedOnlyAndOutOfRangeHistoryDoNotTokenize() {
        ConversationStore(context, inMemory = true).use { store ->
            val recorded = store.newConversation()
            store.addMessage(recorded, "user", "large input".repeat(1000))
            val assistant = store.addMessage(recorded, "assistant", "answer")
            store.recordUsageModelRequest(assistant, 1000, 50)
            store.prepareLegacyUsage(Long.MIN_VALUE, Long.MAX_VALUE, { error("Recorded request was retokenized") }, {})
            assertEquals(0L, store.scalar("SELECT SUM(token_version) FROM messages"))
            val legacy = store.newConversation()
            store.addMessage(legacy, "user", "old input")
            store.addMessage(legacy, "assistant", "old output")
            store.prepareLegacyUsage(0, 1, { error("Unrelated date range tokenized history") }, {})
            assertEquals(0L, store.scalar("SELECT COUNT(*) FROM usage_estimated_requests"))
        }
    }

    @Test fun cancellationCanResumeWithoutPublishingPartialIndex() {
        ConversationStore(context, inMemory = true).use { store ->
            val conversation = store.newConversation()
            repeat(20) {
                store.addMessage(conversation, "user", "question $it")
                store.addMessage(conversation, "assistant", "answer $it")
            }
            var checks = 0
            assertThrows(CancellationException::class.java) {
                store.prepareLegacyUsage(Long.MIN_VALUE, Long.MAX_VALUE, { tokenizer }) {
                    if (++checks == 12) throw CancellationException()
                }
            }
            assertEquals(0L, store.scalar("SELECT COUNT(*) FROM usage_token_index_state WHERE revision=indexed_revision"))
            assertEquals(store.referenceTokens(), store.calculate().let { it.userInputTokens to it.aiOutputTokens })
        }
    }

    @Test fun dateRangeIncludesEarlierContextAndSkipsUnrelatedHistory() {
        ConversationStore(context, inMemory = true).use { store ->
            val conversation = store.newConversation()
            val oldUser = store.addMessage(conversation, "user", "earlier question")
            val oldAssistant = store.addMessage(conversation, "assistant", "earlier answer")
            val newUser = store.addMessage(conversation, "user", "today question")
            val newAssistant = store.addMessage(conversation, "assistant", "today answer")
            val unrelated = store.newConversation()
            val unrelatedUser = store.addMessage(unrelated, "user", "unrelated old question")
            val unrelatedAssistant = store.addMessage(unrelated, "assistant", "unrelated old answer")
            for (id in listOf(oldUser, oldAssistant, unrelatedUser, unrelatedAssistant)) {
                store.writableDatabase.execSQL("UPDATE messages SET created_at=0 WHERE id=?", arrayOf(id))
                store.writableDatabase.execSQL("UPDATE usage_message_events SET created_at=0 WHERE message_id=?", arrayOf(id))
            }
            val today = UsageStatisticsRepository(context, store).calculate(UsageStatsPeriod.DAY)
            val prefix = listOf(oldUser, oldAssistant, newUser).sumOf { store.message(it)!!.calculateTokenCosts(tokenizer::count).prompt }
            assertEquals(1024L + prefix, today.userInputTokens)
            assertEquals(store.message(newAssistant)!!.calculateTokenCosts(tokenizer::count).output, today.aiOutputTokens)
            assertEquals(1, today.modelRequestCount)
            assertEquals(1, today.userMessageCount)
            assertEquals(0L, store.scalar("SELECT SUM(token_version) FROM messages WHERE conversation_id=$unrelated"))
        }
    }

    @Test fun appendingOnlyRebuildsNewRequestEstimates() {
        ConversationStore(context, inMemory = true).use { store ->
            val conversation = store.newConversation()
            repeat(4) {
                store.addMessage(conversation, "user", "question $it")
                store.addMessage(conversation, "assistant", "answer $it")
            }
            store.calculate()
            store.writableDatabase.execSQL("CREATE TEMP TABLE rebuilt_estimates(message_id INTEGER)")
            store.writableDatabase.execSQL("""CREATE TEMP TRIGGER record_rebuilt_estimate AFTER INSERT ON usage_estimated_requests
                BEGIN INSERT INTO rebuilt_estimates VALUES (NEW.assistant_message_id); END""")
            store.addMessage(conversation, "user", "new question")
            val latest = store.addMessage(conversation, "assistant", "new answer")
            val result = store.calculate()
            assertEquals(1L, store.scalar("SELECT COUNT(*) FROM rebuilt_estimates"))
            assertEquals(latest, store.scalar("SELECT message_id FROM rebuilt_estimates"))
            assertEquals(store.referenceTokens(), result.userInputTokens to result.aiOutputTokens)
            val plan = store.readableDatabase.rawQuery("""EXPLAIN QUERY PLAN
                SELECT id,role,created_at,model,prompt_tokens,output_tokens FROM messages
                WHERE conversation_id=? AND id>=? ORDER BY id""", arrayOf(conversation.toString(), "0")).use {
                buildString { while (it.moveToNext()) append(it.getString(3)) }
            }
            assertTrue(plan, plan.contains("COVERING INDEX idx_messages_token_metadata"))
        }
    }

    @Test fun backgroundIndexAllowsConcurrentWritesAndRetriesChangedConversation() {
        ConversationStore(context, inMemory = true).use { store ->
            val conversation = store.newConversation()
            val user = store.addMessage(conversation, "user", "initial question")
            store.addMessage(conversation, "assistant", "answer")
            val writer = Executors.newSingleThreadExecutor()
            try {
                var checks = 0
                store.prepareLegacyUsage(Long.MIN_VALUE, Long.MAX_VALUE, { tokenizer }) {
                    if (++checks == 4) {
                        writer.submit { store.updateMessage(user, content = "concurrent edit ".repeat(50)) }
                            .get(5, TimeUnit.SECONDS)
                    }
                }
                assertTrue(checks > 4)
                assertEquals(store.referenceTokens(), store.calculate().let { it.userInputTokens to it.aiOutputTokens })
                assertEquals(1L, store.scalar("SELECT COUNT(*) FROM usage_token_index_state WHERE revision=indexed_revision"))
            } finally { writer.shutdownNow() }
        }
    }

    @Test fun versionElevenUpgradePreservesHistoryWithoutTokenization() {
        SQLiteDatabase.create(null).use { db ->
            db.execSQL("CREATE TABLE conversations(id INTEGER PRIMARY KEY)")
            db.execSQL("""CREATE TABLE messages(id INTEGER PRIMARY KEY, conversation_id INTEGER, created_at INTEGER,
                role TEXT, content TEXT, thinking TEXT, raw_json TEXT, model TEXT)""")
            db.execSQL("INSERT INTO conversations VALUES (1)")
            db.execSQL("INSERT INTO messages VALUES (1,1,10,'assistant','old content','',NULL,'model')")
            ConversationStore(context, inMemory = true).use { it.onUpgrade(db, 11, 12) }
            db.rawQuery("SELECT content, token_version, token_revision FROM messages", null).use {
                assertTrue(it.moveToFirst())
                assertEquals("old content", it.getString(0))
                assertEquals(0, it.getInt(1))
                assertEquals(0L, it.getLong(2))
            }
            db.rawQuery("SELECT COUNT(*) FROM usage_estimated_requests", null).use {
                it.moveToFirst(); assertEquals(0, it.getInt(0))
            }
        }
    }

    @Test fun benchmarkLargeHistoryColdWarmAndSingleMessageEdit() {
        ConversationStore(context, inMemory = true).use { store ->
            var editId = 0L
            store.writableDatabase.beginTransaction()
            try {
                repeat(8) { conversationIndex ->
                    val conversation = store.newConversation()
                    repeat(60) { turn ->
                        store.addMessage(conversation, "user", "$conversationIndex/$turn " + "检查历史会话 performance 和工具结果。".repeat(100))
                        editId = store.addMessage(conversation, "assistant", "$conversationIndex/$turn " + "fun main() { println(\"tokenizer\") }\n".repeat(100), model = "deepseek-chat")
                        store.addMessage(conversation, "tool", "$conversationIndex/$turn " + "工具输出 result line\n".repeat(100))
                    }
                }
                store.writableDatabase.setTransactionSuccessful()
            } finally { store.writableDatabase.endTransaction() }
            var expected: Pair<Long, Long>? = null
            val scanMs = measureNanoTime { expected = store.referenceTokens() } / 1_000_000.0
            var result: UsageStatsSummary? = null
            val coldMs = measureNanoTime { result = store.calculate() } / 1_000_000.0
            assertEquals(expected, result!!.userInputTokens to result!!.aiOutputTokens)
            val warmMs = measureNanoTime { repeat(5) { result = store.calculate() } } / 5_000_000.0
            val countBefore = store.scalar("SELECT COUNT(*) FROM messages WHERE token_version=$LOCAL_TOKEN_CACHE_VERSION")
            store.updateMessage(editId, content = "edited reply")
            assertEquals(countBefore - 1, store.scalar("SELECT COUNT(*) FROM messages WHERE token_version=$LOCAL_TOKEN_CACHE_VERSION"))
            val editMs = measureNanoTime { result = store.calculate() } / 1_000_000.0
            assertEquals(store.referenceTokens(), result!!.userInputTokens to result!!.aiOutputTokens)
            Log.i("TokenizerBenchmark", "messages=1440 fullScanMs=$scanMs coldIndexMs=$coldMs warmMs=$warmMs editMs=$editMs")
        }
    }
}
