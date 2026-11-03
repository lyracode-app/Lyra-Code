package com.yukisoffd.lyracode

import com.yukisoffd.lyracode.ai.ToolApprovalDecision
import com.yukisoffd.lyracode.ai.ToolApprovalRequest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ToolApprovalSlotTest {
    private val autoApproved = mutableSetOf<Long>()
    private val shown = mutableListOf<PendingToolApproval?>()
    private val slot = ToolApprovalSlot(autoApproved) { shown += it }

    private fun request(conversationId: Long, toolName: String = "write_file") =
        ToolApprovalRequest(conversationId, toolName, "{}", "summary", "risk")

    @Test
    fun answerCompletesTheShownRequestAndClearsTheSlot() = runTest {
        val decision = async { slot.request(request(1L)) }
        runCurrent()
        val pending = slot.current!!
        assertEquals(1L, pending.request.conversationId)

        slot.answer(pending, ToolApprovalDecision(approved = false, feedback = "no"))

        assertEquals(ToolApprovalDecision(approved = false, feedback = "no"), decision.await())
        assertNull(slot.current)
        assertEquals(listOf(pending, null), shown)
    }

    @Test
    fun rememberedApprovalAutoApprovesTheConversation() = runTest {
        val first = async { slot.request(request(1L)) }
        runCurrent()
        slot.answer(slot.current!!, ToolApprovalDecision(approved = true, rememberForConversation = true))
        first.await()

        assertEquals(ToolApprovalDecision.Approved, slot.request(request(1L)))
        assertTrue(1L in autoApproved)
        assertNull(slot.current)
    }

    @Test
    fun sendEmailAsksEvenInAnAutoApprovedConversation() = runTest {
        autoApproved += 1L
        val email = async { slot.request(request(1L, "send_email")) }
        runCurrent()
        val pending = slot.current!!
        assertEquals("send_email", pending.request.toolName)

        slot.answer(pending, ToolApprovalDecision.Approved)
        assertEquals(ToolApprovalDecision.Approved, email.await())
    }

    @Test
    fun rememberedSendEmailApprovalDoesNotAutoApprove() = runTest {
        val email = async { slot.request(request(1L, "send_email")) }
        runCurrent()
        slot.answer(slot.current!!, ToolApprovalDecision(approved = true, rememberForConversation = true))
        email.await()

        assertFalse(1L in autoApproved)
    }
}
