package com.yukisoffd.lyracode

import androidx.compose.material.icons.filled.Cloud
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.yukisoffd.lyracode.data.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun NewApiAccountSection(settings: AppSettings, onServicesChanged: () -> Unit, onOpenAccount: (String) -> Unit) {
    var showLogin by remember { mutableStateOf(false) }
    val account = settings.newApiAccount
    KimiMenuRow(
        icon = androidx.compose.material.icons.Icons.Default.Cloud,
        title = uiText(R.string.mengxi_platform_name),
        value = if (account.isLoggedIn) {
            account.user?.optString("display_name")?.ifBlank { account.user?.optString("username").orEmpty() }.orEmpty()
        } else uiText(R.string.new_api_sign_in_register),
        onClick = { if (account.isLoggedIn) onOpenAccount("") else showLogin = true },
    )
    if (showLogin) NewApiLoginDialog(settings, onServicesChanged,
        onAuthenticated = { notice -> showLogin = false; onOpenAccount(notice) },
        onDismiss = { showLogin = false })
}

internal fun syncNewApiAccountServices(settings: AppSettings): com.yukisoffd.lyracode.data.NewApiSyncResult {
    val account = settings.newApiAccount
    val existing = settings.profiles().firstOrNull { it.id == account.profileId }
    return account.synchronizeService(existing) { profile ->
        val latest = settings.profiles()
        val merged = if (latest.any { it.id == profile.id }) latest.map { if (it.id == profile.id) profile else it } else latest + profile
        val selected = settings.selectedProfile()
        settings.saveProfiles(merged, if (selected.allApiKeys.isEmpty() && !selected.isChatGpt) profile.id else settings.selectedApiProfileId)
    }
}

