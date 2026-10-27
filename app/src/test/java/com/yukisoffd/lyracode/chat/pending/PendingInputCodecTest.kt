package com.yukisoffd.lyracode.chat.pending

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingInputCodecTest {
    @Test
    fun queueRoundTripsThroughVersionedJson() {
        val first = PendingInputItem(
            id = "first",
            conversationId = 8L,
            capture = PendingInputCapture(
                rawText = "raw text",
                composedInput = "composed input\n\n<lyra_attachment_v1>{}</lyra_attachment_v1>",
                attachments = listOf(
                    PendingAttachment("photo.jpg", "image", "image/jpeg", "/uploads/photo.jpg", 2048),
                    PendingAttachment("notes.txt", "text", "text/plain", "/uploads/notes.txt"),
                ),
                forcedSkillIds = listOf("skill-a", "skill-b"),
            ),
            createdAt = 1234L,
            state = PendingInputState.QUEUED,
        )
        val second = PendingInputItem(
            id = "second",
            conversationId = 8L,
            capture = PendingInputCapture(rawText = "second raw", composedInput = "second composed"),
            createdAt = 5678L,
            state = PendingInputState.PARKED,
        )

        val encoded = encodePendingInputQueue(8L, listOf(first, second))
        val root = JSONObject(encoded)
        assertEquals(PENDING_INPUT_SCHEMA, root.getString("schema"))
        assertEquals(8L, root.getLong("conversationId"))
        assertFalse(root.toString().contains("\"state\""))
        assertFalse(root.has("steers"))

        val decoded = decodePendingInputQueue(8L, encoded)
        assertEquals(2, decoded.size)
        assertTrue(decoded.all { it.state == PendingInputState.PARKED })
        assertEquals(first.copy(state = PendingInputState.PARKED), decoded[0])
        assertEquals(second.copy(state = PendingInputState.PARKED), decoded[1])
    }

    @Test
    fun blankCorruptAndUnknownVersionDataIsDiscarded() {
        for (raw in listOf(null, "", "   ", "not json", "[]", "{}")) {
            assertTrue("raw=$raw", decodePendingInputQueue(8L, raw).isEmpty())
        }

        val valid = encodePendingInputQueue(8L, listOf(item("keep")))
        assertTrue(decodePendingInputQueue(8L, JSONObject(valid).put("schema", "lyra_pending_input_v2").toString()).isEmpty())
        assertTrue(decodePendingInputQueue(8L, JSONObject(valid).apply { remove("schema") }.toString()).isEmpty())
        assertTrue(decodePendingInputQueue(8L, JSONObject(valid).put("conversationId", 9L).toString()).isEmpty())
        assertTrue(decodePendingInputQueue(8L, JSONObject(valid).apply { remove("conversationId") }.toString()).isEmpty())
        assertTrue(decodePendingInputQueue(8L, JSONArray().put(JSONObject(valid)).toString()).isEmpty())
    }

    @Test
    fun missingItemsArrayIsAnEmptyQueue() {
        val document = JSONObject()
            .put("schema", PENDING_INPUT_SCHEMA)
            .put("conversationId", 8L)
        assertTrue(decodePendingInputQueue(8L, document.toString()).isEmpty())
    }

    @Test
    fun malformedAndDuplicateItemsAreSkipped() {
        val document = JSONObject()
            .put("schema", PENDING_INPUT_SCHEMA)
            .put("conversationId", 8L)
            .put(
                "items",
                JSONArray()
                    .put(
                        JSONObject()
                            .put("id", "keep")
                            .put("rawText", "r")
                            .put("composedInput", "c")
                            .put("createdAt", 5L),
                    )
                    .put(
                        JSONObject()
                            .put("id", "keep")
                            .put("rawText", "duplicate")
                            .put("composedInput", "duplicate"),
                    )
                    .put(JSONObject().put("rawText", "missing id").put("composedInput", "c"))
                    .put(JSONObject().put("id", " ").put("rawText", "r").put("composedInput", "c"))
                    .put("not an object")
                    .put(
                        JSONObject()
                            .put("id", "second")
                            .put("rawText", "r2")
                            .put("composedInput", "c2")
                            .put("attachments", "broken")
                            .put("forcedSkillIds", JSONObject()),
                    ),
            )

        val decoded = decodePendingInputQueue(8L, document.toString())
        assertEquals(listOf("keep", "second"), decoded.map { it.id })
        assertEquals("r", decoded[0].capture.rawText)
        assertEquals("c", decoded[0].capture.composedInput)
        assertEquals(5L, decoded[0].createdAt)
        assertTrue(decoded[0].capture.attachments.isEmpty())
        assertTrue(decoded[1].capture.attachments.isEmpty())
        assertTrue(decoded[1].capture.forcedSkillIds.isEmpty())
        assertTrue(decoded.all { it.state == PendingInputState.PARKED && it.conversationId == 8L })
    }

    @Test
    fun blankRawTextIsKeptWhenComposedInputExists() {
        val document = JSONObject()
            .put("schema", PENDING_INPUT_SCHEMA)
            .put("conversationId", 8L)
            .put(
                "items",
                JSONArray()
                    .put(
                        JSONObject()
                            .put("id", "attachment-only")
                            .put("rawText", "  ")
                            .put("composedInput", "<lyra_attachment_v1>{}</lyra_attachment_v1>"),
                    )
                    .put(JSONObject().put("id", "missing-raw").put("composedInput", "marker only")),
            )

        val decoded = decodePendingInputQueue(8L, document.toString())
        assertEquals(listOf("attachment-only", "missing-raw"), decoded.map { it.id })
        assertEquals("  ", decoded[0].capture.rawText)
        assertEquals("<lyra_attachment_v1>{}</lyra_attachment_v1>", decoded[0].capture.composedInput)
        assertEquals("", decoded[1].capture.rawText)
        assertEquals("marker only", decoded[1].capture.composedInput)
        assertTrue(decoded.all { it.state == PendingInputState.PARKED && it.conversationId == 8L })
    }

    @Test
    fun itemsWithoutComposedInputAreDiscarded() {
        val document = JSONObject()
            .put("schema", PENDING_INPUT_SCHEMA)
            .put("conversationId", 8L)
            .put(
                "items",
                JSONArray()
                    .put(JSONObject().put("id", "blank-composed").put("rawText", "r").put("composedInput", " "))
                    .put(JSONObject().put("id", "missing-composed").put("rawText", "r"))
                    .put(JSONObject().put("id", "missing-both"))
                    .put(JSONObject().put("id", "blank-both").put("rawText", "").put("composedInput", "")),
            )

        assertTrue(decodePendingInputQueue(8L, document.toString()).isEmpty())
    }

    @Test
    fun malformedAttachmentEntriesAreSkipped() {
        val document = JSONObject()
            .put("schema", PENDING_INPUT_SCHEMA)
            .put("conversationId", 8L)
            .put(
                "items",
                JSONArray().put(
                    JSONObject()
                        .put("id", "with-attachments")
                        .put("rawText", "look")
                        .put("composedInput", "look")
                        .put(
                            "attachments",
                            JSONArray()
                                .put("junk")
                                .put(
                                    JSONObject()
                                        .put("name", "photo.png")
                                        .put("kind", "image")
                                        .put("mimeType", "image/png")
                                        .put("uri", "/uploads/photo.png")
                                        .put("size", 12),
                                )
                                .put(JSONObject().put("uri", "/uploads/notes.txt")),
                        ),
                ),
            )

        val decoded = decodePendingInputQueue(8L, document.toString()).single()
        assertEquals(2, decoded.capture.attachments.size)
        assertEquals(
            PendingAttachment("photo.png", "image", "image/png", "/uploads/photo.png", 12),
            decoded.capture.attachments[0],
        )
        assertEquals(PendingAttachment("", "", "", "/uploads/notes.txt"), decoded.capture.attachments[1])
    }

    @Test
    fun forcedSkillIdsSkipBlankValuesAndKeepOrder() {
        val document = JSONObject()
            .put("schema", PENDING_INPUT_SCHEMA)
            .put("conversationId", 8L)
            .put(
                "items",
                JSONArray().put(
                    JSONObject()
                        .put("id", "skills")
                        .put("rawText", "use skills")
                        .put("composedInput", "use skills")
                        .put("forcedSkillIds", JSONArray().put("first").put("").put("second")),
                ),
            )

        assertEquals(
            listOf("first", "second"),
            decodePendingInputQueue(8L, document.toString()).single().capture.forcedSkillIds,
        )
    }

    private fun item(id: String, state: PendingInputState = PendingInputState.QUEUED) = PendingInputItem(
        id = id,
        conversationId = 8L,
        capture = PendingInputCapture(rawText = "raw", composedInput = "composed"),
        createdAt = 10L,
        state = state,
    )
}
