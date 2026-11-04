package com.yukisoffd.lyracode

import android.app.NotificationManager
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yukisoffd.lyracode.ai.AgentRunForegroundService
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class AgentRunServiceInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun notification() = context.getSystemService(NotificationManager::class.java)
        .activeNotifications.firstOrNull { it.notification.channelId == "agent_runs" }

    private fun awaitCondition(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
        assertTrue(message, condition())
    }

    @Test fun notificationAndWakeLockLastUntilTheFinalRunEnds() {
        ActivityScenario.launch(MainActivity::class.java).use {
            var outstanding = 0
            try {
                main { repeat(2) { AgentRunForegroundService.start(context); outstanding++ } }
                awaitCondition("Foreground notification did not appear") { notification() != null }
                val power = instrumentation.uiAutomation.executeShellCommand("dumpsys power")
                    .use { descriptor -> android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() } }
                assertTrue("Agent wake lock is missing", power.contains("${context.packageName}:agentRun"))
                main { AgentRunForegroundService.finish(context); outstanding-- }
                SystemClock.sleep(250)
                assertNotNull(notification())
                main { AgentRunForegroundService.finish(context); outstanding-- }
                awaitCondition("Notification survived the final run") { notification() == null }
            } finally { main { repeat(outstanding) { AgentRunForegroundService.finish(context) } } }
        }
    }

    @Test fun notificationStopActionCallsTheRunOwnerAndRemovesNotification() {
        ActivityScenario.launch(MainActivity::class.java).use {
            val owner = Any()
            val stops = AtomicInteger()
            var outstanding = false
            try {
                main {
                    AgentRunForegroundService.bindStopRuns(owner) { stops.incrementAndGet() }
                    AgentRunForegroundService.start(context)
                    outstanding = true
                }
                awaitCondition("Foreground notification did not appear") { notification() != null }
                requireNotNull(notification()).notification.actions.single().actionIntent.send()
                awaitCondition("Stop did not reach the run owner") { stops.get() == 1 }
                awaitCondition("Stop left the notification running") { notification() == null }
            } finally {
                main {
                    AgentRunForegroundService.unbindStopRuns(owner)
                    if (outstanding) AgentRunForegroundService.finish(context)
                }
            }
        }
    }
}
