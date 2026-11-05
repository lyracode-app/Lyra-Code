package com.yukisoffd.lyracode

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.ViewModule
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yukisoffd.lyracode.data.AppSettings
import com.yukisoffd.lyracode.data.NewApiAccountClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.NumberFormat

/** Content of a Settings child route; account information is never presented in a dialog. */
@Composable
internal fun MengxiAccountPage(settings: AppSettings, initialNotice: String, onServicesChanged: () -> Unit, onSignedOut: () -> Unit) {
    val account = settings.newApiAccount
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    var revision by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf(initialNotice) }
    var failed by remember { mutableStateOf(false) }
    var showLogin by remember { mutableStateOf(false) }
    val user = remember(revision) { account.user }
    val profile = remember(revision) { settings.profiles().firstOrNull { it.id == account.profileId } }
    val loggedIn = remember(revision) { account.isLoggedIn }
    val syncedText = uiText(R.string.new_api_synced)
    val partialText = uiText(R.string.new_api_partial_sync)
    val refreshedText = uiText(R.string.new_api_user_refreshed)
    val errorText = uiText(R.string.new_api_request_failed)

    fun runAction(signOut: Boolean = false, action: () -> String) {
        if (busy) return
        busy = true
        notice = ""
        failed = false
        scope.launch {
            try { notice = withContext(Dispatchers.IO) { action() } }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { failed = true; notice = error.message ?: errorText }
            finally {
                busy = false
                revision++
                onServicesChanged()
            }
            if (signOut && !account.isLoggedIn) onSignedOut()
        }
    }
    LaunchedEffect(account) {
        if (account.isLoggedIn || account.savedLogin?.autoLogin == true) {
            val entryNotice = notice
            runAction { account.refreshUser(); entryNotice }
        }
    }
    if (showLogin) NewApiLoginDialog(settings, onServicesChanged,
        onAuthenticated = { message -> showLogin = false; notice = message; failed = false; revision++ },
        onDismiss = { showLogin = false })

    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    if (notice.isNotBlank()) Surface(
        shape = RoundedCornerShape(18.dp),
        color = if (failed) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(if (failed) Icons.Default.ErrorOutline else Icons.Default.CheckCircle, null, Modifier.size(20.dp))
            Text(notice, style = MaterialTheme.typography.bodySmall)
        }
    }
    if (!loggedIn || user == null) {
        KimiCardBox {
            Icon(Icons.Default.Cloud, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
            Text(uiText(R.string.mengxi_not_signed_in), style = MaterialTheme.typography.titleMedium)
            Button(enabled = !busy, onClick = { showLogin = true }) { Text(uiText(R.string.new_api_sign_in_register)) }
        }
    } else {
        val username = user.optString("username")
        val displayName = user.optString("display_name").ifBlank { username }
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp))
                .background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.tertiaryContainer)))
                .padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Box(Modifier.size(64.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)),
                    contentAlignment = Alignment.Center) {
                    Text(displayName.take(1).uppercase(), style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(displayName, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    Text("@$username", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(Icons.Default.CheckCircle, uiText(R.string.mengxi_connected), tint = MaterialTheme.colorScheme.primary)
            }
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AccountPill("ID ${user.optString("id")}")
                user.optString("group").takeIf { it.isNotBlank() }?.let { AccountPill(it) }
                user.optString("email").takeIf { it.isNotBlank() }?.let { email ->
                    SelectionContainer(Modifier.weight(1f)) {
                        Text(email, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.primary) {
            Column(Modifier.fillMaxWidth().padding(22.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.AccountBalanceWallet, null, Modifier.size(20.dp))
                    Text(uiText(R.string.new_api_quota), style = MaterialTheme.typography.labelLarge)
                }
                AccountQuotaText(uiText(R.string.mengxi_coin_amount, formatMengxiQuota(user.optLong("quota"))))
                Text(uiText(R.string.mengxi_quota_units, NumberFormat.getIntegerInstance().format(user.optLong("quota"))),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AccountMetric(uiText(R.string.new_api_used_quota),
                uiText(R.string.mengxi_coin_amount, formatMengxiQuota(user.optLong("used_quota"))), Modifier.weight(1f))
            AccountMetric(uiText(R.string.new_api_request_count), NumberFormat.getIntegerInstance().format(user.optLong("request_count")), Modifier.weight(1f))
        }
        KimiSectionLabel(uiText(R.string.section_model_service))
        KimiCardBox {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Key, null, tint = MaterialTheme.colorScheme.primary)
                    Text(uiText(R.string.mengxi_keys_count, profile?.allApiKeys?.size ?: 0), style = MaterialTheme.typography.bodyMedium)
                }
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.ViewModule, null, tint = MaterialTheme.colorScheme.primary)
                    Text(uiText(R.string.label_models_count, profile?.savedModels?.size ?: 0), style = MaterialTheme.typography.bodyMedium)
                }
            }
            Button(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = { runAction {
                val result = syncNewApiAccountServices(settings)
                if (result.warnings.isEmpty()) syncedText else "$partialText\n${result.warnings.joinToString("\n")}"
            } }) {
                Icon(Icons.Default.Sync, null, Modifier.padding(end = 8.dp).size(18.dp))
                Text(uiText(R.string.new_api_sync))
            }
        }
        FilledTonalButton(enabled = !busy, onClick = { runAction { account.refreshUser(); refreshedText } }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Refresh, null, Modifier.padding(end = 8.dp).size(18.dp))
            Text(uiText(R.string.new_api_refresh_user))
        }
    }
    OutlinedButton(onClick = {
        runCatching { uriHandler.openUri(NewApiAccountClient.PLATFORM_URL) }
            .onFailure { failed = true; notice = it.message ?: errorText }
    }, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Default.OpenInNew, null, Modifier.padding(end = 8.dp).size(18.dp))
        Text(uiText(R.string.mengxi_open_website))
    }
    if (loggedIn) {
        TextButton(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = { runAction(signOut = true) { account.logout(); "" } }) {
            Icon(Icons.Default.Logout, null, Modifier.padding(end = 8.dp).size(18.dp), tint = MaterialTheme.colorScheme.error)
            Text(uiText(R.string.new_api_logout), color = MaterialTheme.colorScheme.error)
        }
        Text(uiText(R.string.new_api_logout_hint), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun AccountQuotaText(value: String) {
    val style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold)
    val textMeasurer = rememberTextMeasurer()
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val textWidth = textMeasurer.measure(value, style, softWrap = false, maxLines = 1).size.width
        val scale = if (textWidth > 0) minOf(1f, constraints.maxWidth.toFloat() / textWidth) else 1f
        Text(value, style = style.copy(fontSize = style.fontSize * scale), maxLines = 1, softWrap = false)
    }
}

@Composable
private fun AccountPill(value: String) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surface.copy(alpha = 0.65f)) {
        Text(value, Modifier.padding(horizontal = 12.dp, vertical = 5.dp), style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun AccountMetric(label: String, value: String, modifier: Modifier) {
    KimiCardBox(modifier) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
    }
}

private fun formatMengxiQuota(quota: Long): String = NumberFormat.getNumberInstance().apply {
    minimumFractionDigits = 2
    maximumFractionDigits = 2
}.format(quota / 500_000.0)
