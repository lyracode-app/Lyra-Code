package com.yukisoffd.lyracode

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yukisoffd.lyracode.data.ApiProfile
import com.yukisoffd.lyracode.data.AppSettings
import com.yukisoffd.lyracode.data.ChatGptAccount
import com.yukisoffd.lyracode.data.ChatGptCodexProtocol
import com.yukisoffd.lyracode.data.ChatGptLoopbackSignIn
import com.yukisoffd.lyracode.data.ChatGptProtocol
import com.yukisoffd.lyracode.data.ChatGptUsageWindow
import com.yukisoffd.lyracode.data.chatGptComposerAllowance
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle

@Composable
internal fun ChatGptAccountSection(settings: AppSettings, onOpen: () -> Unit) {
    val revision by settings.chatGptAccount.accountsRevision.collectAsState()
    val accounts by produceState<List<ChatGptAccount>>(emptyList(), settings, revision) {
        value = withContext(Dispatchers.IO) { runCatching { settings.chatGptAccount.accounts() }.getOrDefault(emptyList()) }
    }
    val activeAccountId = settings.selectedProfile().chatGptAccountId
    val account = accounts.firstOrNull { it.clientId == activeAccountId && it.isLoggedIn } ?: accounts.firstOrNull { it.isLoggedIn }
    Row(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ChatGptLogo(Modifier.size(40.dp))
        Column(Modifier.weight(1f)) {
            Text("ChatGPT", style = MaterialTheme.typography.titleSmall)
            Text(account?.label ?: uiText(R.string.chatgpt_continue),
                style = MaterialTheme.typography.bodySmall, color = KimiMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

internal fun synchronizeChatGptService(settings: AppSettings, account: ChatGptAccount, select: Boolean = true): ApiProfile {
    val models = settings.chatGptAccount.fetchModels(account.clientId)
    val ids = models.map { it.slug }
    val id = "chatgpt:${account.clientId}"
    val existing = settings.profiles().firstOrNull { it.id == id }
    val enabled = if (existing == null) ids else existing.enabledModels.filter { it in ids }.ifEmpty { ids.take(1) }
    val profile = ApiProfile(
        id = id, name = existing?.name ?: "ChatGPT · ${account.email.ifBlank { account.clientId.takeLast(8) }}",
        apiKey = "", baseUrl = if (account.isCodex) ChatGptCodexProtocol.BASE else ChatGptProtocol.RESOURCE, presetId = "openai",
        chatGptAccountId = account.clientId, useResponsesApi = true,
        selectedModel = existing?.selectedModel?.takeIf { it in enabled } ?: enabled.firstOrNull().orEmpty(),
        savedModels = ids, enabledModels = enabled,
        modelDisplayNames = models.associate { it.slug to it.displayName },
    )
    val latest = settings.profiles()
    settings.saveProfiles(if (latest.any { it.id == id }) latest.map { if (it.id == id) profile else it } else latest + profile,
        if (select) id else settings.selectedApiProfileId)
    return profile
}

@Composable
internal fun ChatGptSignInPage(
    settings: AppSettings,
    accountId: String,
    onServicesChanged: () -> Unit,
    onSignedIn: (String, String, Boolean) -> Unit,
) {
    val client = remember(settings) { settings.chatGptAccount }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    var accounts by remember { mutableStateOf<List<ChatGptAccount>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf("") }
    var failed by remember { mutableStateOf(false) }
    var listener by remember { mutableStateOf<ChatGptLoopbackSignIn?>(null) }
    var actionJob by remember { mutableStateOf<Job?>(null) }
    var pendingRegistrationId by rememberSaveable { mutableStateOf("") }
    val selected = accounts.firstOrNull { it.clientId == accountId }
    val canSignIn = loaded && !busy && (accountId.isBlank() || selected != null)
    LaunchedEffect(accountId) {
        try { accounts = withContext(Dispatchers.IO) { client.accounts() } }
        catch (error: CancellationException) { throw error }
        catch (_: Exception) { failed = true; notice = context.getString(R.string.chatgpt_storage_unavailable) }
        finally { loaded = true }
    }
    DisposableEffect(Unit) { onDispose { listener?.close(); actionJob?.cancel() } }

    fun signIn(codex: Boolean = selected?.isCodex == true) {
        if (!canSignIn) return
        busy = true
        failed = false
        notice = ""
        actionJob = scope.launch {
            var created: ChatGptLoopbackSignIn? = null
            try {
                val local = withContext(Dispatchers.IO) { ChatGptLoopbackSignIn(
                    if (codex) ChatGptCodexProtocol.CLIENT_ID else selected?.clientId ?: pendingRegistrationId, codex).also { created = it } }
                listener = local
                val url = withContext(Dispatchers.IO) { ChatGptProtocol.authorizationUrl(local.attempt, client.hostId(), selected) }
                uriHandler.openUri(url.toString())
                notice = context.getString(R.string.chatgpt_waiting_for_browser)
                val callback = withContext(Dispatchers.IO) { local.awaitCallback() }
                val signedIn = withContext(Dispatchers.IO) { client.completeSignIn(local.attempt, callback, selected) }
                pendingRegistrationId = ""
                if (signedIn.isCodex) withContext(Dispatchers.IO) { client.refreshUsageIfStale(signedIn.clientId) }
                var syncFailed = !signedIn.planEnabled
                val result = if (!signedIn.planEnabled) context.getString(R.string.chatgpt_plan_disabled)
                else try {
                    val profile = withContext(Dispatchers.IO) { synchronizeChatGptService(settings, signedIn) }
                    context.getString(if (profile.savedModels.isEmpty()) R.string.notice_no_models_fetched else R.string.chatgpt_signed_in)
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    syncFailed = true
                    context.getString(R.string.chatgpt_signed_in_sync_failed) + "\n" + error.message.orEmpty()
                }
                onSignedIn(signedIn.clientId, result, syncFailed)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                if (!codex && selected == null && created?.attempt?.consumed == true)
                    pendingRegistrationId = created?.attempt?.issuedClientId.orEmpty()
                failed = true
                notice = error.message ?: context.getString(R.string.chatgpt_request_failed)
            } finally {
                created?.close()
                listener = null
                busy = false
                onServicesChanged()
            }
        }
    }

    KimiCardBox {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ChatGptLogo(Modifier.size(42.dp))
            Column {
                Text("ChatGPT", style = MaterialTheme.typography.titleLarge)
                Text(uiText(R.string.chatgpt_provided_by), style = MaterialTheme.typography.bodySmall, color = KimiMuted)
            }
        }
        Text(uiText(R.string.chatgpt_disclosure), style = MaterialTheme.typography.bodySmall)
        Text(uiText(R.string.chatgpt_privacy), style = MaterialTheme.typography.bodySmall, color = KimiMuted)
    }
    if (busy || !loaded) LinearProgressIndicator(Modifier.fillMaxWidth())
    if (notice.isNotBlank()) Text(notice, color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.bodySmall)
    if (listener != null) OutlinedButton(onClick = { listener?.close(); actionJob?.cancel() }, modifier = Modifier.fillMaxWidth()) {
        Text(uiText(R.string.action_cancel))
    }
    if (selected != null) KimiCardBox {
        Text(selected.email.ifBlank { uiText(R.string.chatgpt_email_unavailable) }, style = MaterialTheme.typography.titleMedium)
        Text(uiText(if (selected.isCodex) R.string.chatgpt_mode_codex else R.string.chatgpt_mode_official), color = KimiMuted, style = MaterialTheme.typography.bodySmall)
        if (selected.isCodex) Text(uiText(R.string.chatgpt_codex_disclosure), style = MaterialTheme.typography.bodySmall, color = KimiMuted)
        ChatGptSignInButton(enabled = canSignIn, codex = selected.isCodex) { signIn() }
    } else {
        KimiCardBox {
            Text(uiText(R.string.chatgpt_mode_official), style = MaterialTheme.typography.titleSmall)
            ChatGptSignInButton(enabled = canSignIn) { signIn(codex = false) }
        }
        KimiCardBox {
            Text(uiText(R.string.chatgpt_mode_codex), style = MaterialTheme.typography.titleSmall)
            Text(uiText(R.string.chatgpt_codex_disclosure), style = MaterialTheme.typography.bodySmall, color = KimiMuted)
            ChatGptSignInButton(enabled = canSignIn, codex = true) { signIn(codex = true) }
        }
    }
}
@Composable
private fun ChatGptSignInButton(enabled: Boolean, codex: Boolean = false, onClick: () -> Unit) {
    Button(enabled = enabled, onClick = onClick, modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(containerColor = Color.Black, contentColor = Color.White)) {
        ChatGptLogo(Modifier.padding(end = 8.dp).size(24.dp), Color.White)
        Text(uiText(if (codex) R.string.chatgpt_continue_codex else R.string.chatgpt_continue))
    }
}

@Composable
internal fun ChatGptLogo(modifier: Modifier, tint: Color = MaterialTheme.colorScheme.onSurface) {
    // Reuse the OpenAI artwork sourced from aisvg/services/openai.svg.
    Icon(painterResource(R.drawable.ai_service_openai), "ChatGPT", modifier, tint = tint)
}

@Composable
internal fun chatGptWindowLabel(minutes: Int): String = when (minutes) {
    300 -> uiText(R.string.chatgpt_five_hour_remaining)
    10080 -> uiText(R.string.chatgpt_weekly_remaining)
    else -> uiText(R.string.chatgpt_window_remaining, minutes)
}

@Composable
internal fun ChatGptUsageDialog(settings: AppSettings, accountId: String) {
    val client = remember(settings) { settings.chatGptAccount }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(accountId, lifecycle) {
        if (!accountId.startsWith("codex:")) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                withContext(Dispatchers.IO) { client.refreshUsageIfStale(accountId) }
                delay(60_000)
            }
        }
    }
    val notice by client.notice.collectAsState()
    val current = notice ?: return
    val uriHandler = LocalUriHandler.current
    AlertDialog(
        onDismissRequest = client::dismissNotice,
        title = { Text(uiText(R.string.chatgpt_limit_title)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("ChatGPT · ${current.clientId.takeLast(8)}", style = MaterialTheme.typography.titleSmall)
            Text(uiText(R.string.chatgpt_limit_message))
            current.windowMinutes?.let { Text(chatGptWindowLabel(it)) }
            current.remainingPercent?.let { Text(uiText(R.string.chatgpt_remaining_percent, String.format(Locale.getDefault(), "%.1f", it))) }
        } },
        confirmButton = { TextButton(onClick = { runCatching { uriHandler.openUri(ChatGptProtocol.USAGE_URL) }; client.dismissNotice() }) { Text(uiText(R.string.chatgpt_manage_usage)) } },
        dismissButton = { TextButton(onClick = client::dismissNotice) { Text(uiText(R.string.action_close)) } },
    )
}

private data class ChatGptComposerSnapshot(val accountId: String, val account: ChatGptAccount?, val windows: List<ChatGptUsageWindow>)

@Composable
internal fun ChatGptComposerIdentity(controller: ChatController, settings: AppSettings) {
    val profile = controller.profiles.firstOrNull { it.id == controller.activeProfileId.value }
    if (profile?.isChatGpt != true) return
    val accountId = profile.chatGptAccountId
    val client = remember(settings) { settings.chatGptAccount }
    val accountRevision by client.accountsRevision.collectAsState()
    val usageRevision by client.usageRevision.collectAsState()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var now by remember(accountId) { mutableStateOf(System.currentTimeMillis()) }
    val snapshot by produceState<ChatGptComposerSnapshot?>(null, client, accountId, accountRevision, usageRevision) {
        value = withContext(Dispatchers.IO) { runCatching {
            ChatGptComposerSnapshot(accountId, client.accounts().firstOrNull { it.clientId == accountId }, client.usage(accountId))
        }.getOrNull() }
    }
    LaunchedEffect(accountId, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) { now = System.currentTimeMillis(); delay(15_000) }
        }
    }
    val current = snapshot?.takeIf { it.accountId == accountId }
    val allowance = chatGptComposerAllowance(current?.account, current?.windows.orEmpty(), now)
    val percentage = allowance.remainingPercent?.let { String.format(Locale.getDefault(), "%.1f%%", it) } ?: uiText(R.string.chatgpt_not_provided)
    val remaining = uiText(if (allowance.minutes == 10080) R.string.chatgpt_composer_weekly_remaining else R.string.chatgpt_composer_five_hour_remaining, percentage)
    Text("${uiText(R.string.chatgpt_using_plan)} · $remaining", modifier = Modifier.fillMaxWidth(), style = MaterialTheme.typography.labelSmall, color = KimiMuted)
}