@Composable
internal fun NewApiLoginDialog(settings: AppSettings, onServicesChanged: () -> Unit, onAuthenticated: (String) -> Unit, onDismiss: () -> Unit) {
    val account = settings.newApiAccount
    val scope = rememberCoroutineScope()
    var registering by remember { mutableStateOf(false) }
    val savedLogin = remember { account.savedLogin }
    var username by remember { mutableStateOf(savedLogin?.username.orEmpty()) }
    var password by remember { mutableStateOf(savedLogin?.password.orEmpty()) }
    var rememberCredentials by remember { mutableStateOf(savedLogin != null) }
    var autoLogin by remember { mutableStateOf(savedLogin?.autoLogin == true) }
    var email by remember { mutableStateOf("") }
    var verification by remember { mutableStateOf("") }
    var invitation by remember { mutableStateOf("") }
    var flowToken by remember { mutableStateOf<String?>(null) }
    var twoFactor by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var failed by remember { mutableStateOf(false) }
    val syncedText = uiText(R.string.new_api_synced)
    val partialText = uiText(R.string.new_api_partial_sync)
    val sentText = uiText(R.string.new_api_code_sent)
    val registeredText = uiText(R.string.new_api_registered)
    val errorText = uiText(R.string.new_api_request_failed)
    fun runAction(completeSignIn: Boolean = false, action: () -> String) {
        if (busy) return
        busy = true
        status = ""
        failed = false
        scope.launch {
            try { status = withContext(Dispatchers.IO) { action() } }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { failed = true; status = error.message ?: errorText }
            finally {
                busy = false
                onServicesChanged()
            }
            if (completeSignIn && account.isLoggedIn) onAuthenticated(status)
        }
    }
    fun synchronize(): String {
        val result = syncNewApiAccountServices(settings)
        return if (result.warnings.isEmpty()) syncedText else "$partialText\n${result.warnings.joinToString("\n")}"
    }
    fun afterLogin(): String {
        // Login remains successful even if provisioning fails; synchronization can be resumed explicitly.
        return try { synchronize() } catch (error: Exception) { "$partialText\n${error.message ?: errorText}" }
    }
    AlertDialog(
        onDismissRequest = { if (!busy) { account.cancelPendingLogin(); onDismiss() } },
        title = { Text(uiText(R.string.new_api_account)) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("api.huanyan.fun", style = MaterialTheme.typography.labelLarge)
                Text(uiText(R.string.new_api_privacy), style = MaterialTheme.typography.bodySmall)
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (flowToken != null) {
                    Text(uiText(R.string.new_api_2fa_hint))
                    OutlinedTextField(twoFactor, { twoFactor = it }, enabled = !busy, label = { Text(uiText(R.string.new_api_code)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
                    Button(enabled = !busy && twoFactor.isNotBlank(), onClick = { runAction(completeSignIn = true) {
                        account.verifyTwoFactor(flowToken!!, twoFactor)
                        scope.launch { flowToken = null; twoFactor = "" }
                        afterLogin()
                    } }) { Text(uiText(R.string.new_api_login)) }
                    TextButton(enabled = !busy, onClick = { account.cancelPendingLogin(); flowToken = null; twoFactor = "" }) { Text(uiText(R.string.new_api_back_to_login)) }
                } else {
                    OutlinedTextField(username, { username = it }, enabled = !busy, modifier = Modifier.fillMaxWidth(),
                        label = { Text(uiText(R.string.new_api_username)) }, singleLine = true)
                    OutlinedTextField(password, { password = it }, enabled = !busy, modifier = Modifier.fillMaxWidth(),
                        label = { Text(uiText(R.string.new_api_password)) }, visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), singleLine = true)
                    NewApiLoginOption(
                        label = uiText(R.string.new_api_remember_credentials),
                        checked = rememberCredentials,
                        enabled = !busy && account.canRememberCredentials,
                    ) {
                        rememberCredentials = it
                        if (!it) autoLogin = false
                    }
                    NewApiLoginOption(
                        label = uiText(R.string.new_api_auto_login),
                        checked = autoLogin,
                        enabled = !busy && rememberCredentials,
                        onCheckedChange = { autoLogin = it },
                    )
                    Text(
                        uiText(if (account.canRememberCredentials) R.string.new_api_saved_login_hint else R.string.new_api_secure_storage_unavailable),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (registering) {
                        OutlinedTextField(email, { email = it }, enabled = !busy, modifier = Modifier.fillMaxWidth(),
                            label = { Text(uiText(R.string.new_api_email)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), singleLine = true)
                        OutlinedTextField(verification, { verification = it }, enabled = !busy, modifier = Modifier.fillMaxWidth(),
                            label = { Text(uiText(R.string.new_api_code)) }, singleLine = true)
                        TextButton(enabled = !busy && email.isNotBlank(), onClick = { runAction { account.sendVerification(email); sentText } }) {
                            Text(uiText(R.string.new_api_send_code))
                        }
                        OutlinedTextField(invitation, { invitation = it }, enabled = !busy, modifier = Modifier.fillMaxWidth(),
                            label = { Text(uiText(R.string.new_api_invitation)) }, singleLine = true)
                    }
                    Button(enabled = !busy && username.isNotBlank() && password.isNotBlank() &&
                        (!registering || (email.isNotBlank() && verification.isNotBlank())), onClick = {
                        val register = registering
                        runAction(completeSignIn = true) {
                            if (register) {
                                account.register(username, password, email, verification, invitation)
                                scope.launch { registering = false }
                            }
                            val result = try { account.login(username, password, rememberCredentials, autoLogin) } catch (error: Exception) {
                                if (register) throw IllegalStateException("$registeredText\n${error.message}", error) else throw error
                            }
                            // UI state changes happen on the main dispatcher after the network operation.
                            scope.launch { flowToken = result.flowToken; password = ""; registering = false }
                            if (result.flowToken == null) afterLogin() else ""
                        }
                    }) { Text(uiText(if (registering) R.string.new_api_register else R.string.new_api_login)) }
                    TextButton(enabled = !busy, onClick = { registering = !registering; status = "" }) {
                        Text(uiText(if (registering) R.string.new_api_back_to_login else R.string.new_api_create_account))
                    }
                }
                if (status.isNotBlank()) Text(status, color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            }
        },
        confirmButton = { TextButton(enabled = !busy, onClick = { account.cancelPendingLogin(); onDismiss() }) { Text(uiText(R.string.new_api_close)) } },
    )
}

@Composable
private fun NewApiLoginOption(label: String, checked: Boolean, enabled: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(checked, enabled = enabled, role = Role.Checkbox, onValueChange = onCheckedChange)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
        Text(label, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
