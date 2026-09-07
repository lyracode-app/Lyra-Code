package com.yukisoffd.lyracode.interaction.agent

import android.content.Context
import com.yukisoffd.lyracode.ai.OpenAiAgent
import com.yukisoffd.lyracode.ai.DEVICE_WORKSPACE_TOOLS
import com.yukisoffd.lyracode.data.AppSettings
import com.yukisoffd.lyracode.interaction.perception.DeviceScreenshotSource
import com.yukisoffd.lyracode.interaction.policy.DeviceShellPolicy
import com.yukisoffd.lyracode.interaction.policy.TextInputPolicy
import com.yukisoffd.lyracode.interaction.session.DeviceApprovalBroker
import com.yukisoffd.lyracode.interaction.session.DeviceTaskCoordinator
import com.yukisoffd.lyracode.interaction.session.ManualControlController
import com.yukisoffd.lyracode.system.SystemCommandExecutor
import org.json.JSONArray
import org.json.JSONObject

internal class DeviceExtendedTools(context: Context, private val settings: AppSettings,
    private val agent: OpenAiAgent, private val conversationId: Long, private val checkSession: () -> Unit) {
    private val commands = SystemCommandExecutor(context, settings)
    private val vision = settings.visionUnderstandingModelOrNull() ?: settings.selectedProfile().let { profile ->
        // Explicit override covers custom model aliases; text-only models retain the semantic path.
        val enabled = com.yukisoffd.lyracode.interaction.pet.DevicePetStore.options(context).optBoolean("current_model_vision", false)
        if (enabled) profile to profile.selectedModel else null
    }
    private var screenshotFingerprint: String? = null
    private var screenshotDigest: String? = null
    private var lastScreenshotAt = 0L
    fun definitions(): JSONArray = agent.deviceWorkspaceDefinitions().apply {
        if (vision != null) put(tool("device_screenshot", "After upload confirmation, inspect the current window using the configured vision model. Returns visual observations and pixel coordinates. Never captures secure or sensitive pages.", "question"))
        if (settings.requestShellAccess) put(tool("execute_shell_command", COMMAND_HELP, "command"))
        if (settings.requestRootAccess) put(tool("execute_root_command", COMMAND_HELP, "command"))
    }
    suspend fun execute(name: String, args: JSONObject): String {
        checkSession()
        val before = ManualControlController.state.value.latestSnapshot ?: return "ERROR: OBSERVE_CURRENT_APP_FIRST"
        val pkg = before.activePackage ?: return "ERROR: NO_FOREGROUND_APP"
        if (!DeviceTaskCoordinator.isAgentPackageAllowed(pkg)) return "BLOCKED: PACKAGE_NOT_ALLOWED；此系统或凭据界面不支持自动操作，可返回其他应用继续，对话保持。"
        suspend fun approve(title: String, detail: String, twice: Boolean = false): Boolean {
            if (!DeviceApprovalBroker.request(title, detail, pkg, twice)) return false
            checkSession()
            return ManualControlController.state.value.latestSnapshot?.uiFingerprint == before.uiFingerprint
        }
        if (name == "device_screenshot") {
            if (before.nodes.any { it.visible && it.windowId == before.activeWindowId && (it.password || it.redacted || it.accessibilityDataSensitive) })
                return "BLOCKED: SENSITIVE_SCREENSHOT；截图可能包含密码、验证码或受保护字段，禁止上传。请使用已遮蔽字段的 device_observe 继续安全操作，对话保持。"
            val (profile, model) = vision ?: return "ERROR: VISION_MODEL_NOT_CONFIGURED"
            val question = args.getString("question").take(2000)
            if (android.os.SystemClock.elapsedRealtime() - lastScreenshotAt < 1200) return "ERROR: SCREENSHOT_RATE_LIMIT"
            if (!approve("截图识别", "当前窗口截图将发送至 ${profile.name} · $model\n识别目的：$question")) return "ERROR: APPROVAL_INVALIDATED_OR_REJECTED"
            if (!DeviceScreenshotSource.matchesForeground(before)) return "ERROR: FOREGROUND_CHANGED"
            val image = DeviceScreenshotSource.capture(before.activeWindowId ?: return "ERROR: NO_WINDOW")
            checkSession()
            if (ManualControlController.state.value.latestSnapshot?.uiFingerprint != before.uiFingerprint) return "ERROR: PAGE_CHANGED"
            lastScreenshotAt = android.os.SystemClock.elapsedRealtime()
            val report = agent.analyzeDeviceScreenshot(profile, model, image.dataUrl,
                "Image ${image.width} x ${image.height}, origin in screen pixels (${image.left}, ${image.top}). Return absolute screen coordinates by adding that origin. Return only JSON with boolean sensitive_flow and string description. Set sensitive_flow=true for visible password/OTP entry, transaction execution (buy/sell/pay/transfer), or permission approval controls. Merely opening finance apps or browsing financial information is NOT sensitive; omit all secret values. Task: $question").take(12000)
            // Coordinate execution remains unavailable if vision reports any protected flow.
            val safe = runCatching { JSONObject(report.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()).getBoolean("sensitive_flow") == false }.getOrDefault(false)
            screenshotFingerprint = before.uiFingerprint.takeIf { safe }
            screenshotDigest = if (safe) TextInputPolicy.fingerprint(image.dataUrl) else null
            return JSONObject().put("classification", "UNTRUSTED_SCREEN_CONTENT").put("snapshot_id", before.snapshotId)
                .put("width", image.width).put("height", image.height).put("origin_x", image.left).put("origin_y", image.top).put("report", report).toString()
        }
        if (name == "execute_shell_command" || name == "execute_root_command") {
            if (name == "execute_root_command" && !settings.requestRootAccess || name == "execute_shell_command" && !settings.requestShellAccess)
                return "ERROR: TOOL_DISABLED"
            val command = DeviceShellPolicy.parse(args.getString("command"), before.display.widthPixels, before.display.heightPixels)
                ?: return "ERROR: COMMAND_BLOCKED_USE_SUPPORTED_ANDROID_COMMANDS"
            if (command.coordinateAction) {
                val words = command.text.split(' ')
                val x = words[2].toInt(); val y = words[3].toInt()
                val target = before.nodes.filter { node ->
                    node.visible && node.windowId == before.activeWindowId &&
                        x in node.bounds.left until node.bounds.right && y in node.bounds.top until node.bounds.bottom &&
                        (node.password || node.redacted || node.accessibilityDataSensitive ||
                            com.yukisoffd.lyracode.interaction.model.SemanticAction.ACTIVATE in node.actions)
                }.minByOrNull { it.bounds.width.toLong() * it.bounds.height }
                if (target != null) {
                    val decision = com.yukisoffd.lyracode.interaction.policy.DeviceActionPolicy.evaluate(pkg, target,
                        com.yukisoffd.lyracode.interaction.model.ManualDeviceAction.ACTIVATE, before.nodes)
                    if (decision is com.yukisoffd.lyracode.interaction.policy.DevicePolicyDecision.Blocked)
                        return "BLOCKED: ${decision.reason}；${com.yukisoffd.lyracode.interaction.policy.DeviceActionPolicy.blockExplanation(decision.reason)} 对话保持，禁止通过坐标绕过。"
                }
            }
            if (command.coordinateAction && screenshotFingerprint != before.uiFingerprint)
                return "ERROR: CURRENT_SCREENSHOT_REQUIRED_FOR_COORDINATES"
            if (command.destructive) {
                val installed = if (name == "execute_root_command") commands.executeRoot("pm list packages -3", 10)
                    else commands.executeShell("pm list packages -3", 10)
                if ("package:${command.text.substringAfterLast(' ')}" !in installed.stdout.lineSequence().map(String::trim).toList())
                    return "ERROR: ONLY_INSTALLED_THIRD_PARTY_APPS_CAN_BE_UNINSTALLED"
            }
            if ((command.destructive || command.coordinateAction) && !approve(if (command.destructive) "卸载应用（会删除应用数据）" else "${if (name == "execute_root_command") "Root" else "Shell"} 操作",
                    command.text, command.destructive || command.coordinateAction)) return "ERROR: APPROVAL_INVALIDATED_OR_REJECTED"
            ManualControlController.updateChat(ManualControlController.state.value.chat.copy(status = "执行中 · $name"))
            val beforeDispatch: suspend () -> Unit = {
                checkSession()
                check(DeviceScreenshotSource.matchesForeground(before)) { "当前应用或页面已变化，操作取消。" }
                if (command.coordinateAction) {
                    val fresh = DeviceScreenshotSource.capture(before.activeWindowId ?: error("窗口已消失"))
                    check(TextInputPolicy.fingerprint(fresh.dataUrl) == screenshotDigest) { "屏幕画面已变化，请重新截图识别。" }
                    checkSession()
                    check(DeviceScreenshotSource.matchesForeground(before)) { "页面已变化，操作取消。" }
                }
            }
            val result = if (name == "execute_root_command") commands.executeRoot(command.text, 20, beforeDispatch = beforeDispatch)
                else commands.executeShell(command.text, 20, beforeDispatch)
            screenshotFingerprint = null
            val verified = if (command.destructive && result.ok) {
                val after = if (name == "execute_root_command") commands.executeRoot("pm list packages -3", 10) else commands.executeShell("pm list packages -3", 10)
                after.ok && "package:${command.text.substringAfterLast(' ')}" !in after.stdout.lineSequence().map(String::trim).toList()
            } else result.ok
            ManualControlController.setPetEvent(JSONObject().put("id", java.util.UUID.randomUUID().toString())
                .put("operation", if (command.destructive) "uninstall" else "shell")
                .put("packageName", if (command.destructive) command.text.substringAfterLast(' ') else pkg)
                .put("ok", verified).put("exitCode", result.exitCode).toString())
            return JSONObject(result.toJson()).put("verified", verified).toString()
        }
        if (name !in DEVICE_WORKSPACE_TOOLS) return "ERROR: TOOL_NOT_AVAILABLE_IN_DEVICE_SESSION"
        if (args.toString().length > 16000 ||
            Regex("(?i)(\\.env|credential|id_rsa|id_ed25519|keystore|token|secret|cookies|/data/|/proc/|/sys/)").containsMatchIn(args.toString()))
            return "BLOCKED: PROTECTED_DATA；工具请求涉及凭据文件或受保护路径，或参数过长；请跳过该步骤，对话保持。"
        if (!approve("工作区工具 · $name", args.toString(2), name in setOf("delete_file_or_folder", "global_delete_file_or_folder"))) return "ERROR: APPROVAL_INVALIDATED_OR_REJECTED"
        ManualControlController.updateChat(ManualControlController.state.value.chat.copy(status = "执行中 · $name"))
        val output = agent.executeDeviceWorkspaceTool(conversationId, name, args)
        return if (Regex("(?i)(api[_ -]?key|access[_ -]?token|password|private[_ -]?key)\\s*[:=]\\s*[^\\s]{4,}|-----BEGIN .*PRIVATE KEY-----").containsMatchIn(output))
            "BLOCKED: PROTECTED_CONTENT_REDACTED；检测到凭据值，工具输出已遮蔽，对话保持。" else output
    }
    private fun tool(name: String, description: String, parameter: String) = JSONObject().put("type", "function")
        .put("function", JSONObject().put("name", name).put("description", description).put("parameters", JSONObject()
            .put("type", "object").put("properties", JSONObject().put(parameter, JSONObject().put("type", "string")))
            .put("required", JSONArray().put(parameter)).put("additionalProperties", false)))
    private companion object {
        const val COMMAND_HELP = "Android Shizuku/Root commands with exact foreground approval. Allowed grammar: input tap X Y; input swipe X1 Y1 X2 Y2 DURATION_MS; input keyevent KEYCODE_BACK|KEYCODE_HOME|KEYCODE_APP_SWITCH; wm size; wm density; pm list packages -3; monkey -p PACKAGE -c android.intent.category.LAUNCHER 1; pm uninstall --user 0 PACKAGE. Coordinates require a fresh device_screenshot and two confirmations; third-party uninstall requires two confirmations. No arbitrary shell, scripts, text injection, credentials or financial actions."
    }
}
