package com.yukisoffd.lyracode

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.yukisoffd.lyracode.data.AppSettings
import com.yukisoffd.lyracode.data.DeepSeekHarnessBalanceNotifications
import com.yukisoffd.lyracode.data.DeepSeekHarnessProtocol
import com.yukisoffd.lyracode.data.HarnessAuthExpired
import com.yukisoffd.lyracode.data.HarnessBalanceAlert
import com.yukisoffd.lyracode.data.HarnessCurrency
import com.yukisoffd.lyracode.data.HarnessInvalidAuthorizationPayload
import com.yukisoffd.lyracode.data.HarnessInvalidBrowserDestination
import com.yukisoffd.lyracode.data.HarnessPlatformException
import com.yukisoffd.lyracode.data.HarnessPlatformFailure
import com.yukisoffd.lyracode.data.HarnessSignInTimedOut
import com.yukisoffd.lyracode.data.formatHarnessAmount
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.net.BindException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

@Composable
internal fun DeepSeekHarnessAccountSection(settings: AppSettings, onOpen: () -> Unit) {
    val account by settings.deepSeekHarnessAccount.view.collectAsState()
    KimiMenuRow(
        leading = { AiLogoBadge(R.drawable.ai_service_deepseek, DeepSeekHarnessProtocol.NAME) },
        title = DeepSeekHarnessProtocol.NAME,
        value = account.user?.let { it.name.ifBlank { it.contact } }
            ?: uiText(if (account.profileId == null) R.string.harness_sign_in else R.string.harness_signed_in),
        onClick = onOpen,
    )
}

