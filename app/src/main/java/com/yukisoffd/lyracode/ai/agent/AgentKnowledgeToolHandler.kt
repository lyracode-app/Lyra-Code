package com.yukisoffd.lyracode.ai

import com.yukisoffd.lyracode.data.AppSettings
import com.yukisoffd.lyracode.data.ConversationStore
import com.yukisoffd.lyracode.data.MemoryEntry
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

internal class AgentKnowledgeToolHandler(
    private val settings: AppSettings,
    private val conversationStore: ConversationStore,
) {
    fun readMemories(args: JSONObject): String {
        val query = args.optString("query").trim()
        val includeDisabled = args.optBoolean("include_disabled", false)
        val entries = settings.memories().filter { memory ->
            (includeDisabled || memory.enabled) &&
                (query.isBlank() ||
                    memory.content.contains(query, ignoreCase = true) ||
                    memory.category.contains(query, ignoreCase = true))
        }
        return JSONObject()
            .put("schema", "lyra_user_memories_v1")
            .put("count", entries.size)
            .put("memories", JSONArray().also { array -> entries.forEach { array.put(memoryJson(it)) } })
            .toString()
    }
    
    fun saveMemory(args: JSONObject): String {
        val memory = settings.createMemory(
            content = args.getString("content"),
            category = args.optString("category", MemoryEntry.CATEGORY_OTHER),
        )
        return JSONObject()
            .put("schema", "lyra_user_memory_change_v1")
            .put("action", "saved")
            .put("memory", memoryJson(memory))
            .toString()
    }
    
    fun updateMemory(args: JSONObject): String {
        require(args.has("content") || args.has("category") || args.has("enabled")) {
            "update_memory requires at least one of content, category, or enabled."
        }
        val memory = settings.updateMemory(
            id = args.getString("id"),
            content = args.optString("content").takeIf { args.has("content") },
            category = args.optString("category").takeIf { args.has("category") },
            enabled = args.optBoolean("enabled").takeIf { args.has("enabled") },
        )
        return JSONObject()
            .put("schema", "lyra_user_memory_change_v1")
            .put("action", "updated")
            .put("memory", memoryJson(memory))
            .toString()
    }
    
    fun deleteMemory(args: JSONObject): String {
        val id = args.getString("id")
        require(settings.deleteMemory(id)) { "Memory does not exist: $id. Call read_memories and use a returned id." }
        return JSONObject()
            .put("schema", "lyra_user_memory_change_v1")
            .put("action", "deleted")
            .put("id", id)
            .toString()
    }
    
    fun memoryJson(memory: MemoryEntry): JSONObject = JSONObject()
        .put("id", memory.id)
        .put("content", memory.content)
        .put("category", memory.category)
        .put("enabled", memory.enabled)
        .put("created_at", memory.createdAt)
        .put("updated_at", memory.updatedAt)
    
    fun searchConversationHistory(args: JSONObject): String {
        val query = args.optString("query").trim()
        val start = args.optString("start_time").takeIf { it.isNotBlank() }?.let(::parseAgentTime) ?: Long.MIN_VALUE
        val end = args.optString("end_time").takeIf { it.isNotBlank() }?.let(::parseAgentTime) ?: Long.MAX_VALUE
        val limit = args.optInt("limit", 20).coerceIn(1, 100)
        val results = conversationStore.conversations(ConversationStore.MODE_NORMAL)
            .asSequence()
            .filter { it.updatedAt in start..end }
            .map { conversation ->
                val visible = conversationStore.messages(conversation.id)
                    .filter { it.role == "user" || it.role == "assistant" }
                    .filter { it.content.isNotBlank() }
                conversation to visible
            }
            .filter { (conversation, messages) ->
                query.isBlank() ||
                    conversation.title.contains(query, ignoreCase = true) ||
                    messages.any { it.content.contains(query, ignoreCase = true) }
            }
            .take(limit)
            .toList()
        return JSONObject()
            .put("schema", "lyra_conversation_search_v1")
            .put("thinking_included", false)
            .put("tool_messages_included", false)
            .put(
                "conversations",
                JSONArray().also { array ->
                    results.forEach { (conversation, messages) ->
                        array.put(
                            JSONObject()
                                .put("id", conversation.id.toString())
                                .put("title", conversation.title)
                                .put("created_at", conversation.createdAt)
                                .put("updated_at", conversation.updatedAt)
                                .put("message_count", messages.size)
                                .put(
                                    "preview",
                                    messages.asReversed().firstOrNull()?.content.orEmpty()
                                        .replace(Regex("\\s+"), " ")
                                        .take(240),
                                ),
                        )
                    }
                },
            )
            .toString()
    }
    
    fun readConversationHistory(args: JSONObject): String {
        val ids = buildList {
            args.optJSONArray("conversation_ids")?.let { array ->
                for (index in 0 until array.length()) {
                    array.optString(index).toLongOrNull()?.let(::add)
                }
            }
            args.optString("conversation_id").toLongOrNull()?.let(::add)
        }.distinct().take(20)
        require(ids.isNotEmpty()) { "Provide conversation_id or a non-empty conversation_ids array." }
        val maxMessages = args.optInt("max_messages", 100).coerceIn(1, 500)
        val conversations = JSONArray()
        ids.forEach { id ->
            val conversation = conversationStore.conversation(id)
            if (conversation == null || conversation.mode != ConversationStore.MODE_NORMAL) return@forEach
            val visible = conversationStore.messages(id)
                .filter { it.role == "user" || it.role == "assistant" }
                .filter { it.content.isNotBlank() }
                .takeLast(maxMessages)
            conversations.put(
                JSONObject()
                    .put("id", id.toString())
                    .put("title", conversation.title)
                    .put("created_at", conversation.createdAt)
                    .put("updated_at", conversation.updatedAt)
                    .put(
                        "messages",
                        JSONArray().also { array ->
                            visible.forEach { message ->
                                array.put(
                                    JSONObject()
                                        .put("role", message.role)
                                        .put("content", message.content)
                                        .put("created_at", message.createdAt),
                                )
                            }
                        },
                    ),
            )
        }
        return JSONObject()
            .put("schema", "lyra_conversation_history_v1")
            .put("thinking_included", false)
            .put("tool_messages_included", false)
            .put("conversations", conversations)
            .toString()
    }
    
    fun parseAgentTime(value: String): Long {
        value.toLongOrNull()?.let { return it }
        val zone = ZoneId.systemDefault()
        val dateTimeFormats = listOf(
            DateTimeFormatter.ISO_LOCAL_DATE_TIME,
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm"),
        )
        dateTimeFormats.forEach { formatter ->
            try {
                return LocalDateTime.parse(value.trim(), formatter).atZone(zone).toInstant().toEpochMilli()
            } catch (_: DateTimeParseException) {
            }
        }
        return try {
            LocalDate.parse(value.trim(), DateTimeFormatter.ISO_LOCAL_DATE)
                .atStartOfDay(zone)
                .toInstant()
                .toEpochMilli()
        } catch (_: DateTimeParseException) {
            error("Cannot parse time: $value. Use an epoch timestamp, yyyy-MM-dd, yyyy-MM-dd HH:mm, or ISO-8601.")
        }
    }
}

