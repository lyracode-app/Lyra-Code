package com.yukisoffd.lyracode

import android.content.Intent
import android.view.WindowManager
import androidx.test.platform.app.InstrumentationRegistry
import com.yukisoffd.lyracode.ai.ToolApprovalRequest
import com.yukisoffd.lyracode.data.*
import com.yukisoffd.lyracode.webaccess.WebAccessManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

@org.junit.FixMethodOrder(org.junit.runners.MethodSorters.NAME_ASCENDING)
class WebAccessInstrumentedTest {
    @Test fun authenticatedWebCompanionSharesControllerAndReleasesResources() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        if (Android17Compatibility.requiresLocalNetworkPermission() && !Android17Compatibility.hasLocalNetworkAccess(context)) {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Android17Compatibility.ACCESS_LOCAL_NETWORK_PERMISSION)
        }
        val settings = AppSettings(context)
        val originalProfiles = settings.profiles()
        val originalProfileId = settings.selectedApiProfileId
        val originalReasoning = settings.reasoningDepth
        val originalLanguage = settings.languageMode
        val originalPure = settings.purePromptMode
        val originalPrompt = settings.selectedSystemPromptId
        val originalSubAgents = settings.subAgents()
        val originalOrchestration = settings.subAgentOrchestrationEnabled
        val originalDisabled = settings.disabledTools()
        val originalHost = settings.webAccessHost; val originalPort = settings.webAccessPort
        val originalChunks = settings.historyCompressionChunkCount
        val originalCompressionProfile = settings.historyCompressionProfileId; val originalCompressionModel = settings.historyCompressionModel
        val originalSummaryProfile = settings.topicSummaryProfileId; val originalSummaryModel = settings.topicSummaryModel
        val originalConsent = FirstUseConsentStore(context).isAccepted
        FirstUseConsentStore(context).accept()
        val modelServer = ServerSocket(0)
        val modelThread = Thread {
            while (!modelServer.isClosed) runCatching {
                modelServer.accept().use { socket ->
                    socket.soTimeout = 5000
                    val input = socket.getInputStream().buffered()
                    val header = com.yukisoffd.lyracode.webaccess.WebAccessHttp.readRequest(input)
                    val body = JSONObject(com.yukisoffd.lyracode.webaccess.WebAccessHttp.readBody(input,header.length).toString(Charsets.UTF_8))
                    val text = if (body.optBoolean("stream")) "data: {\"choices\":[{\"delta\":{\"content\":\"Hello from the local test model\"}}]}\n\ndata: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"
                        else "{\"choices\":[{\"message\":{\"content\":\"Web test conversation\"}}]}"
                    if (body.optBoolean("stream")) {
                        val output=socket.getOutputStream()
                        com.yukisoffd.lyracode.webaccess.WebAccessHttp.eventHeaders(output)
                        for (chunk in listOf("Hello ","from ","the ","local ","test ","model")) {
                            val delta=JSONObject().put("choices",org.json.JSONArray().put(JSONObject().put("delta",JSONObject().put("content",chunk))))
                            output.write("data: $delta\n\n".toByteArray());output.flush();Thread.sleep(100)
                        }
                        output.write("data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}],\"usage\":{\"completion_tokens\":30,\"prompt_cache_hit_tokens\":75,\"prompt_cache_miss_tokens\":25}}\n\ndata: [DONE]\n\n".toByteArray());output.flush()
                    } else com.yukisoffd.lyracode.webaccess.WebAccessHttp.respond(socket.getOutputStream(),200,"application/json",text.toByteArray())
                }
            }
        }.apply { isDaemon=true; start() }
        val testProfile = ApiProfile("web-test-profile-${System.nanoTime()}", "Web test provider", "web-test-api-key", "http://127.0.0.1:${modelServer.localPort}/v1", selectedModel="test-model",savedModels=listOf("test-model"),presetId="deepseek")
        settings.saveProfiles(originalProfiles + testProfile,testProfile.id)
        settings.topicSummaryProfileId=testProfile.id; settings.topicSummaryModel="test-model"
        settings.purePromptMode=true
        settings.webAccessHost="127.0.0.1"; settings.webAccessPort=ServerSocket(0).use { it.localPort }
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val manager = MainActivity::class.java.getDeclaredField("webAccessManager").apply { isAccessible=true }.get(activity) as WebAccessManager
        val controller = MainActivity::class.java.getDeclaredField("controller").apply { isAccessible=true }.get(activity) as ChatController
        // The process runtime may predate this fixture's direct settings write.
        instrumentation.runOnMainSync { controller.reloadProfiles() }
        val base="http://127.0.0.1:${settings.webAccessPort}"
        val client=OkHttpClient.Builder().readTimeout(20,TimeUnit.SECONDS).build()
        val token=settings.webAccessToken
        val approvalScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        var conversationId=0L; var projectId=0L
        val uploadedFiles=mutableListOf<String>()
        fun getState(): JSONObject = client.newCall(Request.Builder().url("$base/api/state").header("Authorization","Bearer $token").build()).execute().use { assertEquals(200,it.code); JSONObject(it.body!!.string()) }
        fun action(name:String,args:JSONObject=JSONObject(),expectedCode:Int=200, target:JSONObject=getState()):JSONObject {
            args.put("action",name).put("conversationId",target.getLong("conversationId")).put("projectId",target.getLong("projectId")).put("sessionRevision",target.getInt("sessionRevision"))
            return client.newCall(Request.Builder().url("$base/api/action").header("Authorization","Bearer $token").post(args.toString().toRequestBody("application/json".toMediaType())).build()).execute().use {
                val body=it.body!!.string(); assertEquals(body,expectedCode,it.code); JSONObject(body)
            }
        }
        fun upload(name:String,bytes:ByteArray, target:JSONObject=getState(), expectedCode:Int=200) {
            client.newCall(Request.Builder().url("$base/api/upload").header("Authorization","Bearer $token")
                .header("X-File-Name",URLEncoder.encode(name,"UTF-8")).header("X-Conversation-Id",target.getLong("conversationId").toString())
                .header("X-Project-Id",target.getLong("projectId").toString()).header("X-Session-Revision",target.getInt("sessionRevision").toString())
                .post(bytes.toRequestBody("application/octet-stream".toMediaType())).build()).execute().use { assertEquals(expectedCode,it.code) }
        }
        try {
            instrumentation.runOnMainSync { manager.start(); assertTrue(manager.state.value.error,manager.state.value.running); assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0) }
            client.newCall(Request.Builder().url("$base/api/state").build()).execute().use { assertEquals(401,it.code) }
            client.newCall(Request.Builder().url("$base/api/events").build()).execute().use { assertEquals(401,it.code) }
            client.newCall(Request.Builder().url("$base/api/media?id=not-a-capability").build()).execute().use { assertEquals(404,it.code) }
            client.newCall(Request.Builder().url(base).build()).execute().use { assertEquals(200,it.code); assertTrue(it.body!!.string().contains("设置分类")) }
            val initial=getState(); assertFalse(initial.toString().contains(testProfile.apiKey))
            val themeColors = initial.getJSONObject("theme").getJSONObject("colors")
            assertEquals(18, themeColors.length())
            themeColors.keys().forEach { role -> assertTrue(themeColors.getString(role).matches(Regex("#[0-9a-fA-F]{6}"))) }
            for ((mode, language, auto, max) in listOf(
                listOf(AppSettings.LANGUAGE_ZH_CN,"zh-CN","自动","最大档"),
                listOf(AppSettings.LANGUAGE_ZH_TW,"zh-TW","自動","最大檔"),
                listOf(AppSettings.LANGUAGE_EN,"en","Auto","Max"),
            )) {
                instrumentation.runOnMainSync { settings.languageMode=mode }
                val locale=client.newCall(Request.Builder().url("$base/api/locale").build()).execute().use {
                    assertEquals(200,it.code); JSONObject(it.body!!.string())
                }
                assertEquals(language,locale.getString("language"))
                assertEquals(auto,locale.getJSONObject("reasoningLabels").getString("auto"))
                assertEquals(max,locale.getJSONObject("reasoningLabels").getString("max"))
                assertEquals(locale.toString(),getState().getJSONObject("locale").toString())
                assertEquals(4,locale.length()) // No account, session, or access token in the public response.
            }
            instrumentation.runOnMainSync { settings.languageMode=originalLanguage }
            action("reasoning",JSONObject().put("value","high")); assertEquals("high",settings.reasoningDepth)
            action("model",JSONObject().put("profileId",testProfile.id).put("model","test-model"))
            action("autoApproval",JSONObject().put("enabled",true)); assertTrue(getState().getBoolean("autoApproval"))
            action("promptSave",JSONObject().put("id","web-test-prompt").put("name","PC prompt").put("prompt","Test prompt"))
            action("promptSelect",JSONObject().put("id","web-test-prompt")); assertEquals("web-test-prompt",settings.selectedSystemPromptId)
            action("subAgentSave",JSONObject().put("id","web-test-agent").put("name","Reviewer").put("profileId",testProfile.id).put("model","test-model").put("description","Review code").put("enabled",true))
            action("subAgentsEnabled",JSONObject().put("enabled",true)); assertTrue(settings.enabledSubAgents().any { it.id=="web-test-agent" })
            action("tool",JSONObject().put("name","read_file").put("enabled",false)); assertTrue(settings.disabledTools().contains("read_file"))
            action("tool",JSONObject().put("name","manage_app_config").put("enabled",false),400)
            action("mcpSave",JSONObject().put("id","web-test-mcp").put("name","Test MCP").put("enabled",false).put("timeoutSeconds",30).put("rawJson","{\"url\":\"https://example.com/mcp\"}"))
            assertEquals("https://example.com/mcp",settings.mcpServers().first { it.id=="web-test-mcp" }.url)
            action("compression",JSONObject().put("mode","tokens").put("turns",12).put("tokens",4096).put("chunks",2))
            assertEquals(4096,getState().getJSONObject("compression").getInt("tokens"))
            val stale=getState(); action("new"); upload("stale.txt","stale".toByteArray(),stale,409)
            upload("../电脑附件.txt","from PC".toByteArray())
            upload("binary.bin",byteArrayOf(0,1,2,3))
            instrumentation.runOnMainSync {
                assertEquals("电脑附件.txt",controller.pendingUploads[0].name)
                assertEquals("from PC",controller.pendingUploads[0].content)
                assertEquals("file",controller.pendingUploads[1].mediaKind)
                uploadedFiles += controller.pendingUploads.map { it.uri }
            }
            val pendingMedia=getState().getJSONArray("media")
            val binary=(0 until pendingMedia.length()).map { pendingMedia.getJSONObject(it) }.first { it.getString("name")=="binary.bin" }
            client.newCall(Request.Builder().url("$base/api/media?id=${binary.getString("id")}").header("Range","bytes=1-2").build()).execute().use {
                assertEquals(206,it.code);assertEquals("bytes 1-2/4",it.header("Content-Range"));assertArrayEquals(byteArrayOf(1,2),it.body!!.bytes())
            }
            val pushed=mutableSetOf<String>()
            var pushedPerformance=false
            client.newCall(Request.Builder().url("$base/api/events").header("Authorization","Bearer $token").build()).execute().use { events ->
                assertEquals(200,events.code)
                action("send",JSONObject().put("text","Hello from desktop"))
                val source=events.body!!.source();val streamDeadline=System.currentTimeMillis()+20_000
                while(System.currentTimeMillis()<streamDeadline) {
                    val line=source.readUtf8Line() ?: break
                    if(!line.startsWith("data: "))continue
                    val snapshot=JSONObject(line.removePrefix("data: "))
                    val records=snapshot.getJSONArray("messages")
                    for(index in 0 until records.length()) {
                        val record=records.getJSONObject(index)
                        if(record.getString("role")=="assistant" && record.getString("content").isNotEmpty())pushed+=record.getString("content")
                        assertTrue(record.has("tokensPerSecond"));assertTrue(record.has("deepSeekCacheHitRate"))
                        if(record.getString("role")=="assistant" && record.optDouble("tokensPerSecond",0.0)>0.0 && record.optDouble("deepSeekCacheHitRate",-1.0)==75.0)pushedPerformance=true
                    }
                    if(!snapshot.getBoolean("running") && pushed.any { it=="Hello from the local test model" })break
                }
            }
            assertTrue("Expected intermediate pushed content: $pushed",pushed.size>1)
            assertTrue("Expected native speed and DeepSeek cache statistics over SSE",pushedPerformance)
            conversationId=getState().getLong("conversationId"); assertTrue(conversationId>0)
            val deadline=System.currentTimeMillis()+20_000
            while (getState().getBoolean("running") && System.currentTimeMillis()<deadline) Thread.sleep(200)
            val response=getState(); assertFalse(response.toString(),response.getBoolean("running"))
            assertFalse(response.getBoolean("canContinue"))
            action("continue");assertFalse(getState().getBoolean("running"))
            assertTrue(response.getJSONArray("messages").toString().contains("Hello from the local test model"))
            assertTrue(response.getJSONArray("messages").toString().contains("电脑附件.txt"))
            val records=response.getJSONArray("messages")
            val assistant=(0 until records.length()).map {records.getJSONObject(it)}.last {it.getString("role")=="assistant"}
            assertTrue(assistant.getDouble("tokensPerSecond")>0.0)
            assertEquals(75.0,assistant.getDouble("deepSeekCacheHitRate"),0.0)
            action("rename",JSONObject().put("id",conversationId).put("title","Renamed on PC"))
            action("pin",JSONObject().put("id",conversationId).put("enabled",true))
            assertEquals("Renamed on PC",controller.usageStore().conversation(conversationId)!!.title)
            action("autoApproval",JSONObject().put("enabled",false))
            val agent = ChatController::class.java.getDeclaredField("agent").apply { isAccessible=true }
                .get(controller) as com.yukisoffd.lyracode.ai.OpenAiAgent
            val approval = approvalScope.async {
                agent.approvalHandler(ToolApprovalRequest(conversationId,"run_command","{}","echo test","test"))
            }
            instrumentation.waitForIdleSync()
            var approvalId = 0L
            instrumentation.runOnMainSync { approvalId=controller.pendingToolApproval.value!!.id }
            action("approval",JSONObject().put("id",approvalId-1).put("approved",true),409)
            instrumentation.runOnMainSync { assertEquals(approvalId,controller.pendingToolApproval.value!!.id) }
            action("approval",JSONObject().put("id",approvalId).put("approved",true))
            assertTrue(runBlocking { withTimeout(5_000) { approval.await() } }.approved)
            instrumentation.runOnMainSync { assertNull(controller.pendingToolApproval.value) }
            instrumentation.runOnMainSync { projectId=controller.usageStore().createProject("Web project",""); controller.reloadConversations() }
            action("newProjectChat",JSONObject().put("id",projectId)); assertEquals(projectId,getState().getLong("projectId"))
            assertTrue(getState().getJSONArray("projects").toString().contains("Web project"))
            action("workspace",JSONObject().put("uri","file:///data/data/private"),400)
            action("archive",JSONObject().put("id",conversationId)); assertTrue(controller.usageStore().conversation(conversationId)!!.archivedAt>0)
            action("restore",JSONObject().put("id",conversationId)); assertEquals(0L,controller.usageStore().conversation(conversationId)!!.archivedAt)
            instrumentation.runOnMainSync { manager.stop(); assertFalse(manager.state.value.running); assertEquals(0,activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            ServerSocket().use { it.bind(java.net.InetSocketAddress("127.0.0.1",settings.webAccessPort)) }
        } finally {
            approvalScope.cancel()
            instrumentation.runOnMainSync {
                manager.stop(); controller.stopActive()
                if (conversationId>0) controller.deleteConversation(conversationId)
                if (projectId>0) controller.deleteProject(projectId)
                activity.finish()
            }
            instrumentation.waitForIdleSync()
            modelServer.close(); modelThread.join(1000)
            uploadedFiles.forEach { java.io.File(it).delete() }
            settings.saveProfiles(originalProfiles,originalProfileId); settings.reasoningDepth=originalReasoning; settings.languageMode=originalLanguage
            settings.deleteSystemPromptConfig("web-test-prompt"); settings.selectedSystemPromptId=originalPrompt; settings.purePromptMode=originalPure
            settings.saveSubAgents(originalSubAgents); settings.subAgentOrchestrationEnabled=originalOrchestration
            settings.setToolEnabled("read_file","read_file" !in originalDisabled); settings.deleteMcpServer("web-test-mcp")
            settings.webAccessHost=originalHost; settings.webAccessPort=originalPort; settings.historyCompressionChunkCount=originalChunks
            settings.historyCompressionProfileId=originalCompressionProfile; settings.historyCompressionModel=originalCompressionModel
            settings.topicSummaryProfileId=originalSummaryProfile; settings.topicSummaryModel=originalSummaryModel
            context.getSharedPreferences("first_use_consent",0).edit().putBoolean("accepted",originalConsent).commit()
        }
    }


    @Test fun sharedStorageDirectoryWorksWithoutAnySafGrant() {
        val i=InstrumentationRegistry.getInstrumentation();val context=i.targetContext
        assertTrue("Grant All files access on the test device before running this test",com.yukisoffd.lyracode.workspace.SharedStorageWorkspace.permitted())
        if (Android17Compatibility.requiresLocalNetworkPermission() && !Android17Compatibility.hasLocalNetworkAccess(context)) i.uiAutomation.grantRuntimePermission(context.packageName,Android17Compatibility.ACCESS_LOCAL_NETWORK_PERMISSION)
        val oldConsent=FirstUseConsentStore(context).isAccepted;FirstUseConsentStore(context).accept()
        val settings=AppSettings(context);val oldHost=settings.webAccessHost;val oldPort=settings.webAccessPort
        settings.webAccessHost="127.0.0.1";settings.webAccessPort=ServerSocket(0).use { it.localPort }
        val name="lyra-web-direct-test-"+java.util.UUID.randomUUID()
        val directory=java.io.File(android.os.Environment.getExternalStorageDirectory(),"Documents/$name/nested");assertTrue(directory.mkdirs())
        val originalGrants=context.contentResolver.persistedUriPermissions.map { it.uri.toString() }.toSet()
        val activity=i.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val manager=MainActivity::class.java.getDeclaredField("webAccessManager").apply { isAccessible=true }.get(activity) as WebAccessManager
        try {
            i.runOnMainSync { manager.start();assertTrue(manager.state.value.error,manager.state.value.running) }
            val client=OkHttpClient();val token=settings.webAccessToken;val base="http://127.0.0.1:"+settings.webAccessPort
            fun getState()=client.newCall(Request.Builder().url("$base/api/state").header("Authorization","Bearer $token").build()).execute().use { JSONObject(it.body!!.string()) }
            fun action(action:String,args:JSONObject):JSONObject {
                val current=getState();args.put("action",action).put("conversationId",current.getLong("conversationId")).put("projectId",current.getLong("projectId")).put("sessionRevision",current.getInt("sessionRevision"))
                return client.newCall(Request.Builder().url("$base/api/action").header("Authorization","Bearer $token").post(args.toString().toRequestBody("application/json".toMediaType())).build()).execute().use {val body=it.body!!.string();assertEquals(body,200,it.code);JSONObject(body)}
            }
            val roots=action("browseWorkspace",JSONObject());assertFalse(roots.getBoolean("needsStoragePermission"))
            val rootEntries=roots.getJSONArray("entries")
            assertTrue((0 until rootEntries.length()).any { rootEntries.getJSONObject(it).getString("uri")=="file:///storage/emulated/0" })
            val uri=android.net.Uri.fromFile(directory)
            action("workspace",JSONObject().put("uri",uri.toString()))
            val after=getState();assertEquals(uri.toString(),after.getJSONObject("workspace").getString("uri"))
            assertTrue(after.getJSONObject("workspace").getString("path").endsWith("/nested"))
            val workspace=com.yukisoffd.lyracode.workspace.WorkspaceManager(context,settings)
            workspace.persistWorkspace(uri)
            val file=workspace.root()!!.createFile("text/plain","from-web.txt")!!
            context.contentResolver.openOutputStream(file.uri)!!.use {it.write("direct workspace".toByteArray())}
            assertEquals("direct workspace",context.contentResolver.openInputStream(file.uri)!!.use {it.readBytes().toString(Charsets.UTF_8)})
            assertTrue(workspace.searchFiles("from-web").any {it.name=="from-web.txt"})
            assertEquals(originalGrants,context.contentResolver.persistedUriPermissions.map {it.uri.toString()}.toSet())
        } finally {
            i.runOnMainSync {manager.stop();activity.finish()}
            java.io.File(android.os.Environment.getExternalStorageDirectory(),"Documents/$name").deleteRecursively()
            settings.webAccessHost=oldHost;settings.webAccessPort=oldPort
            context.getSharedPreferences("first_use_consent",0).edit().putBoolean("accepted",oldConsent).commit()
        }
    }
}
