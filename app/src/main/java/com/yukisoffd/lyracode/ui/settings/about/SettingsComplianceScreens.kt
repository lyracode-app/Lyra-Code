package com.yukisoffd.lyracode

import androidx.annotation.ArrayRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FactCheck
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

internal object CompliancePageIds {
    const val INDEX = "service_agreements"
    const val USER_AGREEMENT = "user_agreement"
    const val PRIVACY_POLICY = "privacy_policy"
    const val PERSONAL_INFO = "personal_info_list"
    const val THIRD_PARTY = "third_party_info_list"
    const val APP_PERMISSIONS = "app_permissions_document"

    val documents = setOf(USER_AGREEMENT, PRIVACY_POLICY, PERSONAL_INFO, THIRD_PARTY, APP_PERMISSIONS)
}

private const val COMPLIANCE_REVISION_DATE = "2026-11-28"
private const val PROJECT_ISSUES = "https://github.com/lyracode-app/Lyra-Code/issues"
private const val PROJECT_SECURITY = "https://github.com/lyracode-app/Lyra-Code/security"
private const val PROJECT_LICENSE = "https://github.com/lyracode-app/Lyra-Code/blob/main/LICENSE"

private data class CompliancePage(
    val id: String,
    val icon: ImageVector,
    @param:StringRes val title: Int,
    @param:StringRes val summary: Int,
    @param:StringRes val description: Int,
)

private val compliancePages = listOf(
    CompliancePage(CompliancePageIds.USER_AGREEMENT, Icons.Default.Gavel, R.string.compliance_user_agreement,
        R.string.compliance_user_summary, R.string.compliance_user_agreement_desc),
    CompliancePage(CompliancePageIds.PRIVACY_POLICY, Icons.Default.PrivacyTip, R.string.compliance_privacy_policy,
        R.string.compliance_privacy_summary, R.string.compliance_privacy_policy_desc),
    CompliancePage(CompliancePageIds.PERSONAL_INFO, Icons.AutoMirrored.Filled.FactCheck, R.string.compliance_personal_info_list,
        R.string.compliance_personal_summary, R.string.compliance_personal_info_list_desc),
    CompliancePage(CompliancePageIds.THIRD_PARTY, Icons.Default.Hub, R.string.compliance_third_party_list,
        R.string.compliance_third_party_summary, R.string.compliance_third_party_list_desc),
    CompliancePage(CompliancePageIds.APP_PERMISSIONS, Icons.Default.AdminPanelSettings, R.string.compliance_app_permissions,
        R.string.compliance_permissions_summary, R.string.compliance_app_permissions_desc),
)

@Composable
internal fun ServiceAgreementScreen(onOpenDocument: (String) -> Unit) {
    KimiCardBox {
        compliancePages.forEachIndexed { index, page ->
            if (index > 0) KimiDivider()
            KimiMenuRow(page.icon, stringResource(page.title), stringResource(page.description),
                onClick = { onOpenDocument(page.id) })
        }
    }
}

