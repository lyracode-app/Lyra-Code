package com.yukisoffd.lyracode

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Kept separate from app settings/imports and deliberately independent of app version. */
internal class FirstUseConsentStore(context: Context) {
    private val preferences = context.getSharedPreferences("first_use_consent", Context.MODE_PRIVATE)
    val isAccepted: Boolean get() = preferences.getBoolean("accepted", false)
    fun accept(): Boolean = preferences.edit().putBoolean("accepted", true).commit()
}

@Composable
internal fun FirstUseConsentScreen(onAccepted: () -> Unit, onDeclined: () -> Unit) {
    val context = LocalContext.current
    val store = remember(context) { FirstUseConsentStore(context) }
    val scope = rememberCoroutineScope()
    var page by rememberSaveable { mutableStateOf<String?>(null) }
    var agreementRead by rememberSaveable { mutableStateOf(false) }
    var privacyRead by rememberSaveable { mutableStateOf(false) }
    var checked by rememberSaveable { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var saveFailed by remember { mutableStateOf(false) }

    BackHandler {
        if (page != null) page = null else if (!saving) onDeclined()
    }
    Surface(Modifier.fillMaxSize()) {
        val currentPage = page
        if (currentPage != null) {
            key(currentPage) {
                ConsentDocumentReader(currentPage, onBack = { page = null }) {
                    if (currentPage == CompliancePageIds.USER_AGREEMENT) agreementRead = true
                    else privacyRead = true
                    page = null
                }
            }
        } else {
            AlertDialog(
                onDismissRequest = { if (!saving) onDeclined() },
                title = { Text(stringResource(R.string.consent_title)) },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        Text(stringResource(R.string.consent_intro))
                        TextButton(onClick = { page = CompliancePageIds.USER_AGREEMENT }, enabled = !saving) {
                            Text(stringResource(R.string.compliance_user_agreement) + if (agreementRead) " ✓" else "")
                        }
                        TextButton(onClick = { page = CompliancePageIds.PRIVACY_POLICY }, enabled = !saving) {
                            Text(stringResource(R.string.compliance_privacy_policy) + if (privacyRead) " ✓" else "")
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked, onCheckedChange = { checked = it }, enabled = agreementRead && privacyRead && !saving)
                            Text(stringResource(R.string.consent_checkbox))
                        }
                        if (saveFailed) Text(stringResource(R.string.consent_save_failed), color = MaterialTheme.colorScheme.error)
                    }
                },
                confirmButton = {
                    TextButton(enabled = agreementRead && privacyRead && checked && !saving, onClick = {
                        saving = true
                        scope.launch {
                            val saved = withContext(Dispatchers.IO) { runCatching { store.accept() }.getOrDefault(false) }
                            if (saved) onAccepted() else {
                                saveFailed = true
                                saving = false
                            }
                        }
                    }) { Text(stringResource(R.string.consent_continue)) }
                },
                dismissButton = {
                    TextButton(onClick = onDeclined, enabled = !saving) { Text(stringResource(R.string.consent_decline)) }
                },
                properties = androidx.compose.ui.window.DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = false),
            )
        }
    }
}

@Composable
private fun ConsentDocumentReader(page: String, onBack: () -> Unit, onRead: () -> Unit) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var resumed by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    var remaining by rememberSaveable { mutableIntStateOf(10) }
    val scroll = rememberScrollState()
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ -> resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(resumed) {
        if (resumed) while (remaining > 0) {
            delay(1_000)
            remaining--
        }
    }
    Column(Modifier.fillMaxSize().systemBarsPadding().padding(16.dp)) {
        TextButton(onClick = onBack) { Text(stringResource(R.string.consent_back)) }
        Column(Modifier.weight(1f).verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ComplianceDocumentScreen(page)
        }
        Text(
            stringResource(if (remaining > 0) R.string.consent_countdown else R.string.consent_scroll_hint, remaining),
            modifier = Modifier.padding(vertical = 8.dp),
            style = MaterialTheme.typography.bodySmall,
        )
        Button(onClick = onRead, enabled = remaining == 0 && !scroll.canScrollForward, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.consent_read_done))
        }
    }
}
