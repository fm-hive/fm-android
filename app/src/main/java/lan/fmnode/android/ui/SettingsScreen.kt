package lan.fmnode.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Image
import lan.fmnode.android.R
import lan.fmnode.android.crypto.Encoding
import lan.fmnode.android.storage.ProfileEntity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    profile: ProfileEntity?,
    onDeleteNetwork: () -> Unit,
    onEchoes: () -> Unit,
    onNetmail: () -> Unit,
    onExportKeys: (String) -> Unit,
    onExportSettings: () -> Unit,
    onExportFull: () -> Unit,
    onImportBackup: () -> Unit,
    backupStatus: String?,
    backupError: UiError?,
    onClearBackup: () -> Unit,
    onOpenAdmin: () -> Unit,
    onOpenRecovery: () -> Unit,
    openNodes: List<NodeUi> = emptyList(),
    onRequestVoucher: (NodeUi) -> Unit = {},
    onCreateVoucher: () -> Unit,
    voucherCode: String?,
    voucherLoading: Boolean,
    voucherError: UiError?,
    onDismissVoucher: () -> Unit,
    onOpenOutbox: () -> Unit,
    onOpenPolicyViewer: () -> Unit,
    onLock: () -> Unit,
    onLogout: () -> Unit,
) {
    var showExportDialog by remember { mutableStateOf(false) }
    var showInviteDialog by remember { mutableStateOf(false) }
    if (voucherCode != null && profile != null) {
        InviteQrDialog(
            url = inviteUrl(profile) + "&voucher=" + voucherCode,
            onDismiss = onDismissVoucher,
        )
    }
    if (showInviteDialog && profile != null) {
        InviteQrDialog(url = inviteUrl(profile), onDismiss = { showInviteDialog = false })
    }
    if (showExportDialog) {
        ExportPasswordDialog(
            onConfirm = { password ->
                showExportDialog = false
                onExportKeys(password)
            },
            onDismiss = { showExportDialog = false },
        )
    }
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.settings_tab)) },
                actions = { AppMenu(onLock = onLock, onLogout = onLogout) },
            )
        },
        bottomBar = {
            MainBottomBar(AppScreen.Settings, onEchoes = onEchoes, onNetmail = onNetmail, onSettings = {})
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (profile == null) return@Column

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(
                        text = profile.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(4.dp))
                    LabeledValue(stringResource(R.string.address_label), profile.address)
                    LabeledValue(stringResource(R.string.display_name), profile.displayName)
                    LabeledValue(stringResource(R.string.network_label), profile.apiUrl)
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (profile.isAdmin) {
                            AssistChip(onClick = {}, label = { Text(stringResource(R.string.role_admin)) })
                        }
                        if (profile.isModerator) {
                            AssistChip(onClick = {}, label = { Text(stringResource(R.string.role_moderator)) })
                        }
                    }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.node_key_label),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = profile.nodePublicKeyB64,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }

            OutlinedButton(onClick = onLock, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.lock))
            }
            OutlinedButton(onClick = onLogout, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.logout))
            }
            OutlinedButton(onClick = { showExportDialog = true }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.export_keys))
            }
            OutlinedButton(onClick = onExportSettings, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.fm_settings_exportsettings))
            }
            OutlinedButton(onClick = onExportFull, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.fm_settings_fullexport))
            }
            OutlinedButton(onClick = onImportBackup, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.fm_settings_importsettings))
            }
            backupStatus?.let {
                Text(it, color = MaterialTheme.colorScheme.primary)
                TextButton(onClick = onClearBackup) { Text(stringResource(R.string.close)) }
            }
            backupError?.let { Text(errorText(it), color = MaterialTheme.colorScheme.error) }
            OutlinedButton(onClick = { showInviteDialog = true }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.show_invite_qr))
            }
            OutlinedButton(
                onClick = onCreateVoucher,
                enabled = !voucherLoading,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.send_voucher))
            }
            voucherError?.let {
                Text(errorText(it), color = MaterialTheme.colorScheme.error)
            }
            OutlinedButton(onClick = onOpenOutbox, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.outbox_title))
            }
            OutlinedButton(onClick = onOpenPolicyViewer, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.view_policy))
            }
            OutlinedButton(onClick = onOpenRecovery, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.recovery_title))
            }
            if (openNodes.isNotEmpty()) {
                Text(stringResource(R.string.recovery_open_nodes), style = MaterialTheme.typography.labelLarge)
                openNodes.forEach { node ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(node.address, fontFamily = FontFamily.Monospace)
                            if (node.url.isNotBlank()) {
                                Text(node.url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        TextButton(onClick = { onRequestVoucher(node) }) {
                            Text(stringResource(R.string.recovery_request_voucher))
                        }
                    }
                }
            }
            if (profile.isAdmin) {
                OutlinedButton(onClick = onOpenAdmin, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.admin_title))
                }
            }
            TextButton(onClick = onDeleteNetwork, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.delete_network), color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

private fun inviteUrl(profile: ProfileEntity): String {
    val pubkey = runCatching {
        Encoding.base64UrlEncode(Encoding.base64Decode(profile.publicKeyB64))
    }.getOrDefault("")
    val separator = if (profile.appUrl.contains("?")) "&" else "?"
    return buildString {
        append(profile.appUrl)
        if (pubkey.isNotEmpty()) append(separator).append("pubkey=").append(pubkey)
        if (profile.address.isNotBlank()) append('&').append("address=").append(profile.address)
    }
}

@Composable
private fun InviteQrDialog(url: String, onDismiss: () -> Unit) {
    val bitmap = remember(url) { qrImageBitmap(url, 512) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.invite_qr_title)) },
        text = {
            Column {
                if (bitmap != null) {
                    Image(bitmap = bitmap, contentDescription = null)
                }
                Spacer(Modifier.height(8.dp))
                Text(url, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
        },
    )
}

@Composable
private fun ExportPasswordDialog(
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var password by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.export_password_title)) },
        text = {
            Column {
                Text(stringResource(R.string.export_password_hint), style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.password)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { if (password.isNotBlank()) onConfirm(password) }) {
                Text(stringResource(R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun LabeledValue(label: String, value: String) {
    if (value.isBlank()) return
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            text = "$label: ",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = value, style = MaterialTheme.typography.bodySmall)
    }
}
