package lan.fmnode.android.ui

import androidx.annotation.StringRes
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import lan.fmnode.android.network.ClientRefillStatus
import lan.fmnode.android.network.ClientRefillUi
import lan.fmnode.android.network.NetworkSession
import lan.fmnode.android.network.NetworkSnapshot
import lan.fmnode.android.network.ProxyKind
import lan.fmnode.android.network.ProxyMode
import lan.fmnode.android.storage.ProfileEntity

/** Validation error of the manual proxy fields. */
enum class ProxyFormError { HOST, PORT }

/** Draft of the global proxy settings shown in Settings → Network (#50). */
data class ProxySettingsUi(
    val mode: ProxyMode = ProxyMode.SYSTEM,
    val kind: ProxyKind = ProxyKind.HTTP,
    val host: String = "",
    val port: String = "",
    /** Proxy currently configured in Android, "host:port" or empty. */
    val systemProxy: String = "",
    val error: ProxyFormError? = null,
)

/** Callbacks of the network settings card, grouped to keep FmRoot short. */
data class NetworkSettingsCallbacks(
    val proxy: ProxySettingsUi = ProxySettingsUi(),
    /** Current Android network/VPN state; null until the first callback. */
    val network: NetworkSnapshot? = null,
    val onMode: (ProxyMode) -> Unit = {},
    val onKind: (ProxyKind) -> Unit = {},
    val onHost: (String) -> Unit = {},
    val onPort: (String) -> Unit = {},
    val onSave: () -> Unit = {},
)