@Composable
internal fun ComplianceDocumentScreen(pageId: String) {
    val page = compliancePages.first { it.id == pageId }
    val locale = LocalConfiguration.current.locales[0]
    val revision = LocalDate.parse(COMPLIANCE_REVISION_DATE)
        .format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(locale))
    KimiCardBox {
        Text(stringResource(page.title), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.compliance_effective_date, revision),
            color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
        SelectionContainer {
            Text(stringResource(page.summary), style = MaterialTheme.typography.bodyMedium)
        }
    }
    when (pageId) {
        CompliancePageIds.USER_AGREEMENT -> {
            ComplianceSection(R.array.compliance_user_scope)
            ComplianceSection(R.array.compliance_user_features)
            ComplianceSection(R.array.compliance_user_rules)
            ComplianceSection(R.array.compliance_user_fees)
            ComplianceSection(R.array.compliance_user_intellectual_property,
                R.string.compliance_project_license to PROJECT_LICENSE)
            ComplianceSection(R.array.compliance_user_responsibility)
            ComplianceSection(R.array.compliance_user_disputes,
                R.string.compliance_feedback_link to PROJECT_ISSUES)
            ComplianceSection(R.array.compliance_user_changes)
        }
        CompliancePageIds.PRIVACY_POLICY -> {
            ComplianceSection(R.array.compliance_privacy_contact,
                R.string.compliance_feedback_link to PROJECT_ISSUES,
                R.string.compliance_security_link to PROJECT_SECURITY)
            KimiSectionLabel(stringResource(R.string.compliance_processing_title))
            ComplianceTable(listOf(featureColumn, informationColumn, purposeColumn, timingColumn, permissionColumn, retentionColumn),
                personalRows(), listOf(0, 1, 2, 3, 4, 7))
            ComplianceSection(R.array.compliance_privacy_sensitive)
            ComplianceSection(R.array.compliance_privacy_external)
            ComplianceSection(R.array.compliance_privacy_storage)
            ComplianceSection(R.array.compliance_privacy_security)
            ComplianceSection(R.array.compliance_privacy_rights)
            ComplianceSection(R.array.compliance_privacy_children)
            ComplianceSection(R.array.compliance_privacy_changes)
        }
        CompliancePageIds.PERSONAL_INFO -> ComplianceTable(
            listOf(featureColumn, informationColumn, permissionColumn, requiredColumn, alternativeColumn, retentionColumn, impactColumn),
            personalRows(), listOf(0, 1, 4, 5, 6, 7, 8),
        )
        CompliancePageIds.THIRD_PARTY -> ComplianceTable(
            listOf(thirdServiceColumn, thirdOperatorColumn, purposeColumn, informationColumn, thirdTimingColumn, policyColumn),
            thirdPartyRows(),
        )
        CompliancePageIds.APP_PERMISSIONS -> {
            ComplianceTable(listOf(featureColumn, purposeColumn, permissionColumn), permissionRows())
            ComplianceSection(R.array.compliance_permission_control)
        }
    }
}

@Composable
private fun ComplianceSection(@ArrayRes section: Int, vararg links: Pair<Int, String>) {
    val (title, body) = stringArrayResource(section)
    KimiSectionLabel(title)
    KimiCardBox {
        SelectionContainer {
            Text(body, Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyMedium)
        }
        links.forEach { (label, url) ->
            ComplianceLink(stringResource(label), url)
        }
    }
}

private data class ComplianceColumn(@param:StringRes val label: Int, val width: Int)
private data class ComplianceRow(val cells: List<String>, val policyUrl: String? = null)

private val featureColumn = ComplianceColumn(R.string.compliance_column_feature, 138)
private val informationColumn = ComplianceColumn(R.string.compliance_column_information, 264)
private val purposeColumn = ComplianceColumn(R.string.compliance_column_purpose, 185)
private val timingColumn = ComplianceColumn(R.string.compliance_column_timing, 244)
private val permissionColumn = ComplianceColumn(R.string.compliance_column_permission, 235)
private val requiredColumn = ComplianceColumn(R.string.compliance_column_required, 142)
private val alternativeColumn = ComplianceColumn(R.string.compliance_column_alternative, 175)
private val retentionColumn = ComplianceColumn(R.string.compliance_column_retention, 222)
private val impactColumn = ComplianceColumn(R.string.compliance_column_impact, 190)
private val thirdServiceColumn = ComplianceColumn(R.string.compliance_column_service, 164)
private val thirdOperatorColumn = ComplianceColumn(R.string.compliance_column_operator, 194)
private val thirdTimingColumn = ComplianceColumn(R.string.compliance_column_third_timing, 244)
private val policyColumn = ComplianceColumn(R.string.compliance_column_policy, 187)

