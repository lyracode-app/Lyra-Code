package com.yukisoffd.lyracode.interaction.policy

import com.yukisoffd.lyracode.interaction.model.ManualDeviceAction
import com.yukisoffd.lyracode.interaction.model.SemanticAction
import com.yukisoffd.lyracode.interaction.model.SemanticNode

internal sealed interface DevicePolicyDecision {
    data object Allowed : DevicePolicyDecision
    data class Blocked(val reason: DevicePolicyBlockReason) : DevicePolicyDecision
}

internal enum class DevicePolicyBlockReason {
    PACKAGE_NOT_ALLOWED,
    SENSITIVE_NODE,
    HIGH_RISK_CONTROL,
    PASSWORD_OR_CODE,
    FINANCIAL_OPERATION,
    ACTION_NOT_ADVERTISED,
    INVISIBLE_OR_DISABLED,
}

internal object DeviceActionPolicy {
    fun evaluate(
        packageName: String,
        node: SemanticNode,
        action: ManualDeviceAction,
        pageNodes: List<SemanticNode> = emptyList(),
    ): DevicePolicyDecision {
        if (!isPackageAllowed(packageName)) {
            return DevicePolicyDecision.Blocked(DevicePolicyBlockReason.PACKAGE_NOT_ALLOWED)
        }
        if (node.packageName != null && node.packageName != packageName) {
            return DevicePolicyDecision.Blocked(DevicePolicyBlockReason.PACKAGE_NOT_ALLOWED)
        }
        if (node.password) return DevicePolicyDecision.Blocked(DevicePolicyBlockReason.PASSWORD_OR_CODE)
        if (node.password || node.accessibilityDataSensitive || node.redacted ||
            (node.editable && TextInputPolicy.isSensitive(node.inputType, node.hintText, node.contentDescription, node.resourceId?.substringAfterLast('/')))) {
            return DevicePolicyDecision.Blocked(DevicePolicyBlockReason.SENSITIVE_NODE)
        }
        val launcherNavigation = action == ManualDeviceAction.ACTIVATE &&
            Regex("(?i)(^|\\.)(launcher[0-9]*|nexuslauncher|trebuchet|home)$").containsMatchIn(packageName)
        if (!launcherNavigation && (action == ManualDeviceAction.ACTIVATE || action == ManualDeviceAction.SET_TEXT)) {
            val label = listOfNotNull(node.text.takeUnless { node.editable }, node.contentDescription, node.hintText,
                node.resourceId?.substringAfterLast('/')).joinToString(" ").replace('_', ' ')
            val confirmation = Regex("^(确认|確定|确定|確認|提交|继续|繼續|confirm|submit|continue|ok)$", RegexOption.IGNORE_CASE)
                .matches(node.text.orEmpty().trim())
            val financialContext = confirmation && pageNodes.any {
                it.visible && it.windowId == node.windowId && it.packageName == node.packageName &&
                    FINANCIAL_ACTION.containsMatchIn(listOfNotNull(it.text, it.hintText, it.contentDescription).joinToString(" "))
            }
            val financialAssetDeletion = Regex("(?i)delete|remove|删除|刪除").containsMatchIn(label) &&
                Regex("(?i)wallet|bank account|钱包|錢包|银行账户|銀行帳戶").containsMatchIn(label)
            val readingRecords = Regex("(?i)^(查看|浏览|瀏覽)?(交易|转账|轉帳|支付|付款|购买|購買)(记录|紀錄|历史|歷史|明细|明細)$|^(view |show )?(payment|transfer|purchase|transaction)s? (history|records|details)$")
                .matches(node.text.orEmpty().trim())
            if ((!readingRecords && FINANCIAL_ACTION.containsMatchIn(label)) || financialContext || financialAssetDeletion)
                return DevicePolicyDecision.Blocked(DevicePolicyBlockReason.FINANCIAL_OPERATION)
        }
        if (!node.enabled || !node.visible) {
            return DevicePolicyDecision.Blocked(DevicePolicyBlockReason.INVISIBLE_OR_DISABLED)
        }
        if (!advertises(node, action)) {
            return DevicePolicyDecision.Blocked(DevicePolicyBlockReason.ACTION_NOT_ADVERTISED)
        }
        if (!launcherNavigation && action in setOf(ManualDeviceAction.ACTIVATE, ManualDeviceAction.SET_TEXT) &&
            containsHighRiskLanguage(node, includeValue = action != ManualDeviceAction.SET_TEXT)) {
            if (action == ManualDeviceAction.ACTIVATE && requiresSecondConfirmation(node, action)) return DevicePolicyDecision.Allowed
            return DevicePolicyDecision.Blocked(DevicePolicyBlockReason.HIGH_RISK_CONTROL)
        }
        return DevicePolicyDecision.Allowed
    }

    fun isPackageAllowed(packageName: String): Boolean {
        val normalized = packageName.lowercase()
        return normalized.isNotBlank() &&
            normalized !in BLOCKED_SYSTEM_PACKAGES &&
            BLOCKED_SYSTEM_SUFFIXES.none(normalized::endsWith)
    }

