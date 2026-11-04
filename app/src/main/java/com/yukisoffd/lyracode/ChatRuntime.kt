package com.yukisoffd.lyracode

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Resources
import android.os.Looper
import com.yukisoffd.lyracode.ai.AiResponseCache
import com.yukisoffd.lyracode.ai.OpenAiAgent
import com.yukisoffd.lyracode.ai.WebViewWebAgent
import com.yukisoffd.lyracode.data.AppSettings
import com.yukisoffd.lyracode.data.AuditLogStore
import com.yukisoffd.lyracode.data.BackupManager
import com.yukisoffd.lyracode.data.ConversationStore
import com.yukisoffd.lyracode.filetransfer.FileTransferClient
import com.yukisoffd.lyracode.mcp.LocalMcpServerManager
import com.yukisoffd.lyracode.mcp.McpClientManager
import com.yukisoffd.lyracode.server.MiniServerManager
import com.yukisoffd.lyracode.ssh.SshExecutor
import com.yukisoffd.lyracode.system.SystemCommandExecutor
import com.yukisoffd.lyracode.tasks.DownloadTaskManager
import com.yukisoffd.lyracode.tasks.ScheduledTaskManager
import com.yukisoffd.lyracode.termux.TermuxExecutor
import com.yukisoffd.lyracode.webdav.WebDavClient
import com.yukisoffd.lyracode.workspace.GlobalFileManager
import com.yukisoffd.lyracode.workspace.NativeFileManager
import com.yukisoffd.lyracode.workspace.UploadedFileManager
import com.yukisoffd.lyracode.workspace.WorkspaceManager
import java.lang.ref.WeakReference

/** Holds one process-wide instance and the activity attached to it. Main thread only. */
internal class ProcessRuntimeOwner<R : Any, T : Any> {
    private var instance: R? = null
    private var owner: WeakReference<T>? = null

    fun getOrCreate(factory: () -> R): R = instance ?: factory().also { instance = it }
    fun attach(value: T) { owner = WeakReference(value) }
    fun detach(value: T) {
        if (owner?.get() === value) owner = null
    }
    fun isAttached(value: T): Boolean = owner?.get() === value
}

internal class ChatRuntime private constructor(context: Context) {
    private val application = context.applicationContext
    private val appContext = object : ContextWrapper(application) {
        override fun getResources(): Resources =
            application.localizedContext(settings.languageMode).resources
    }
    val settings = AppSettings(application)
    val auditLogStore = AuditLogStore(appContext)
    val conversationStore = ConversationStore(appContext)
    val workspaceManager = WorkspaceManager(appContext, settings)
    val nativeFileManager = NativeFileManager(appContext, workspaceManager)
    val globalFileManager = GlobalFileManager()
    val downloadTaskManager = DownloadTaskManager.getInstance(appContext, settings, nativeFileManager, globalFileManager)
    val scheduledTaskManager = ScheduledTaskManager.getInstance(appContext)
    val termuxExecutor = TermuxExecutor(appContext, auditLogStore)
    val uploadedFileManager = UploadedFileManager(appContext)
    val webAgent = WebViewWebAgent(appContext, settings)
    val responseCache = AiResponseCache(appContext.cacheDir)
    val mcpClientManager = McpClientManager(appContext, settings)
    val sshExecutor = SshExecutor(settings)
    val systemCommandExecutor = SystemCommandExecutor(appContext, settings)
    val webDavClient = WebDavClient()
    val fileTransferClient = FileTransferClient(appContext)
    val backupManager = BackupManager(appContext, settings, conversationStore)
    val miniServerManager = MiniServerManager(appContext, settings, workspaceManager)
    val localMcpServerManager = LocalMcpServerManager(settings)
    val agent = OpenAiAgent(appContext, settings, conversationStore, nativeFileManager, globalFileManager, termuxExecutor, workspaceManager, webAgent, mcpClientManager, sshExecutor, systemCommandExecutor, webDavClient, fileTransferClient, backupManager, miniServerManager, downloadTaskManager, scheduledTaskManager, responseCache)
    val controller: ChatController

    init {
        localMcpServerManager.attachAgent(agent)
        controller = ChatController(appContext, settings, conversationStore, uploadedFileManager, workspaceManager, agent)
    }

    fun attach(activity: MainActivity) {
        check(Looper.myLooper() == Looper.getMainLooper())
        runtimeHolder.attach(activity)
    }

    fun detach(activity: MainActivity) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (runtimeHolder.isAttached(activity)) {
            runtimeHolder.detach(activity)
            // Stop Activity-scoped listeners without cancelling the controller's runs.
            miniServerManager.stop()
            localMcpServerManager.stop(saveDisabled = false)
        }
    }

    internal fun isAttached(activity: MainActivity): Boolean = runtimeHolder.isAttached(activity)

    companion object {
        private val runtimeHolder = ProcessRuntimeOwner<ChatRuntime, MainActivity>()

        fun get(context: Context): ChatRuntime {
            check(Looper.myLooper() == Looper.getMainLooper())
            return runtimeHolder.getOrCreate { ChatRuntime(context.applicationContext) }
        }
    }
}
