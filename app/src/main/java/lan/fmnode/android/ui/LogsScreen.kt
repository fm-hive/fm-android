package lan.fmnode.android.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import lan.fmnode.android.R
import lan.fmnode.android.log.AppLog
import lan.fmnode.android.log.LogCategory
import lan.fmnode.android.log.LogEntry

private enum class LogFilter { All, Network, App, Errors }

/** Network and application diagnostics (#50). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen(onBack: () -> Unit) {
    val entries by AppLog.entries.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var filter by remember { mutableStateOf(LogFilter.All) }
    var clearDialog by remember { mutableStateOf(false) }

    val filtered = remember(entries, filter) {
        entries.filter { entry ->
            when (filter) {
                LogFilter.All -> true
                LogFilter.Network -> entry.category == LogCategory.NETWORK
                LogFilter.App -> entry.category == LogCategory.APP
                LogFilter.Errors -> entry.level == lan.fmnode.android.log.LogLevel.ERROR ||
                    entry.category == LogCategory.ERROR
            }
        }
    }

    if (clearDialog) {
        AlertDialog(
            onDismissRequest = { clearDialog = false },
            title = { Text(stringResource(R.string.logs_clear)) },
            text = { Text(stringResource(R.string.logs_clear_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    AppLog.clear()
                    clearDialog = false
                }) { Text(stringResource(R.string.logs_clear)) }
            },
            dismissButton = {
                TextButton(onClick = { clearDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.logs_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(onClick = { shareLogs(context) }) {
                        Icon(Icons.Default.Share, contentDescription = stringResource(R.string.logs_share))
                    }
                    IconButton(onClick = { clearDialog = true }) {
                        Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.logs_clear))
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                LogFilterChip(stringResource(R.string.logs_all), filter == LogFilter.All) {
                    filter = LogFilter.All
                }
                LogFilterChip(stringResource(R.string.logs_network), filter == LogFilter.Network) {
                    filter = LogFilter.Network
                }
                LogFilterChip(stringResource(R.string.logs_app), filter == LogFilter.App) {
                    filter = LogFilter.App
                }
                LogFilterChip(stringResource(R.string.logs_errors), filter == LogFilter.Errors) {
                    filter = LogFilter.Errors
                }
            }
            Text(
                text = stringResource(R.string.logs_privacy),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
            if (filtered.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(R.string.logs_empty),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(filtered.asReversed()) { entry -> LogRow(entry) }
                }
            }
        }
    }
}

@Composable
private fun LogFilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) })
}

@Composable
private fun LogRow(entry: LogEntry) {
    val time = remember(entry.timestamp) {
        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(entry.timestamp))
    }
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = time,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(8.dp))
            AssistChip(onClick = {}, label = { Text(entry.category.name) })
            Spacer(Modifier.width(6.dp))
            val color = when (entry.level) {
                lan.fmnode.android.log.LogLevel.ERROR -> MaterialTheme.colorScheme.error
                lan.fmnode.android.log.LogLevel.WARN -> MaterialTheme.colorScheme.tertiary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            Text(
                text = entry.level.name,
                style = MaterialTheme.typography.labelSmall,
                color = color,
            )
        }
        Text(
            text = entry.message,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
        )
        if (entry.detail.isNotBlank()) {
            Text(
                text = entry.detail,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 8,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(4.dp))
    }
}

private fun shareLogs(context: Context) {
    runCatching {
        val dir = File(context.cacheDir, "shares").apply { mkdirs() }
        val file = File(dir, "fm-log.txt")
        file.writeText(AppLog.exportText().ifBlank { "FM client log is empty" })
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "FM client log")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, context.getString(R.string.logs_share)))
    }
}
