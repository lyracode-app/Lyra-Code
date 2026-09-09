package com.yukisoffd.lyracode

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FactCheck
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.DataObject
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Policy
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

internal object CompliancePageIds {
    const val INDEX = "service_agreements"
    const val USER_AGREEMENT = "user_agreement"
    const val PRIVACY_POLICY = "privacy_policy"
    const val PERSONAL_INFO = "personal_info_list"
    const val THIRD_PARTY = "third_party_info_list"
    const val APP_PERMISSIONS = "app_permissions_document"

    val documents = setOf(USER_AGREEMENT, PRIVACY_POLICY, PERSONAL_INFO, THIRD_PARTY, APP_PERMISSIONS)
}

private data class ComplianceDocument(
    val icon: ImageVector,
    @param:StringRes val title: Int,
    @param:StringRes val summary: Int,
    val sections: List<ComplianceSection>,
)

private data class ComplianceSection(
    @param:StringRes val title: Int,
    @param:StringRes val body: Int,
)

@Composable
internal fun ServiceAgreementScreen(onOpenDocument: (String) -> Unit) {
    KimiCardBox {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Default.Policy, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(stringResource(R.string.compliance_service_agreements), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.compliance_index_intro), color = KimiMuted, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    KimiSectionLabel(stringResource(R.string.compliance_documents))
    KimiCardBox {
        ComplianceMenuRow(Icons.Default.Gavel, R.string.compliance_user_agreement, R.string.compliance_user_agreement_desc) {
            onOpenDocument(CompliancePageIds.USER_AGREEMENT)
        }
        KimiDivider()
        ComplianceMenuRow(Icons.Default.PrivacyTip, R.string.compliance_privacy_policy, R.string.compliance_privacy_policy_desc) {
            onOpenDocument(CompliancePageIds.PRIVACY_POLICY)
        }
        KimiDivider()
        ComplianceMenuRow(Icons.AutoMirrored.Filled.FactCheck, R.string.compliance_personal_info_list, R.string.compliance_personal_info_list_desc) {
            onOpenDocument(CompliancePageIds.PERSONAL_INFO)
        }
        KimiDivider()
        ComplianceMenuRow(Icons.Default.Hub, R.string.compliance_third_party_list, R.string.compliance_third_party_list_desc) {
            onOpenDocument(CompliancePageIds.THIRD_PARTY)
        }
        KimiDivider()
        ComplianceMenuRow(Icons.Default.AdminPanelSettings, R.string.compliance_app_permissions, R.string.compliance_app_permissions_desc) {
            onOpenDocument(CompliancePageIds.APP_PERMISSIONS)
        }
    }
    KimiCardBox {
        Text(stringResource(R.string.compliance_review_notice), color = KimiMuted, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ComplianceMenuRow(
    icon: ImageVector,
    @StringRes title: Int,
    @StringRes description: Int,
    onClick: () -> Unit,
) {
    KimiMenuRow(
        icon = icon,
        title = stringResource(title),
        value = stringResource(description),
        onClick = onClick,
    )
}

@Composable
internal fun ComplianceDocumentScreen(pageId: String) {
    val document = complianceDocument(pageId)
    KimiCardBox {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(document.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(stringResource(document.title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.compliance_effective_date), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
            }
        }
        SelectionContainer {
            Text(stringResource(document.summary), color = KimiMuted, style = MaterialTheme.typography.bodySmall)
        }
    }
    document.sections.forEach { section ->
        KimiSectionLabel(stringResource(section.title))
        KimiCardBox {
            SelectionContainer {
                Text(
                    text = stringResource(section.body),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

private fun complianceDocument(pageId: String): ComplianceDocument = when (pageId) {
    CompliancePageIds.USER_AGREEMENT -> ComplianceDocument(
        icon = Icons.Default.Gavel,
        title = R.string.compliance_user_agreement,
        summary = R.string.compliance_user_summary,
        sections = listOf(
            ComplianceSection(R.string.compliance_user_scope_title, R.string.compliance_user_scope_body),
            ComplianceSection(R.string.compliance_user_service_title, R.string.compliance_user_service_body),
            ComplianceSection(R.string.compliance_user_rules_title, R.string.compliance_user_rules_body),
            ComplianceSection(R.string.compliance_user_ai_risk_title, R.string.compliance_user_ai_risk_body),
            ComplianceSection(R.string.compliance_user_third_party_title, R.string.compliance_user_third_party_body),
            ComplianceSection(R.string.compliance_user_disclaimer_title, R.string.compliance_user_disclaimer_body),
            ComplianceSection(R.string.compliance_user_device_title, R.string.compliance_user_device_body),
            ComplianceSection(R.string.compliance_user_contact_title, R.string.compliance_user_contact_body),
        ),
    )
    CompliancePageIds.PRIVACY_POLICY -> ComplianceDocument(
        icon = Icons.Default.PrivacyTip,
        title = R.string.compliance_privacy_policy,
        summary = R.string.compliance_privacy_summary,
        sections = listOf(
            ComplianceSection(R.string.compliance_privacy_principles_title, R.string.compliance_privacy_principles_body),
            ComplianceSection(R.string.compliance_privacy_process_title, R.string.compliance_privacy_process_body),
            ComplianceSection(R.string.compliance_privacy_external_title, R.string.compliance_privacy_external_body),
            ComplianceSection(R.string.compliance_privacy_storage_title, R.string.compliance_privacy_storage_body),
            ComplianceSection(R.string.compliance_privacy_security_title, R.string.compliance_privacy_security_body),
            ComplianceSection(R.string.compliance_privacy_device_title, R.string.compliance_privacy_device_body),
            ComplianceSection(R.string.compliance_privacy_rights_title, R.string.compliance_privacy_rights_body),
            ComplianceSection(R.string.compliance_privacy_children_title, R.string.compliance_privacy_children_body),
            ComplianceSection(R.string.compliance_privacy_changes_title, R.string.compliance_privacy_changes_body),
        ),
    )
    CompliancePageIds.PERSONAL_INFO -> ComplianceDocument(
        icon = Icons.Default.DataObject,
        title = R.string.compliance_personal_info_list,
        summary = R.string.compliance_personal_summary,
        sections = listOf(
            ComplianceSection(R.string.compliance_personal_optional_profile_title, R.string.compliance_personal_optional_profile_body),
            ComplianceSection(R.string.compliance_personal_chat_title, R.string.compliance_personal_chat_body),
            ComplianceSection(R.string.compliance_personal_credentials_title, R.string.compliance_personal_credentials_body),
            ComplianceSection(R.string.compliance_personal_files_title, R.string.compliance_personal_files_body),
            ComplianceSection(R.string.compliance_personal_device_title, R.string.compliance_personal_device_body),
            ComplianceSection(R.string.compliance_personal_location_apps_title, R.string.compliance_personal_location_apps_body),
            ComplianceSection(R.string.compliance_personal_logs_title, R.string.compliance_personal_logs_body),
            ComplianceSection(R.string.compliance_personal_screen_title, R.string.compliance_personal_screen_body),
            ComplianceSection(R.string.compliance_personal_not_collected_title, R.string.compliance_personal_not_collected_body),
        ),
    )
    CompliancePageIds.THIRD_PARTY -> ComplianceDocument(
        icon = Icons.Default.Hub,
        title = R.string.compliance_third_party_list,
        summary = R.string.compliance_third_party_summary,
        sections = listOf(
            ComplianceSection(R.string.compliance_third_sdk_title, R.string.compliance_third_sdk_body),
            ComplianceSection(R.string.compliance_third_models_title, R.string.compliance_third_models_body),
            ComplianceSection(R.string.compliance_third_web_title, R.string.compliance_third_web_body),
            ComplianceSection(R.string.compliance_third_mcp_title, R.string.compliance_third_mcp_body),
            ComplianceSection(R.string.compliance_third_remote_title, R.string.compliance_third_remote_body),
            ComplianceSection(R.string.compliance_third_update_title, R.string.compliance_third_update_body),
            ComplianceSection(R.string.compliance_third_pets_title, R.string.compliance_third_pets_body),
            ComplianceSection(R.string.compliance_third_external_links_title, R.string.compliance_third_external_links_body),
        ),
    )
    else -> ComplianceDocument(
        icon = Icons.Default.AdminPanelSettings,
        title = R.string.compliance_app_permissions,
        summary = R.string.compliance_permissions_summary,
        sections = listOf(
            ComplianceSection(R.string.compliance_permission_network_title, R.string.compliance_permission_network_body),
            ComplianceSection(R.string.compliance_permission_location_title, R.string.compliance_permission_location_body),
            ComplianceSection(R.string.compliance_permission_camera_title, R.string.compliance_permission_camera_body),
            ComplianceSection(R.string.compliance_permission_media_title, R.string.compliance_permission_media_body),
            ComplianceSection(R.string.compliance_permission_apps_title, R.string.compliance_permission_apps_body),
            ComplianceSection(R.string.compliance_permission_notifications_title, R.string.compliance_permission_notifications_body),
            ComplianceSection(R.string.compliance_permission_install_title, R.string.compliance_permission_install_body),
            ComplianceSection(R.string.compliance_permission_termux_title, R.string.compliance_permission_termux_body),
            ComplianceSection(R.string.compliance_permission_overlay_title, R.string.compliance_permission_overlay_body),
            ComplianceSection(R.string.compliance_permission_accessibility_title, R.string.compliance_permission_accessibility_body),
            ComplianceSection(R.string.compliance_permission_control_title, R.string.compliance_permission_control_body),
        ),
    )
}
