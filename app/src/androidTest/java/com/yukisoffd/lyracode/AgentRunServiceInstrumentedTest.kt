package com.yukisoffd.lyracode

import android.app.NotificationManager
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yukisoffd.lyracode.ai.AgentRunForegroundService
import com.yukisoffd.lyracode.data.ConversationStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

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
        assumeTrue(InstrumentationRegistry.getArguments().getString("disposableTarget") == "true")
        val consent = context.getSharedPreferences("first_use_consent", 0)
        val hadConsent = consent.contains("accepted")
        val accepted = consent.getBoolean("accepted", false)
        var runtime: ChatRuntime? = null
        var conversation = 0L
        var job: Job? = null
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        try {
            check(FirstUseConsentStore(context).accept())
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val current = checkNotNull(activity.chatRuntime)
                    runtime = current
                    conversation = current.conversationStore.createConversation("test", "test", "Notification stop fixture")
                    current.conversationStore.setConversationMeta(conversation, status = ConversationStore.STATUS_RUNNING)
                    job = current.controller.launchTestConversationJob(conversation) {
                        started.complete(Unit)
                        release.await()
                    }
                }
                runBlocking { withTimeout(10_000) { started.await() } }
                awaitCondition("Foreground notification did not appear") { notification() != null }
                requireNotNull(notification()).notification.actions.single().actionIntent.send()
                awaitCondition("Stop did not cancel the controller run") { job?.isCancelled == true && job?.isCompleted == true }
                assertEquals(ConversationStore.STATUS_INTERRUPTED, runtime?.conversationStore?.conversation(conversation)?.status)
                awaitCondition("Stop left the notification running") { notification() == null }
            }
        } finally {
            release.complete(Unit)
            job?.cancel()
            runBlocking { withTimeout(10_000) { job?.join() } }
            if (conversation > 0) runtime?.conversationStore?.deleteConversation(conversation)
            val editor = consent.edit()
            if (hadConsent) editor.putBoolean("accepted", accepted) else editor.remove("accepted")
            check(editor.commit())
        }
    }
}