/** Multi-device mailbox refill controls (#56), grouped for FmRoot. */
data class ClientSyncCallbacks(
    val state: ClientRefillUi = ClientRefillUi(),
    val onStart: () -> Unit = {},
    val onCancel: () -> Unit = {},
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    profile: ProfileEntity?,
    /** Network name from the accepted policy; shown instead of the node URL. */
    networkName: String = "",
    onDeleteNetwork: () -> Unit,
    onEchoes: () -> Unit,
    onNetmail: () -> Unit,
    /** Opens the combined export/import page (#57). */
    onOpenDataTransfer: () -> Unit,
    onOpenAdmin: () -> Unit,
    onOpenRecovery: () -> Unit,
    openNodes: List<NodeUi> = emptyList(),
    onRequestVoucher: (NodeUi) -> Unit = {},
    onCreateVoucher: () -> Unit,
    voucherCode: String?,
    voucherLoading: Boolean,
    voucherError: UiError?,
    onDismissVoucher: () -> Unit,
    onOpenDisplayName: () -> Unit = {},
    tagline: String = "",
    showTearline: Boolean = false,
    onTagline: (String) -> Unit = {},
    onShowTearline: (Boolean) -> Unit = {},
    allowSendingWithoutRateLimit: Boolean = false,
    onAllowSendingWithoutRateLimit: (Boolean) -> Unit = {},
    voucherEnabled: Boolean = true,
    voucherType: String = NetworkSession.VOUCHER_INVITE,
    voucherTtl: String = "24h",
    voucherReusable: Boolean = false,
    voucherExpiresAt: String? = null,
    vouchers: List<VoucherUi> = emptyList(),
    vouchersLoading: Boolean = false,
    vouchersError: UiError? = null,
    onVoucherType: (String) -> Unit = {},
    onVoucherTtl: (String) -> Unit = {},
    onLoadVouchers: () -> Unit = {},
    onRevokeVoucher: (String) -> Unit = {},
    autoRequestPubkeys: Boolean = false,
    autoSendPubkey: Boolean = false,
    onAutoRequestPubkeys: (Boolean) -> Unit = {},
    onAutoSendPubkey: (Boolean) -> Unit = {},
    onOpenOutbox: () -> Unit,
    onOpenPolicyViewer: () -> Unit,
    onOpenLogs: () -> Unit = {},
    networkSettings: NetworkSettingsCallbacks = NetworkSettingsCallbacks(),
    clientSync: ClientSyncCallbacks = ClientSyncCallbacks(),
    onLock: () -> Unit,
    onLogout: () -> Unit,
) {
    var showLockDialog by remember { mutableStateOf(false) }
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
    if (showLockDialog) {
        LockConfirmDialog(
            onConfirm = {
                showLockDialog = false
                onLock()
            },
            onDismiss = { showLockDialog = false },
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
                        text = networkName.ifBlank { profile.title },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(4.dp))
                    LabeledValue(stringResource(R.string.address_label), profile.address)
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text(
                            text = stringResource(R.string.display_name) + ": " + profile.displayName,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onOpenDisplayName) {
                            Text(stringResource(R.string.display_name_change))
                        }
                    }
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

            OutlinedButton(onClick = { showLockDialog = true }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.lock))
            }
            OutlinedButton(onClick = onLogout, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.logout))
            }
            OutlinedButton(onClick = onOpenDataTransfer, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.data_transfer_title))
            }
            OutlinedButton(onClick = { showInviteDialog = true }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.show_invite_qr))
            }
            // Keys of other users only arrive in a pubkey_response, so a netmail
            // to an unknown recipient stays in the outbox unless we ask for it.
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.privacy_title),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.privacy_auto_request_pubkeys),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                text = stringResource(R.string.privacy_auto_request_pubkeys_hint),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = autoRequestPubkeys, onCheckedChange = onAutoRequestPubkeys)
                    }
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.privacy_auto_send_pubkey),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Switch(checked = autoSendPubkey, onCheckedChange = onAutoSendPubkey)
                    }
                }
            }
            NetworkSettingsCard(
                state = networkSettings.proxy,
                callbacks = networkSettings,
            )
            ClientSyncCard(clientSync)
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.fm_settings_profiletagline),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(
                        value = tagline,
                        onValueChange = onTagline,
                        placeholder = { Text(stringResource(R.string.fm_settings_profiletaglineplaceholder)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = stringResource(R.string.fm_settings_profiletaglinehint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text(
                            text = stringResource(R.string.fm_settings_profileshowtearline),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Switch(checked = showTearline, onCheckedChange = onShowTearline)
                    }
                    Text(
                        text = stringResource(R.string.fm_settings_profileshowtearlinehint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.fm_settings_ratelimit),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text(
                            text = stringResource(R.string.fm_settings_allowsendingwithoutratelimit),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Switch(
                            checked = allowSendingWithoutRateLimit,
                            onCheckedChange = onAllowSendingWithoutRateLimit,
                        )
                    }
                    Text(
                        text = stringResource(R.string.fm_settings_allowsendingwithoutratelimitdesc),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (voucherEnabled) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Text(
                            text = stringResource(R.string.voucher_type),
                            style = MaterialTheme.typography.labelLarge,
                        )
                        // Russian labels ("Одноразовое приглашение" and
                        // "Многоразовый (на время)") do not fit side by side.
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { onVoucherType(NetworkSession.VOUCHER_INVITE) },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    text = stringResource(R.string.voucher_type_invite),
                                    fontWeight = if (voucherType == NetworkSession.VOUCHER_INVITE) FontWeight.Bold else FontWeight.Normal,
                                    color = if (voucherType == NetworkSession.VOUCHER_INVITE) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                                )
                            }
                            // Time vouchers are admin-only on the node.
                            OutlinedButton(
                                onClick = { onVoucherType(NetworkSession.VOUCHER_INVITE_TIME) },
                                enabled = profile.isAdmin,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    text = stringResource(R.string.voucher_type_time),
                                    fontWeight = if (voucherType == NetworkSession.VOUCHER_INVITE_TIME) FontWeight.Bold else FontWeight.Normal,
                                    color = if (voucherType == NetworkSession.VOUCHER_INVITE_TIME) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                                )
                            }
                        }
                        if (voucherType == NetworkSession.VOUCHER_INVITE_TIME) {
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = voucherTtl,
                                onValueChange = onVoucherTtl,
                                label = { Text(stringResource(R.string.voucher_ttl)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = onCreateVoucher,
                            enabled = !voucherLoading,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.send_voucher))
                        }
                        voucherCode?.let { code ->
                            Spacer(Modifier.height(8.dp))
                            Text(code, fontFamily = FontFamily.Monospace)
                            if (voucherReusable) {
                                Text(
                                    text = stringResource(R.string.voucher_type_time) +
                                        (voucherExpiresAt?.let { " · $it" } ?: ""),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        voucherError?.let {
                            Spacer(Modifier.height(8.dp))
                            Text(errorText(it), color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
            if (voucherEnabled) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Text(
                                text = stringResource(R.string.vouchers_title),
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = onLoadVouchers, enabled = !vouchersLoading) {
                                Text(stringResource(R.string.vouchers_refresh))
                            }
                        }
                        if (vouchers.isEmpty()) {
                            Text(
                                text = stringResource(R.string.vouchers_empty),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        vouchers.forEach { voucher ->
                            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(voucher.code, fontFamily = FontFamily.Monospace)
                                    Text(
                                        text = stringResource(voucherStatusRes(voucher.status)) +
                                            " · " + stringResource(voucherTypeRes(voucher.type)) +
                                            if (voucher.expiresAt.isNotBlank()) " · ${voucher.expiresAt}" else "",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                if (voucher.status == "active") {
                                    TextButton(onClick = { onRevokeVoucher(voucher.code) }) {
                                        Text(stringResource(R.string.voucher_revoke))
                                    }
                                }
                            }
                        }
                        vouchersError?.let { Text(errorText(it), color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
            OutlinedButton(onClick = onOpenOutbox, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.outbox_title))
            }
            OutlinedButton(onClick = onOpenPolicyViewer, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.view_policy))
            }
            OutlinedButton(onClick = onOpenLogs, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.logs_title))
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

@StringRes
private fun voucherStatusRes(status: String): Int = when (status) {
    "used" -> R.string.voucher_status_used
    "expired" -> R.string.voucher_status_expired
    "revoked" -> R.string.voucher_status_revoked
    else -> R.string.voucher_status_active
}

@StringRes
private fun voucherTypeRes(type: String): Int = when (type) {
    NetworkSession.VOUCHER_INVITE_TIME -> R.string.voucher_type_time
    else -> R.string.voucher_type_invite
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
internal fun ExportPasswordDialog(
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
private fun ClientSyncCard(callbacks: ClientSyncCallbacks) {
    val state = callbacks.state
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.client_sync_title),
                style = MaterialTheme.typography.labelLarge,
            )
            Text(
                text = stringResource(R.string.client_sync_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val status = when (state.status) {
                ClientRefillStatus.Waiting -> stringResource(
                    R.string.client_refill_waiting,
                    state.expected,
                )
                ClientRefillStatus.Receiving -> stringResource(
                    R.string.client_refill_progress,
                    state.messages,
                    state.done,
                    state.expected,
                )
                ClientRefillStatus.Done -> stringResource(
                    R.string.client_refill_done,
                    state.messages,
                )
                ClientRefillStatus.Partial -> stringResource(
                    R.string.client_refill_partial,
                    state.messages,
                    state.done,
                    state.expected,
                )
                ClientRefillStatus.NoClients -> stringResource(R.string.client_refill_no_clients)
                ClientRefillStatus.Error -> when (state.error) {
                    "disabled" -> stringResource(R.string.client_refill_disabled)
                    else -> stringResource(R.string.client_refill_error)
                }
                ClientRefillStatus.Idle -> ""
            }
            if (status.isNotEmpty()) {
                Text(
                    text = status,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state.status == ClientRefillStatus.Error) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            if (state.active) {
                OutlinedButton(
                    onClick = callbacks.onCancel,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.client_refill_cancel))
                }
            } else {
                OutlinedButton(
                    onClick = callbacks.onStart,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.client_refill_start))
                }
            }
        }
    }
}

@Composable
private fun NetworkSettingsCard(
    state: ProxySettingsUi,
    callbacks: NetworkSettingsCallbacks,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                text = stringResource(R.string.network_proxy_title),
                style = MaterialTheme.typography.labelLarge,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.network_proxy_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val network = callbacks.network
            if (network == null) {
                Text(
                    text = stringResource(R.string.network_state_unknown),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    text = stringResource(
                        if (network.vpn) R.string.network_vpn_active else R.string.network_vpn_inactive,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (network.vpn) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                if (network.transports.isNotEmpty()) {
                    Text(
                        text = stringResource(
                            R.string.network_transport,
                            network.transports.joinToString(", "),
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                FilterChip(
                    selected = state.mode == ProxyMode.SYSTEM,
                    onClick = { callbacks.onMode(ProxyMode.SYSTEM) },
                    label = { Text(stringResource(R.string.network_proxy_system)) },
                )
                FilterChip(
                    selected = state.mode == ProxyMode.DIRECT,
                    onClick = { callbacks.onMode(ProxyMode.DIRECT) },
                    label = { Text(stringResource(R.string.network_proxy_direct)) },
                )
                FilterChip(
                    selected = state.mode == ProxyMode.MANUAL,
                    onClick = { callbacks.onMode(ProxyMode.MANUAL) },
                    label = { Text(stringResource(R.string.network_proxy_manual)) },
                )
            }
            when (state.mode) {
                ProxyMode.SYSTEM -> Text(
                    text = stringResource(
                        R.string.network_proxy_system_current,
                        state.systemProxy.ifBlank {
                            stringResource(R.string.network_proxy_system_none)
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ProxyMode.DIRECT -> Text(
                    text = stringResource(R.string.network_proxy_direct_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ProxyMode.MANUAL -> {
                    Spacer(Modifier.height(8.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        FilterChip(
                            selected = state.kind == ProxyKind.HTTP,
                            onClick = { callbacks.onKind(ProxyKind.HTTP) },
                            label = { Text(stringResource(R.string.network_proxy_http)) },
                        )
                        FilterChip(
                            selected = state.kind == ProxyKind.SOCKS,
                            onClick = { callbacks.onKind(ProxyKind.SOCKS) },
                            label = { Text(stringResource(R.string.network_proxy_socks)) },
                        )
                    }
                    OutlinedTextField(
                        value = state.host,
                        onValueChange = callbacks.onHost,
                        label = { Text(stringResource(R.string.network_proxy_host)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = state.port,
                        onValueChange = callbacks.onPort,
                        label = { Text(stringResource(R.string.network_proxy_port)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    state.error?.let {
                        Text(
                            text = stringResource(
                                when (it) {
                                    ProxyFormError.HOST -> R.string.network_proxy_error_host
                                    ProxyFormError.PORT -> R.string.network_proxy_error_port
                                },
                            ),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Button(onClick = callbacks.onSave, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.network_proxy_save))
            }
        }
    }
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
