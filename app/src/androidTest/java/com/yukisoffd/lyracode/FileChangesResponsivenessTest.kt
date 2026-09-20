package com.yukisoffd.lyracode

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.*
import androidx.test.platform.app.InstrumentationRegistry
import com.yukisoffd.lyracode.data.ConversationStore
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@OptIn(androidx.compose.ui.InternalComposeUiApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
class FileChangesResponsivenessTest {
    @Test fun openingHistoryWithSixteenFileChangesKeepsMainThreadResponsive() = exerciseChanges(1)
    @Test fun expandingLargeDiffAndSnapshotsKeepsMainThreadResponsive() = exerciseChanges(30_000)

    @Test fun addingFileChangesToAnOpenConversationKeepsMainThreadResponsive() = exerciseChanges(1, incoming = true)

    private fun exerciseChanges(lineCount: Int, incoming: Boolean = false) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        FirstUseConsentStore(instrumentation.targetContext).accept()
        instrumentation.targetContext.getSharedPreferences("android_compatibility", 0).edit()
            .putBoolean("android_17_local_network_rationale_seen", true).commit()
        val store = ConversationStore(instrumentation.targetContext)
        val conversation = store.createConversation("fixture", "", title = "File changes ANR regression")
        val changes = JSONArray()
        repeat(16) { index ->
            val lines = if (index == 15) lineCount else 1
            changes.put(JSONObject().put("path", "mypkg/file$index.py").put("added", 16).put("removed", 0)
                .put("diff", "+ print('hello')\n".repeat(lines)).put("before", "")
                .put("after", "print('hello')\n".repeat(lines) + if (lines > 1) "x".repeat(100_000) else ""))
        }
        store.addMessage(conversation, "user", "Write Python files")
        fun appendChanges() {
            store.addMessage(conversation, "tool", JSONObject().put("file_changes", changes).toString())
            store.addMessage(conversation, "assistant", "Files written")
        }
        if (!incoming) appendChanges()
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as MainActivity
        val handler = Handler(Looper.getMainLooper())
        try {
            onMain(handler) {
                val field = MainActivity::class.java.getDeclaredField("controller").apply { isAccessible = true }
                val controller = field.get(activity) as ChatController
                controller.reloadConversations()
                controller.selectConversation(conversation)
            }
            if (incoming) {
                awaitText(handler, activity, "Write Python files")
                appendChanges()
                onMain(handler) {
                    val field = MainActivity::class.java.getDeclaredField("controller").apply { isAccessible = true }
                    (field.get(activity) as ChatController).reloadMessages()
                }
            }
            awaitText(handler, activity, "mypkg/file15.py")
            click(handler, activity, activity.getString(R.string.cd_expand))
            Thread.sleep(400)
            click(handler, activity, activity.getString(R.string.cd_expand_alt))
            awaitText(handler, activity, "mypkg/file15.py")
            click(handler, activity, "mypkg/file15.py")
            awaitText(handler, activity, activity.getString(R.string.label_diff))
            awaitText(handler, activity, "+ print('hello')")
            // Repeated heartbeat catches a layout/scroll loop after asynchronous parsing finishes.
            repeat(30) {
                Thread.sleep(100)
                onMain(handler) { }
            }
            onMain(handler) {
                val field = MainActivity::class.java.getDeclaredField("controller").apply { isAccessible = true }
                val controller = field.get(activity) as ChatController
                controller.newConversation()
                controller.selectConversation(conversation)
            }
            awaitText(handler, activity, "mypkg/file15.py")
            // Return through the real history drawer too.
            click(handler, activity, activity.getString(R.string.cd_menu))
            awaitText(handler, activity, "File changes ANR regression")
            click(handler, activity, "File changes ANR regression")
            awaitText(handler, activity, "mypkg/file15.py")
        } finally {
            handler.post { activity.finish() }
            store.deleteConversation(conversation)
            store.close()
        }
    }

    private fun roots(view: View): List<ViewRootForTest> = buildList {
        if (view is ViewRootForTest) add(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) addAll(roots(view.getChildAt(index)))
    }
    private fun all(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::all)
    private fun nodes(activity: MainActivity) = roots(activity.window.decorView).flatMap { all(it.semanticsOwner.rootSemanticsNode) }
    private fun matches(node: SemanticsNode, text: String): Boolean =
        node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true ||
            node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(text) == true

    private fun awaitText(handler: Handler, activity: MainActivity, text: String) {
        repeat(100) {
            var found = false
            onMain(handler) { found = nodes(activity).any { matches(it, text) } }
            if (found) return
            Thread.sleep(100)
        }
        throw AssertionError("UI did not render: $text")
    }
    private fun click(handler: Handler, activity: MainActivity, text: String) = onMain(handler) {
        var node = nodes(activity).first { matches(it, text) }
        while (node.config.getOrNull(SemanticsActions.OnClick) == null) node = node.parent!!
        assertTrue(node.config[SemanticsActions.OnClick].action!!.invoke())
    }

    private fun onMain(handler: Handler, action: () -> Unit) {
        val done = CountDownLatch(1)
        var failure: Throwable? = null
        handler.post {
            try { action() } catch (error: Throwable) { failure = error } finally { done.countDown() }
        }
        assertTrue("Main thread failed to respond within 2 seconds", done.await(2, TimeUnit.SECONDS))
        failure?.let { throw it }
    }
}