@Composable
internal fun DeepSeekHarnessAccountPage(settings: AppSettings, onServicesChanged: () -> Unit) {
    val client = settings.deepSeekHarnessAccount
    val account by client.view.collectAsState()
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    val dark = LocalDarkTheme.current
    val locale = context.resources.configuration.locales[0]
    val profileId = account.profileId
    val notifications = remember(context) { DeepSeekHarnessBalanceNotifications(context) }
    var permissionRevision by remember { mutableIntStateOf(0) }
    val canNotify = remember(permissionRevision) { notifications.available() }
    var action by remember { mutableStateOf<Job?>(null) }
    var waitingForBrowser by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var balanceFailed by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        permissionRevision++
        val id = client.view.value.profileId
        if (it && id != null) scope.launch { withContext(Dispatchers.IO) { runCatching { client.refreshBalance(id) } } }
    }

    fun refreshModelService(id: String, select: Boolean = false) {
        val models = client.fetchModels(id)
        val profiles = settings.profiles()
        val existing = profiles.firstOrNull { it.id == id } ?: throw HarnessAuthExpired()
        val retained = existing.enabledModels.filter { it in models }
        val enabled = if (existing.enabledModels == existing.savedModels) models else retained.ifEmpty { models.take(1) }
        val updated = existing.copy(savedModels = models, enabledModels = enabled,
            selectedModel = existing.selectedModel.takeIf { it in enabled } ?: enabled.first())
        settings.saveProfiles(profiles.map { if (it.id == id) updated else it },
            if (select) id else settings.selectedApiProfileId)
    }

    fun runAction(block: suspend () -> Unit) {
        if (action?.isActive == true) return
        message = null
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try { block() }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { message = harnessAccountErrorMessage(error) }
            finally {
                waitingForBrowser = false
                action = null
            }
        }
        action = job
        job.start()
    }

    suspend fun refreshBalance(id: String, maxAgeMillis: Long = 0) {
        balanceFailed = withContext(Dispatchers.IO) {
            try { client.refreshBalance(id, maxAgeMillis); false }
            catch (_: HarnessAuthExpired) { false }
            catch (_: Exception) { true }
        }
    }

    LaunchedEffect(profileId, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            permissionRevision++
            if (profileId != null) {
                refreshBalance(profileId, 10_000L)
                withContext(Dispatchers.IO) { runCatching { client.refreshUser(profileId) } }
            }
        }
    }

    KimiCardBox {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            AiLogoBadge(R.drawable.ai_service_deepseek, DeepSeekHarnessProtocol.NAME)
            Text(account.user?.name?.ifBlank { DeepSeekHarnessProtocol.NAME } ?: DeepSeekHarnessProtocol.NAME,
                style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
            account.user?.contact?.takeIf(String::isNotBlank)?.let {
                Text(it, color = KimiMuted, textAlign = TextAlign.Center)
            }
        }
        if (profileId == null) {
            Text(uiText(R.string.harness_sign_in_description), Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
            Button(modifier = Modifier.fillMaxWidth(), enabled = action == null, onClick = {
                val selected = settings.selectedProfile()
                val select = !selected.usesAccountCredential && selected.allApiKeys.isEmpty()
                runAction {
                    val id = client.signIn(dark) { url ->
                        uriHandler.openUri(url)
                        waitingForBrowser = true
                    }
                    waitingForBrowser = false
                    if (select) settings.selectedApiProfileId = id
                    onServicesChanged()
                    try { withContext(Dispatchers.IO) { refreshModelService(id) } }
                    catch (error: CancellationException) { throw error }
                    catch (error: HarnessAuthExpired) { throw error }
                    catch (_: Exception) { message = uiText(R.string.harness_model_list_failed) }
                    finally { onServicesChanged() }
                }
            }) { Text(uiText(R.string.harness_sign_in), textAlign = TextAlign.Center) }
            if (waitingForBrowser) {
                Text(uiText(R.string.harness_waiting_browser), Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                TextButton(modifier = Modifier.fillMaxWidth(), onClick = { action?.cancel() }) { Text(uiText(R.string.action_cancel)) }
            }
        }
    }
    if (action != null) LinearProgressIndicator(Modifier.fillMaxWidth())
    message?.let { Text(it, Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center) }

    if (profileId != null) {
        KimiCardBox {
            Text(uiText(R.string.harness_recharge_balance), Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            val balance = account.balance
            if (balance != null && balance.recharge.isNotEmpty()) {
                balance.recharge.forEach { (currency, amount) ->
                    Text(formatHarnessAmount(amount, currency, locale), Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
                }
            } else {
                val label = if (balanceFailed || balance != null) R.string.harness_balance_unavailable else R.string.harness_balance_loading
                Text(uiText(label), Modifier.fillMaxWidth(), color = KimiMuted, textAlign = TextAlign.Center)
            }
            balance?.bonus?.filterValues { it.signum() > 0 }?.forEach { (currency, amount) ->
                Text(uiText(R.string.harness_bonus_balance, formatHarnessAmount(amount, currency, locale)),
                    Modifier.fillMaxWidth(), color = KimiMuted, textAlign = TextAlign.Center)
            }
            if (balanceFailed && balance != null) Text(uiText(R.string.harness_balance_stale), Modifier.fillMaxWidth(),
                color = KimiMuted, textAlign = TextAlign.Center)
            OutlinedButton(modifier = Modifier.fillMaxWidth(), enabled = action == null, onClick = { runAction { refreshBalance(profileId) } }) {
                Text(uiText(R.string.harness_refresh_balance))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(modifier = Modifier.weight(1f), onClick = { runAction { uriHandler.openUri(DeepSeekHarnessProtocol.PLATFORM + "/usage") } }) {
                    Text(uiText(R.string.harness_usage), textAlign = TextAlign.Center)
                }
                TextButton(modifier = Modifier.weight(1f), onClick = { runAction { uriHandler.openUri(DeepSeekHarnessProtocol.PLATFORM + "/top_up") } }) {
                    Text(uiText(R.string.harness_top_up), textAlign = TextAlign.Center)
                }
            }
        }
        HarnessBalanceAlertSettings(profileId, account.alert, account.balance?.recharge?.keys.orEmpty(), action == null,
            onSave = { currency, threshold ->
                runAction {
                    withContext(Dispatchers.IO) { client.configureAlert(profileId, currency, threshold) }
                    if (threshold != null) {
                        if (Build.VERSION.SDK_INT >= 33 &&
                            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                        ) permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        refreshBalance(profileId)
                    }
                }
            },
        )
        if (account.alert != null && !canNotify) {
            Text(uiText(R.string.harness_notification_permission), Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall,
                color = KimiMuted, textAlign = TextAlign.Center)
            TextButton(modifier = Modifier.fillMaxWidth(), onClick = {
                runAction {
                    context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                }
            }) { Text(uiText(R.string.harness_notification_settings)) }
        }
        OutlinedButton(modifier = Modifier.fillMaxWidth(), enabled = action == null, onClick = { runAction {
            withContext(Dispatchers.IO) { refreshModelService(profileId) }
            onServicesChanged()
        } }) { Text(uiText(R.string.refresh_model_list)) }
        TextButton(modifier = Modifier.fillMaxWidth(), enabled = action == null, onClick = { runAction {
            try { withContext(Dispatchers.IO) { client.signOut() } }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) {
                message = if (client.view.value.profileId == null) uiText(R.string.harness_sign_out_failed)
                else uiText(R.string.harness_request_failed)
            }
            finally { onServicesChanged() }
        } }) { Text(uiText(R.string.new_api_logout), color = MaterialTheme.colorScheme.error) }
    }
}

private fun harnessAccountErrorMessage(error: Exception): String = when (error) {
    is HarnessSignInTimedOut -> uiText(R.string.harness_sign_in_timeout)
    is HarnessAuthExpired -> uiText(R.string.harness_session_expired)
    is HarnessInvalidBrowserDestination -> uiText(R.string.harness_invalid_browser_destination)
    is HarnessInvalidAuthorizationPayload -> uiText(R.string.harness_invalid_authorization_payload)
    is ActivityNotFoundException -> uiText(R.string.harness_browser_unavailable)
    is BindException -> uiText(R.string.harness_callback_unavailable)
    is ConnectException, is UnknownHostException, is SocketTimeoutException, is SSLException ->
        uiText(R.string.harness_platform_connection_failed)
    is HarnessPlatformException -> {
        Log.w("DeepSeekHarness", "Platform response: ${error.failure} HTTP ${error.status}, code=${error.platformCode}, bizCode=${error.businessCode}")
        when (error.failure) {
            HarnessPlatformFailure.RATE_LIMITED -> uiText(R.string.harness_platform_rate_limited)
            HarnessPlatformFailure.BLOCKED -> uiText(R.string.harness_platform_blocked, error.status)
            HarnessPlatformFailure.HTTP_ERROR -> uiText(R.string.harness_platform_http_error, error.status)
            HarnessPlatformFailure.INVALID_RESPONSE -> uiText(R.string.harness_platform_invalid_response, error.status)
            HarnessPlatformFailure.REJECTED -> uiText(R.string.harness_platform_rejected, error.businessCode ?: error.platformCode ?: error.status)
        }
    }
    else -> uiText(R.string.harness_request_failed)
}

@Composable
private fun HarnessBalanceAlertSettings(
    profileId: String,
    alert: HarnessBalanceAlert?,
    walletCurrencies: Set<HarnessCurrency>,
    enabled: Boolean,
    onSave: (HarnessCurrency?, BigDecimal?) -> Unit,
) {
    var editing by remember(profileId, alert?.threshold, alert?.currency) { mutableStateOf(alert != null) }
    var threshold by remember(profileId, alert?.threshold) { mutableStateOf(alert?.threshold?.toPlainString().orEmpty()) }
    var selectedCurrency by remember(profileId, alert?.currency) {
        mutableStateOf<HarnessCurrency?>(alert?.currency)
    }
    val currency = selectedCurrency ?: walletCurrencies.firstOrNull() ?: HarnessCurrency.CNY
    val parsed = threshold.trim().replace(',', '.').takeIf { it.matches(Regex("[0-9]{0,9}(?:\\.[0-9]{1,6})?")) }
        ?.toBigDecimalOrNull()?.takeIf { it.signum() > 0 }
    KimiCardBox {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) {
            Text(uiText(R.string.harness_balance_alert), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Switch(checked = editing, enabled = enabled, onCheckedChange = {
                editing = it
                if (!it && alert != null) onSave(null, null)
            })
        }
        if (editing) {
            Text(uiText(R.string.harness_balance_alert_hint), Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall,
                color = KimiMuted, textAlign = TextAlign.Center)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
                HarnessCurrency.entries.forEach {
                    FilterChip(selected = currency == it, enabled = enabled, onClick = { selectedCurrency = it }, label = { Text(it.name) })
                }
            }
            OutlinedTextField(threshold, { threshold = it }, enabled = enabled, singleLine = true,
                modifier = Modifier.fillMaxWidth(), label = { Text(uiText(R.string.harness_alert_threshold)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                isError = threshold.isNotBlank() && parsed == null)
            Button(modifier = Modifier.fillMaxWidth(), enabled = enabled && parsed != null &&
                (alert?.currency != currency || alert.threshold.compareTo(parsed) != 0),
                onClick = { if (parsed != null) onSave(currency, parsed) }) { Text(uiText(R.string.file_editor_save)) }
        }
    }
}