    fun requiresSecondConfirmation(node: SemanticNode, action: ManualDeviceAction, pageNodes: List<SemanticNode> = emptyList()): Boolean {
        if (action != ManualDeviceAction.ACTIVATE) return false
        val label = listOfNotNull(node.text, node.contentDescription, node.hintText, node.resourceId?.substringAfterLast('/')).joinToString(" ").replace('_', ' ').replace('-', ' ').lowercase()
        if (TextInputPolicy.isSensitive(node.inputType, label) || Regex("\\b(pay|buy|purchase|transfer|authorize|password|otp)\\b|支付|付款|转账|轉帳|密码|密碼|验证码|驗證碼|购买|購買").containsMatchIn(label)) return false
        val destructive = Regex("\\b(delete|remove|uninstall|erase|wipe|trash)\\b|clear\\s+(data|storage|history|cache|all)|删除|刪除|卸载|解除安裝|移除|清空|清除|抹除|销毁|銷毀")
        if (destructive.containsMatchIn(label)) return true
        val confirmation = Regex("(?i)^(confirm|ok|yes|continue|确定|確認|确认|是|继续|繼續)$")
            .matches(node.text.orEmpty().trim())
        return confirmation && pageNodes.any { it.visible && it.windowId == node.windowId && it.packageName == node.packageName &&
            destructive.containsMatchIn(listOfNotNull(it.text, it.hintText, it.contentDescription).joinToString(" ").lowercase()) }

    }

    fun blockExplanation(reason: DevicePolicyBlockReason): String = when (reason) {
        DevicePolicyBlockReason.SENSITIVE_NODE -> "目标字段已被应用或敏感数据策略标记为受保护，禁止自动读取或填写。可继续操作其他控件，或由用户手动完成此步骤。"
        DevicePolicyBlockReason.PASSWORD_OR_CODE -> "目标为密码、验证码或受保护的敏感字段，禁止自动读取或输入。可跳过此步骤或由用户手动完成。"
        DevicePolicyBlockReason.FINANCIAL_OPERATION -> "该操作涉及购买、交易、支付或转账，财产安全策略禁止自动执行。浏览行情和理财信息仍可继续。若涉及大额或陌生收款人转账，您可能正在遭受诈骗，请先独立核实。"
        DevicePolicyBlockReason.PACKAGE_NOT_ALLOWED -> "目标属于受保护的系统界面或不属于当前应用，禁止执行此操作。"
        DevicePolicyBlockReason.HIGH_RISK_CONTROL -> "目标涉及安全授权或对外提交等受限操作，请用户手动完成。"
        DevicePolicyBlockReason.ACTION_NOT_ADVERTISED -> "此控件不支持请求的无障碍动作。"
        DevicePolicyBlockReason.INVISIBLE_OR_DISABLED -> "此控件不可见或已禁用，请重新观察页面。"
    }

    private val FINANCIAL_ACTION = Regex("\\b(buy|purchase|pay|payment|transfer|checkout|withdraw|deposit|trade|sell|subscribe fund)\\b|购买|購買|买入|買入|卖出|賣出|支付|付款|转账|轉帳|汇款|匯款|提现|提現|充值|下单|下單|确认交易|申购|申購|赎回|贖回", RegexOption.IGNORE_CASE)

    private fun advertises(node: SemanticNode, action: ManualDeviceAction): Boolean = when (action) {
        ManualDeviceAction.SET_TEXT -> node.editable && SemanticAction.SET_TEXT in node.actions &&
            TextInputPolicy.isOrdinaryText(node.inputType) && node.textFingerprint != null
        ManualDeviceAction.ACTIVATE -> SemanticAction.ACTIVATE in node.actions
        ManualDeviceAction.SCROLL_FORWARD -> {
            SemanticAction.SCROLL_FORWARD in node.actions ||
                SemanticAction.SCROLL_DOWN in node.actions ||
                SemanticAction.SCROLL_RIGHT in node.actions
        }
        ManualDeviceAction.SCROLL_BACKWARD -> {
            SemanticAction.SCROLL_BACKWARD in node.actions ||
                SemanticAction.SCROLL_UP in node.actions ||
                SemanticAction.SCROLL_LEFT in node.actions
        }
    }

    private fun containsHighRiskLanguage(node: SemanticNode, includeValue: Boolean): Boolean {
        val labels = listOfNotNull(node.text.takeIf { includeValue }, node.hintText, node.contentDescription, node.resourceId?.substringAfterLast('/')?.replace('_', ' '))
            .map { it.trim().lowercase() }
        return labels.any { label ->
            HIGH_RISK_ENGLISH.containsMatchIn(label) ||
                HIGH_RISK_CJK_TERMS.any(label::contains)
        }
    }

    private val BLOCKED_SYSTEM_PACKAGES = setOf(
        "android",
        "com.android.systemui",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.android.settings",
    )

    private val BLOCKED_SYSTEM_SUFFIXES = setOf(
        ".permissioncontroller",
        ".packageinstaller",
    )

    private val HIGH_RISK_ENGLISH = Regex(
        "\\b(authorize|password|otp)\\b|verification\\s+code",
        RegexOption.IGNORE_CASE,
    )

    private val HIGH_RISK_CJK_TERMS = setOf("授权", "授權", "密码", "密碼", "验证码", "驗證碼")
}
