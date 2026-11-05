package com.yukisoffd.lyracode

import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yukisoffd.lyracode.data.ConversationStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatRuntimeRecreationTest {
    @Test
    fun recreatedActivityUsesSameRuntimeAndController() {
        // Startup can recover abandoned runs and mutate other app state. Run only against a
        // disposable installation, never an operator's existing app data.
        assumeTrue(InstrumentationRegistry.getArguments().getString("disposableTarget") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val consent = context.getSharedPreferences("first_use_consent", 0)
        val hadConsent = consent.contains("accepted")
        val accepted = consent.getBoolean("accepted", false)
        val settings = context.getSharedPreferences("lyra_settings", 0)
        val drafts = settings.all.filterKeys { it.startsWith("chat_input_draft_") }
        var runtime: ChatRuntime? = null
        var conversationId = -1L
        var job: Job? = null
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        try {
            check(FirstUseConsentStore(context).accept())
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var activity: MainActivity
                scenario.onActivity {
                    activity = it
                    val current = checkNotNull(it.chatRuntime)
                    runtime = current
                    assertTrue(current.isAttached(it))
                    conversationId = current.conversationStore.createConversation(
                        profileId = "instrumentation-fake", model = "local-fixture", title = "Runtime lifecycle fixture",
                    )
                    check(conversationId > 0)
                    current.conversationStore.setConversationMeta(conversationId, status = ConversationStore.STATUS_RUNNING)
                    job = current.controller.launchTestConversationJob(conversationId) {
                        started.complete(Unit)
                        release.await()
                    }
                }
                runBlocking { withTimeout(10_000) { started.await() } }
                val current = checkNotNull(runtime)
                val controller = current.controller
                assertTrue(checkNotNull(job).isActive)
                scenario.recreate()
                scenario.onActivity {
                    assertNotSame(activity, it)
                    assertSame(current, it.chatRuntime)
                    assertSame(controller, checkNotNull(it.chatRuntime).controller)
                    assertTrue(current.isAttached(it))
                    assertTrue(checkNotNull(job).isActive)
                    assertEquals(ConversationStore.STATUS_RUNNING, current.conversationStore.conversation(conversationId)?.status)
                }
            }
            assertTrue(checkNotNull(job).isActive)
            assertEquals(ConversationStore.STATUS_RUNNING, checkNotNull(runtime).conversationStore.conversation(conversationId)?.status)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity {
                    val current = checkNotNull(runtime)
                    assertSame(current, it.chatRuntime)
                    assertSame(current.controller, checkNotNull(it.chatRuntime).controller)
                    assertTrue(current.isAttached(it))
                    assertTrue(checkNotNull(job).isActive)
                    assertEquals(ConversationStore.STATUS_RUNNING, current.conversationStore.conversation(conversationId)?.status)
                }
            }
            release.complete(Unit)
            runBlocking { withTimeout(10_000) { checkNotNull(job).join() } }
            assertTrue(checkNotNull(job).isCompleted)
            assertFalse(checkNotNull(job).isCancelled)
        } finally {
            try {
                release.complete(Unit)
                job?.cancel()
                runBlocking { withTimeout(10_000) { job?.join() } }
            } finally {
                try {
                    if (conversationId > 0) runtime?.conversationStore?.deleteConversation(conversationId)
                } finally {
                    val editor = consent.edit()
                    if (hadConsent) editor.putBoolean("accepted", accepted) else editor.remove("accepted")
                    check(editor.commit())
                    val draftEditor = settings.edit()
                    settings.all.keys.filter { it.startsWith("chat_input_draft_") }.forEach { draftEditor.remove(it) }
                    drafts.forEach { (key, value) -> draftEditor.putString(key, value as String) }
                    check(draftEditor.commit())
                }
            }
        }
    }
}
