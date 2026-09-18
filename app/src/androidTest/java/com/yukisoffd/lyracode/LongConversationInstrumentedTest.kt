package com.yukisoffd.lyracode

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yukisoffd.lyracode.data.ConversationStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LongConversationInstrumentedTest {
    @Test
    fun historyReadsFieldsLargerThanCursorWindowWithoutTruncation() {
        ConversationStore(InstrumentationRegistry.getInstrumentation().targetContext, inMemory = true).use { store ->
            val id = store.createConversation(profileId = "test", model = "test")
            // Each field independently exceeds the usual 2 MiB CursorWindow.
            // Include supplementary Unicode across SQL substring boundaries.
            val text = "思考😀".repeat(300_000)
            val raw = "{\"content\":\"$text\"}"
            store.addMessage(id, "assistant", text, thinking = text, rawJson = raw)
            store.addMessage(id, "user", "next")

            val messages = store.messages(id)
            assertEquals(2, messages.size)
            assertEquals(text, messages[0].content)
            assertEquals(text, messages[0].thinking)
            assertEquals(raw, messages[0].rawJson)
            assertEquals("next", messages[1].content)
            assertEquals("", messages[1].thinking)
            assertNull(messages[1].rawJson)
        }
    }

    @Test
    fun contextCountsRespectConversationAndCompressionBoundary() {
        ConversationStore(InstrumentationRegistry.getInstrumentation().targetContext, inMemory = true).use { store ->
            val id = store.createConversation(profileId = "test", model = "test")
            val other = store.createConversation(profileId = "test", model = "test")
            assertEquals(0 to 0, store.contextMessageCounts(id, 0L))
            val boundary = store.addMessage(id, "user", "compressed")
            store.addMessage(id, "assistant", "summary")
            store.addMessage(id, "user", "new turn")
            store.addMessage(id, "tool", "result")
            store.addMessage(other, "user", "unrelated")

            assertEquals(4 to 2, store.contextMessageCounts(id, 0L))
            assertEquals(3 to 1, store.contextMessageCounts(id, boundary))
            assertEquals(0 to 0, store.contextMessageCounts(id, Long.MAX_VALUE))
        }
    }
}
