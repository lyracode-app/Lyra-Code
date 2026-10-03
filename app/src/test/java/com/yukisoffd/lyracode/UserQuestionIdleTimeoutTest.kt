package com.yukisoffd.lyracode

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UserQuestionIdleTimeoutTest {
    private val tenMinutes = 10L * 60L * 1000L

    @Test
    fun openQuestionExpiresAfterTenMinutesOfInactivity() = runTest {
        val expired = mutableListOf<Long>()
        val timeout = UserQuestionIdleTimeout(this, onTimeout = expired::add)
        timeout.reset(1L)
        advanceTimeBy(tenMinutes - 1L)
        assertTrue(expired.isEmpty())
        advanceTimeBy(1L)
        runCurrent()
        assertEquals(listOf(1L), expired)
    }

    @Test
    fun interactionStartsAnotherFullIdlePeriod() = runTest {
        val expired = mutableListOf<Long>()
        val timeout = UserQuestionIdleTimeout(this, onTimeout = expired::add)
        timeout.reset(1L)
        advanceTimeBy(tenMinutes / 2L)
        timeout.reset(1L)
        advanceTimeBy(tenMinutes - 1L)
        assertTrue(expired.isEmpty())
        advanceTimeBy(1L)
        runCurrent()
        assertEquals(listOf(1L), expired)
    }

    @Test
    fun minimizingPausesTimeoutAndIgnoresLateInteraction() = runTest {
        val expired = mutableListOf<Long>()
        val timeout = UserQuestionIdleTimeout(this, onTimeout = expired::add)
        timeout.reset(1L)
        advanceTimeBy(tenMinutes - 1L)
        timeout.pause()
        timeout.reset(1L)
        advanceTimeBy(tenMinutes * 12L)
        runCurrent()
        assertTrue(expired.isEmpty())
    }

    @Test
    fun reopeningStartsAnotherFullTenMinutes() = runTest {
        val expired = mutableListOf<Long>()
        val timeout = UserQuestionIdleTimeout(this, onTimeout = expired::add)
        timeout.reset(1L)
        advanceTimeBy(tenMinutes - 1L)
        timeout.pause()
        advanceTimeBy(tenMinutes * 12L)
        timeout.resume(1L)
        advanceTimeBy(tenMinutes - 1L)
        assertTrue(expired.isEmpty())
        advanceTimeBy(1L)
        runCurrent()
        assertEquals(listOf(1L), expired)
    }

    @Test
    fun cancellingMinimizedQuestionAllowsNextQuestionToExpire() = runTest {
        val expired = mutableListOf<Long>()
        val timeout = UserQuestionIdleTimeout(this, onTimeout = expired::add)
        timeout.reset(1L)
        timeout.pause()
        timeout.cancel()
        timeout.reset(2L)
        advanceTimeBy(tenMinutes)
        runCurrent()
        assertEquals(listOf(2L), expired)
    }

    @Test
    fun submissionOrInterruptionCancelsTimeout() = runTest {
        val expired = mutableListOf<Long>()
        val timeout = UserQuestionIdleTimeout(this, onTimeout = expired::add)
        timeout.reset(1L)
        advanceTimeBy(tenMinutes - 1L)
        timeout.cancel()
        advanceTimeBy(tenMinutes * 2L)
        runCurrent()
        assertTrue(expired.isEmpty())
    }
}
