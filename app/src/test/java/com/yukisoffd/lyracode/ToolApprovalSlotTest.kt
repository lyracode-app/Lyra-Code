package com.yukisoffd.lyracode

import com.yukisoffd.lyracode.ai.ToolApprovalDecision
import com.yukisoffd.lyracode.ai.ToolApprovalRequest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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

        slot.answer(pending.id, ToolApprovalDecision(approved = false, feedback = "no"))

        assertEquals(ToolApprovalDecision(approved = false, feedback = "no"), decision.await())
        assertNull(slot.current)
        assertEquals(listOf(pending, null), shown)
    }

    @Test
    fun rememberedApprovalAutoApprovesTheConversation() = runTest {
        val first = async { slot.request(request(1L)) }
        runCurrent()
        slot.answer(slot.current!!.id, ToolApprovalDecision(approved = true, rememberForConversation = true))
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

        slot.answer(pending.id, ToolApprovalDecision.Approved)
        assertEquals(ToolApprovalDecision.Approved, email.await())
    }

    @Test
    fun rememberedSendEmailApprovalDoesNotAutoApprove() = runTest {
        val email = async { slot.request(request(1L, "send_email")) }
        runCurrent()
        slot.answer(slot.current!!.id, ToolApprovalDecision(approved = true, rememberForConversation = true))
        email.await()

        assertFalse(1L in autoApproved)
    }

    @Test
    fun concurrentRequestsAreShownOneAtATimeInOrder() = runTest {
        val first = async { slot.request(request(1L)) }
        val second = async { slot.request(request(2L)) }
        runCurrent()

        assertEquals(1L, slot.current!!.request.conversationId)
        assertEquals(1, shown.size)
        assertFalse(second.isCompleted)

        val denied = ToolApprovalDecision(approved = false, feedback = "first")
        slot.answer(slot.current!!.id, denied)
        runCurrent()

        assertEquals(denied, first.await())
        assertEquals(2L, slot.current!!.request.conversationId)
        assertFalse(second.isCompleted)

        slot.answer(slot.current!!.id, ToolApprovalDecision.Approved)
        assertEquals(ToolApprovalDecision.Approved, second.await())
        assertNull(slot.current)
        assertEquals(listOf(1L, null, 2L, null), shown.map { it?.request?.conversationId })
    }

    @Test
    fun staleAnswerDoesNotAnswerTheNextRequest() = runTest {
        val first = async { slot.request(request(1L)) }
        val second = async { slot.request(request(2L)) }
        runCurrent()
        val firstShown = slot.current!!

        assertTrue(slot.answer(firstShown.id, ToolApprovalDecision.Approved))
        runCurrent()
        first.await()
        val secondShown = slot.current!!
        assertEquals(2L, secondShown.request.conversationId)

        // A fast second tap on the first dialog carries the first request's id.
        assertFalse(slot.answer(firstShown.id, ToolApprovalDecision.Approved))
        runCurrent()

        assertFalse(second.isCompleted)
        assertEquals(secondShown, slot.current)
        slot.answer(secondShown.id, ToolApprovalDecision(approved = false))
        assertFalse(second.await().approved)
    }

    @Test
    fun cancelledShownRequestClearsTheSlotAndFreesTheTurn() = runTest {
        val first = async { slot.request(request(1L)) }
        runCurrent()
        assertEquals(1L, slot.current!!.request.conversationId)

        first.cancel()
        runCurrent()

        assertNull(slot.current)
        assertEquals(listOf(1L, null), shown.map { it?.request?.conversationId })

        val second = async { slot.request(request(2L)) }
        runCurrent()
        val pending = slot.current
        assertNotNull(pending)
        assertEquals(2L, pending!!.request.conversationId)
        slot.answer(pending.id, ToolApprovalDecision.Approved)
        assertEquals(ToolApprovalDecision.Approved, second.await())
    }

    @Test
    fun cancelledWaitingRequestIsNeverShown() = runTest {
        val first = async { slot.request(request(1L)) }
        val second = async { slot.request(request(2L)) }
        val third = async { slot.request(request(3L)) }
        runCurrent()

        second.cancel()
        slot.answer(slot.current!!.id, ToolApprovalDecision.Approved)
        runCurrent()

        assertEquals(ToolApprovalDecision.Approved, first.await())
        assertEquals(3L, slot.current!!.request.conversationId)
        slot.answer(slot.current!!.id, ToolApprovalDecision.Approved)
        third.await()
        assertTrue(shown.none { it?.request?.conversationId == 2L })
    }

    @Test
    fun waitingRequestRechecksAutoApprovalWhenItsTurnComes() = runTest {
        val first = async { slot.request(request(1L)) }
        val second = async { slot.request(request(1L, "edit_file")) }
        runCurrent()

        slot.answer(slot.current!!.id, ToolApprovalDecision(approved = true, rememberForConversation = true))
        runCurrent()

        first.await()
        assertEquals(ToolApprovalDecision.Approved, second.await())
        assertNull(slot.current)
        assertEquals(2, shown.size)
    }

    @Test
    fun waitingRequestRechecksAutoApprovalSetDirectlyWhileItWaits() = runTest {
        val first = async { slot.request(request(1L)) }
        val second = async { slot.request(request(2L)) }
        runCurrent()

        // The way setAutoApprovalForActiveSession turns auto-approval on for a conversation.
        autoApproved += 2L
        slot.answer(slot.current!!.id, ToolApprovalDecision(approved = false, feedback = "first"))
        runCurrent()

        assertFalse(first.await().approved)
        assertEquals(ToolApprovalDecision.Approved, second.await())
        assertNull(slot.current)
        assertTrue(shown.none { it?.request?.conversationId == 2L })
    }

    @Test
    fun waitingSendEmailStillAsksAfterTheConversationIsAutoApproved() = runTest {
        val first = async { slot.request(request(1L)) }
        val email = async { slot.request(request(1L, "send_email")) }
        runCurrent()

        slot.answer(slot.current!!.id, ToolApprovalDecision(approved = true, rememberForConversation = true))
        runCurrent()

        first.await()
        assertEquals("send_email", slot.current!!.request.toolName)
        assertFalse(email.isCompleted)
        slot.answer(slot.current!!.id, ToolApprovalDecision(approved = false))
        assertFalse(email.await().approved)
    }
}
