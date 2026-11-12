package com.yukisoffd.lyracode

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yukisoffd.lyracode.data.ConversationStore
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PendingInputAttachmentReferenceInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun userMessageAttachmentUrisReadsOnlyUserMessagesIncludingChunkedContent() {
        ConversationStore(context, inMemory = true).use { store ->
            val conversation = store.createConversation("test", "test")
            val kept = "/uploads/keep.jpg"
            val longKept = "/uploads/long-keep.jpg"
            store.addMessage(conversation, "user", "look\n\n${marker(kept)}")
            store.addMessage(conversation, "user", "duplicate\n\n${marker(kept)}")
            // Non-user messages never authorize attachment retention.
            store.addMessage(conversation, "assistant", marker("/uploads/assistant.jpg"))
            store.addMessage(conversation, "tool", marker("/uploads/tool.jpg"))
            // The marker sits beyond the bounded-cursor window, forcing the chunked read.
            store.addMessage(conversation, "user", "x".repeat(140_000) + "\n\n${marker(longKept)}")
            store.addMessage(conversation, "user", "plain text without a marker")
            store.addMessage(conversation, "user", "malformed <lyra_attachment_v1>{not-json}</lyra_attachment_v1>")

            assertEquals(setOf(kept, longKept), store.userMessageAttachmentUris())
        }
    }

    @Test
    fun userMessageAttachmentUrisIsEmptyWithoutAttachmentMarkers() {
        ConversationStore(context, inMemory = true).use { store ->
            val conversation = store.createConversation("test", "test")
            store.addMessage(conversation, "user", "plain text")
            store.addMessage(conversation, "assistant", "plain answer")

            assertEquals(emptySet<String>(), store.userMessageAttachmentUris())
        }
    }

    private fun marker(uri: String): String =
        """<lyra_attachment_v1>{"name":"file","uri":"$uri"}</lyra_attachment_v1>"""
}
