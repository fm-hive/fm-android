package lan.fmnode.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import lan.fmnode.android.R

/**
 * Combined export/import page (#57). One entry point in Settings, with the
 * actual choice of what to export or import made here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DataTransferScreen(
    onBack: () -> Unit,
    onExportKeys: (String) -> Unit,
    onExportSettings: () -> Unit,
    onExportFull: () -> Unit,
    onImport: () -> Unit,
    backupStatus: String?,
    backupError: UiError?,
    onClearBackup: () -> Unit,
) {
    var showExportPassword by remember { mutableStateOf(false) }
    if (showExportPassword) {
        ExportPasswordDialog(
            onConfirm = { password ->
                showExportPassword = false
                onExportKeys(password)
            },
            onDismiss = { showExportPassword = false },
        )
    }
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.data_transfer_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
            )
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
            Text(
                text = stringResource(R.string.data_transfer_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Card(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = stringResource(R.string.data_transfer_export),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    DataTransferAction(
                        title = stringResource(R.string.data_transfer_keys),
                        description = stringResource(R.string.data_transfer_keys_hint),
                        button = stringResource(R.string.export_keys),
                        onClick = { showExportPassword = true },
                    )
                    DataTransferAction(
                        title = stringResource(R.string.data_transfer_settings),
                        description = stringResource(R.string.data_transfer_settings_hint),
                        button = stringResource(R.string.fm_settings_exportsettings),
                        onClick = onExportSettings,
                    )
                    DataTransferAction(
                        title = stringResource(R.string.data_transfer_full),
                        description = stringResource(R.string.data_transfer_full_hint),
                        button = stringResource(R.string.fm_settings_fullexport),
                        onClick = onExportFull,
                    )
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = stringResource(R.string.data_transfer_import),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = stringResource(R.string.data_transfer_import_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(
                        onClick = onImport,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.fm_settings_importsettings))
                    }
                }
            }

            backupStatus?.let {
                Text(it, color = MaterialTheme.colorScheme.primary)
                TextButton(onClick = onClearBackup) {
                    Text(stringResource(R.string.close))
                }
            }
            backupError?.let {
                Text(errorText(it), color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun DataTransferAction(
    title: String,
    description: String,
    button: String,
    onClick: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
            Text(button)
        }
    }
}