@Composable
private fun ComplianceTable(
    columns: List<ComplianceColumn>,
    rows: List<ComplianceRow>,
    columnIndices: List<Int> = columns.indices.toList(),
) {
    KimiCardBox {
        Text(stringResource(R.string.compliance_table_scroll_hint), color = KimiMuted,
            style = MaterialTheme.typography.bodySmall)
        val lineColor = MaterialTheme.colorScheme.outlineVariant
        HorizontalDivider(color = lineColor)
        val tableWidth = columns.sumOf { it.width }.dp + (columns.size - 1).dp
        Column(Modifier.horizontalScroll(rememberScrollState()).width(tableWidth)) {
            Row(
                Modifier.height(IntrinsicSize.Min)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                columns.forEachIndexed { index, column ->
                    if (index > 0) VerticalDivider(Modifier.fillMaxHeight(), color = lineColor)
                    Text(stringResource(column.label),
                        Modifier.width(column.width.dp).padding(10.dp).semantics { heading() },
                        style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                }
            }
            HorizontalDivider(color = lineColor)
            rows.forEachIndexed { rowIndex, row ->
                Row(
                    Modifier.height(IntrinsicSize.Min).background(
                        if (rowIndex % 2 == 0) MaterialTheme.colorScheme.surface
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                    ),
                ) {
                    columns.forEachIndexed { index, column ->
                        if (index > 0) VerticalDivider(Modifier.fillMaxHeight(), color = lineColor)
                        val modifier = Modifier.width(column.width.dp).padding(10.dp)
                        val cell = row.cells[columnIndices[index]]
                        if (column == policyColumn && row.policyUrl != null) {
                            ComplianceLink(cell, row.policyUrl, modifier)
                        } else {
                            Text(cell, modifier, style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (index == 0) FontWeight.Medium else FontWeight.Normal)
                        }
                    }
                }
                HorizontalDivider(color = lineColor)
            }
        }
    }
}

@Composable
private fun ComplianceLink(label: String, url: String, modifier: Modifier = Modifier) {
    val linkStyle = SpanStyle(color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline)
    Text(
        buildAnnotatedString { withLink(LinkAnnotation.Url(url, TextLinkStyles(style = linkStyle))) { append(label) } },
        modifier,
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun personalRows(): List<ComplianceRow> =
    personalInfoResources.map { ComplianceRow(stringArrayResource(it).toList()) }

@Composable
private fun permissionRows(): List<ComplianceRow> =
    permissionResources.map { ComplianceRow(stringArrayResource(it).toList()) }

@Composable
private fun thirdPartyRows(): List<ComplianceRow> {
    val modelDetails = stringArrayResource(R.array.compliance_third_model_fields).toList()
    val openPolicy = stringResource(R.string.compliance_open_privacy_policy)
    val providerSite = stringResource(R.string.compliance_open_provider_site)
    val models = ProviderCatalog.presets.map { preset ->
        val policy = modelPrivacyPolicies[preset.id]
        ComplianceRow(
            listOf(preset.displayName(), policy?.operator ?: preset.displayName()) + modelDetails +
                (if (policy?.url != null) openPolicy else providerSite),
            policy?.url ?: preset.websiteUrl,
        )
    }
    val otherServices = thirdPartyResources.map { (resource, url) ->
        key(resource) {
            val cells = stringArrayResource(resource).toList()
            ComplianceRow(if (url != null) cells + openPolicy else cells, url)
        }
    }
    return listOf(otherServices.first()) + models + otherServices.drop(1)
}

private data class ProviderPrivacy(val operator: String, val url: String?)

private val modelPrivacyPolicies = mapOf(
    "ai302" to ProviderPrivacy("302.AI", "https://price.302.ai/privacy/"),
    "aihubmix" to ProviderPrivacy("AIHubMix", "https://api.aihubmix.com/legal/privacy"),
    "anthropic" to ProviderPrivacy("Anthropic", "https://www.anthropic.com/legal/privacy"),
    "bailian" to ProviderPrivacy("Alibaba Cloud", "https://www.alibabacloud.com/help/en/legal/latest/alibaba-cloud-international-website-privacy-policy"),
    "deepseek" to ProviderPrivacy("DeepSeek", "https://cdn.deepseek.com/policies/zh-CN/deepseek-privacy-policy.html"),
    "gemini" to ProviderPrivacy("Google", "https://ai.google.dev/gemini-api/terms"),
    "minimax" to ProviderPrivacy("MiniMax", "https://platform.minimaxi.com/document/%E9%9A%90%E7%A7%81%E6%9D%A1%E6%AC%BE?key=67cd3394ce12aabaf58e6035"),
    "moonshot" to ProviderPrivacy("Moonshot AI", "https://platform.kimi.com/docs/agreement/userprivacy"),
    "nvidia" to ProviderPrivacy("NVIDIA", "https://www.nvidia.com/en-us/about-nvidia/privacy-policy/"),
    "openai" to ProviderPrivacy("OpenAI", "https://openai.com/business-data/"),
    "opencodego" to ProviderPrivacy("OpenCode", "https://opencode.ai/legal/privacy-policy"),
    "openrouter" to ProviderPrivacy("OpenRouter", "https://openrouter.ai/privacy"),
    "sensenova" to ProviderPrivacy("SenseTime", "https://token.sensetime.com/docs/guides/agreement/policy"),
    "siliconcloud" to ProviderPrivacy("SiliconFlow", "https://docs.siliconflow.cn/docs/legals/privacy-policy"),
    "stepfun" to ProviderPrivacy("StepFun", "https://platform.stepfun.com/legal/privacy-policy.html"),
    "volcengine" to ProviderPrivacy("Volcengine", "https://docs.volcengine.com/docs/legal-agreements/volcano-engines-privacy-policy?lang=zh"),
    "xai" to ProviderPrivacy("SpaceX Super Intelligence (xAI)", "https://x.ai/legal/data-processing-addendum"),
    "xiaomimimo" to ProviderPrivacy("Xiaomi", "https://privacy.mi.com/XiaomiMiMoPlatform/zh_CN"),
    "zai" to ProviderPrivacy("Z.ai / Zhipu AI", "https://docs.bigmodel.cn/cn/terms/privacy-policy"),
)

private val personalInfoResources = listOf(
    // Each row is shared by the privacy policy and the personal information list.
    R.array.compliance_info_settings, R.array.compliance_info_profile, R.array.compliance_info_chat,
    R.array.compliance_info_model, R.array.compliance_info_new_api, R.array.compliance_info_chatgpt,
    R.array.compliance_info_harness, R.array.compliance_info_workspace, R.array.compliance_info_attachments,
    R.array.compliance_info_automation, R.array.compliance_info_location, R.array.compliance_info_apps,
    R.array.compliance_info_device, R.array.compliance_info_interaction, R.array.compliance_info_local_runtime,
    R.array.compliance_info_remote, R.array.compliance_info_mail, R.array.compliance_info_web,
    R.array.compliance_info_deepseek_web,
    R.array.compliance_info_local_servers, R.array.compliance_info_diagnostics, R.array.compliance_info_cache,
    R.array.compliance_info_backup, R.array.compliance_info_updates, R.array.compliance_info_wall,
)

private val permissionResources = listOf(
    R.array.compliance_perm_internet, R.array.compliance_perm_lan, R.array.compliance_perm_location,
    R.array.compliance_perm_camera, R.array.compliance_perm_media, R.array.compliance_perm_files,
    R.array.compliance_perm_packages, R.array.compliance_perm_notifications, R.array.compliance_perm_install,
    R.array.compliance_perm_foreground, R.array.compliance_perm_overlay, R.array.compliance_perm_accessibility,
    R.array.compliance_perm_biometric, R.array.compliance_perm_commands, R.array.compliance_perm_boot,
    R.array.compliance_perm_receiver,
)

private val thirdPartyResources = listOf(
    R.array.compliance_third_local to null,
    R.array.compliance_third_custom to null,
    R.array.compliance_third_new_api to null,
    R.array.compliance_third_chatgpt to "https://openai.com/policies/privacy-policy/",
    R.array.compliance_third_harness to "https://cdn.deepseek.com/policies/zh-CN/deepseek-privacy-policy.html",
    R.array.compliance_third_deepseek_web to "https://cdn.deepseek.com/policies/zh-CN/deepseek-privacy-policy.html",
    R.array.compliance_third_ddg to "https://duckduckgo.com/privacy",
    R.array.compliance_third_google to "https://policies.google.com/privacy",
    R.array.compliance_third_bing to "https://www.microsoft.com/privacy/privacystatement",
    R.array.compliance_third_baidu to "https://www.baidu.com/duty/yinsiquan-policy.html",
    R.array.compliance_third_web to null,
    R.array.compliance_third_mcp to null,
    R.array.compliance_third_remote to null,
    R.array.compliance_third_mail to null,
    R.array.compliance_third_termux to null,
    R.array.compliance_third_lan to null,
    R.array.compliance_third_github to "https://docs.github.com/en/site-policy/privacy-policies/github-general-privacy-statement",
    R.array.compliance_third_release_hosts to null,
    R.array.compliance_third_debian to "https://www.debian.org/legal/privacy",
    R.array.compliance_third_android to null,
    R.array.compliance_third_gitee to "https://gitee.com/terms/privacy_terms",
    R.array.compliance_third_sponsor to "https://www.tenpay.com/v3/helpcenter/low/privacy.shtml",
    R.array.compliance_third_links to null,
)
