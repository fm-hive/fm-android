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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import lan.fmnode.android.R
import lan.fmnode.android.storage.ProfileEntity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    profile: ProfileEntity?,
    onLock: () -> Unit,
    onDeleteNetwork: () -> Unit,
    onEchoes: () -> Unit,
    onNetmail: () -> Unit,
) {
    Scaffold(
        topBar = { CenterAlignedTopAppBar(title = { Text(stringResource(R.string.settings_tab)) }) },
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
            TextButton(onClick = onDeleteNetwork, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.delete_network), color = MaterialTheme.colorScheme.error)
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
