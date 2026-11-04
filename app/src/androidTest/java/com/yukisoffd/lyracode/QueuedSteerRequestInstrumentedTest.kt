package com.yukisoffd.lyracode

import androidx.test.platform.app.InstrumentationRegistry
import com.yukisoffd.lyracode.ai.OpenAiAgent
import com.yukisoffd.lyracode.ai.SteerSource
import com.yukisoffd.lyracode.ai.WebViewWebAgent
import com.yukisoffd.lyracode.chat.pending.PendingInputStore
import com.yukisoffd.lyracode.data.ApiProfile
import com.yukisoffd.lyracode.data.AppSettings
import com.yukisoffd.lyracode.data.AuditLogStore
import com.yukisoffd.lyracode.data.BackupManager
import com.yukisoffd.lyracode.data.ConversationStore
import com.yukisoffd.lyracode.filetransfer.FileTransferClient
import com.yukisoffd.lyracode.mcp.McpClientManager
import com.yukisoffd.lyracode.server.MiniServerManager
import com.yukisoffd.lyracode.ssh.SshExecutor
import com.yukisoffd.lyracode.system.SystemCommandExecutor
import com.yukisoffd.lyracode.tasks.DownloadTaskManager
import com.yukisoffd.lyracode.tasks.ScheduledTaskManager
import com.yukisoffd.lyracode.termux.TermuxExecutor
import com.yukisoffd.lyracode.webaccess.WebAccessHttp
import com.yukisoffd.lyracode.webdav.WebDavClient
import com.yukisoffd.lyracode.workspace.GlobalFileManager
import com.yukisoffd.lyracode.workspace.NativeFileManager
import com.yukisoffd.lyracode.workspace.WorkspaceFileReference
import com.yukisoffd.lyracode.workspace.WorkspaceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class QueuedSteerRequestInstrumentedTest {
    @Test
    fun nextModelRequestIncludesForcedSkillAndSelectedWorkspaceFile() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        AppStrings.initialize(context)
        val settings = AppSettings(context)
        val previousPureMode = settings.purePromptMode
        val skill = settings.importSkillMarkdown("Steer test.md", "# Review selected files").getOrThrow()
        val requests = LinkedBlockingQueue<JSONObject>()
        val releaseFirstResponse = CountDownLatch(1)
        try {
            settings.purePromptMode = false
            settings.setSkillEnabled(skill.id, false)
            ServerSocket(0, 2, InetAddress.getByName("127.0.0.1")).use { server ->
                val provider = FutureTask { serveRounds(server, requests, releaseFirstResponse) }
                val providerThread = thread(isDaemon = true, name = "Steer test provider") { provider.run() }
                fun nextRequest(): JSONObject {
                    requests.poll(15, TimeUnit.SECONDS)?.let { return it }
                    if (provider.isDone) provider.get()
                    error("No request arrived at the test provider")
                }
                try {
                    ConversationStore(context, inMemory = true).use { history ->
                        val workspace = WorkspaceManager(context, settings)
                        val nativeFiles = NativeFileManager(context, workspace)
                        val globalFiles = GlobalFileManager()
                        val agent = OpenAiAgent(
                            context, settings, history, nativeFiles, globalFiles,
                            TermuxExecutor(context, AuditLogStore(context)), workspace, WebViewWebAgent(context, settings),
                            McpClientManager(context, settings), SshExecutor(settings), SystemCommandExecutor(context, settings),
                            WebDavClient(), FileTransferClient(context), BackupManager(context, settings, history),
                            MiniServerManager(context, settings, workspace),
                            DownloadTaskManager.getInstance(context, settings, nativeFiles, globalFiles),
                            ScheduledTaskManager.getInstance(context),
                        )
                        val profile = ApiProfile(
                            "steer-fixture", "Steer fixture", "", "http://127.0.0.1:${server.localPort}/v1",
                            selectedModel = "steer-test", savedModels = listOf("steer-test"),
                        )
                        val conversation = history.createConversation(profile.id, profile.selectedModel, "Steer fixture")
                        val pending = PendingInputStore(readQueue = { null }, writeQueue = { _, _ -> })
                        agent.steerSource = SteerSource(pending::drainSteers)
                        withTimeout(40_000) {
                            val run = async(Dispatchers.IO) {
                                agent.chat(conversation, "Start reviewing", profile, profile.selectedModel, propagateErrors = true) {}
                            }
                            val first = nextRequest()
                            assertFalse(first.toString().contains(skill.id))
                            val request = captureChatRequest(
                                rawText = "Review this file",
                                uploads = emptyList(),
                                workspaceFiles = listOf(WorkspaceFileReference("notes.md", "docs/notes.md", "content://notes")),
                                forcedSkillIds = listOf(skill.id),
                                editorContextPath = "",
                            )
                            val queued = pending.enqueue(conversation, request.toPendingInputCapture())
                            pending.takeForSteering(conversation, queued.id)
                            releaseFirstResponse.countDown()

                            val second = nextRequest().getJSONArray("messages")
                            val contents = (0 until second.length()).map { second.getJSONObject(it).optString("content") }
                            val steeredText = contents.firstOrNull { it.startsWith("Review this file") }
                                ?: error("The next model request did not include the steered user message")
                            assertEquals(listOf("docs/notes.md"), workspaceReferencePreviews(steeredText).map { it.path })
                            assertTrue(
                                "The next model request did not include the selected forced Skill",
                                contents.any { it.contains("forced_skill_ids=") && it.contains(skill.id) },
                            )
                            run.await()
                            provider.get(2, TimeUnit.SECONDS)
                        }
                    }
                } finally {
                    releaseFirstResponse.countDown()
                    server.close()
                    providerThread.join(2_000)
                }
            }
        } finally {
            settings.purePromptMode = previousPureMode
            settings.deleteSkill(skill.id)
        }
    }

    private fun serveRounds(
        server: ServerSocket,
        requests: LinkedBlockingQueue<JSONObject>,
        releaseFirstResponse: CountDownLatch,
    ) {
        repeat(2) { round ->
            server.accept().use { socket ->
                socket.soTimeout = 10_000
                val input = socket.getInputStream().buffered()
                val headers = WebAccessHttp.readRequest(input)
                val body = WebAccessHttp.readBody(input, headers.length)
                requests.add(JSONObject(body.toString(Charsets.UTF_8)))
                if (round == 0) check(releaseFirstResponse.await(20, TimeUnit.SECONDS))
                val output = socket.getOutputStream()
                WebAccessHttp.eventHeaders(output)
                output.write(
                    ("data: {\"choices\":[{\"delta\":{\"content\":\"Fixture reply\"}}]}\n\n" +
                        "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n" +
                        "data: [DONE]\n\n").toByteArray(),
                )
                output.flush()
            }
        }
    }
}
