package com.yukisoffd.lyracode.chat.pending

import org.json.JSONArray
import org.json.JSONObject

internal const val PENDING_INPUT_SCHEMA = "lyra_pending_input_v1"

/** One versioned document per conversation. Item state is never stored; restore always parks. */
internal fun encodePendingInputQueue(conversationId: Long, items: List<PendingInputItem>): String {
    val encodedItems = JSONArray()
    items.forEach { item ->
        val encodedAttachments = JSONArray()
        item.capture.attachments.forEach { attachment ->
            encodedAttachments.put(
                JSONObject()
                    .put("name", attachment.name)
                    .put("kind", attachment.kind)
                    .put("mimeType", attachment.mimeType)
                    .put("uri", attachment.uri)
                    .put("size", attachment.size),
            )
        }
        val encodedSkills = JSONArray()
        item.capture.forcedSkillIds.forEach(encodedSkills::put)
        encodedItems.put(
            JSONObject()
                .put("id", item.id)
                .put("rawText", item.capture.rawText)
                .put("composedInput", item.capture.composedInput)
                .put("createdAt", item.createdAt)
                .put("attachments", encodedAttachments)
                .put("forcedSkillIds", encodedSkills),
        )
    }
    return JSONObject()
        .put("schema", PENDING_INPUT_SCHEMA)
        .put("conversationId", conversationId)
        .put("items", encodedItems)
        .toString()
}

/** Corrupt, unknown-version, or foreign-conversation data yields an empty queue; never throws. */
internal fun decodePendingInputQueue(conversationId: Long, raw: String?): List<PendingInputItem> {
    if (raw.isNullOrBlank()) return emptyList()
    return runCatching { decodeQueue(conversationId, raw) }.getOrDefault(emptyList())
}

private fun decodeQueue(conversationId: Long, raw: String): List<PendingInputItem> {
    val root = JSONObject(raw)
    if (root.optString("schema") != PENDING_INPUT_SCHEMA) return emptyList()
    val storedConversationId = root.opt("conversationId")
    if (storedConversationId !is Number || storedConversationId.toLong() != conversationId) return emptyList()
    val entries = root.optJSONArray("items") ?: return emptyList()
    val seenIds = mutableSetOf<String>()
    val items = ArrayList<PendingInputItem>(entries.length())
    for (index in 0 until entries.length()) {
        val entry = entries.optJSONObject(index) ?: continue
        val id = entry.optString("id")
        val rawText = entry.optString("rawText")
        val composedInput = entry.optString("composedInput")
        // Composed input is the deliverable. Raw text may be blank when the user queued only
        // attachments or workspace selections, so only missing composed input is malformed.
        if (id.isBlank() || composedInput.isBlank() || !seenIds.add(id)) continue
        items += PendingInputItem(
            id = id,
            conversationId = conversationId,
            capture = PendingInputCapture(
                rawText = rawText,
                composedInput = composedInput,
                attachments = decodeAttachments(entry.optJSONArray("attachments")),
                forcedSkillIds = decodeSkillIds(entry.optJSONArray("forcedSkillIds")),
            ),
            createdAt = entry.optLong("createdAt"),
            state = PendingInputState.PARKED,
        )
    }
    return items
}

private fun decodeAttachments(entries: JSONArray?): List<PendingAttachment> {
    if (entries == null) return emptyList()
    val attachments = ArrayList<PendingAttachment>(entries.length())
    for (index in 0 until entries.length()) {
        val entry = entries.optJSONObject(index) ?: continue
        attachments += PendingAttachment(
            name = entry.optString("name"),
            kind = entry.optString("kind"),
            mimeType = entry.optString("mimeType"),
            uri = entry.optString("uri"),
            size = entry.optInt("size"),
        )
    }
    return attachments
}

private fun decodeSkillIds(entries: JSONArray?): List<String> {
    if (entries == null) return emptyList()
    val values = ArrayList<String>(entries.length())
    for (index in 0 until entries.length()) {
        val value = entries.opt(index)
        if (value is String && value.isNotBlank()) values += value
    }
    return values
}
