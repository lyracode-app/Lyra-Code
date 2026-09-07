package com.yukisoffd.lyracode.interaction.agent

import android.content.Context
import com.yukisoffd.lyracode.ai.OpenAiAgent
import com.yukisoffd.lyracode.ai.WebViewWebAgent
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
import com.yukisoffd.lyracode.webdav.WebDavClient
import com.yukisoffd.lyracode.workspace.GlobalFileManager
import com.yukisoffd.lyracode.workspace.NativeFileManager
import com.yukisoffd.lyracode.workspace.WorkspaceManager

/** Owns application-context dependencies; no reference to MainActivity or ChatController. */
internal object DeviceAgentFactory {
    fun create(context: Context, settings: AppSettings, store: ConversationStore): OpenAiAgent {
        val app = context.applicationContext
        val workspace = WorkspaceManager(app, settings)
        val nativeFiles = NativeFileManager(app, workspace)
        val globalFiles = GlobalFileManager()
        return OpenAiAgent(
            context = app, settings = settings, conversationStore = store,
            nativeFileManager = nativeFiles, globalFileManager = globalFiles,
            termuxExecutor = TermuxExecutor(app, AuditLogStore(app)), workspaceManager = workspace,
            webAgent = WebViewWebAgent(app, settings), mcpClientManager = McpClientManager(app, settings),
            sshExecutor = SshExecutor(settings), systemCommandExecutor = SystemCommandExecutor(app, settings),
            webDavClient = WebDavClient(), fileTransferClient = FileTransferClient(app),
            backupManager = BackupManager(app, settings, store),
            miniServerManager = MiniServerManager(app, settings, workspace),
            downloadTaskManager = DownloadTaskManager.getInstance(app, settings, nativeFiles, globalFiles),
            scheduledTaskManager = ScheduledTaskManager.getInstance(app),
        )
    }
}
