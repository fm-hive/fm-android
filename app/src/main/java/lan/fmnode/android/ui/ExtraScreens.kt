package lan.fmnode.android.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import java.util.Locale
import lan.fmnode.android.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    query: String,
    loading: Boolean,
    results: List<SearchResultUi>,
    onBack: () -> Unit,
    onQuery: (String) -> Unit,
    onOpenResult: (SearchResultUi) -> Unit,
) {
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.search_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = query,
                onValueChange = onQuery,
                label = { Text(stringResource(R.string.search_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(12.dp),
            )
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.width(24.dp).height(24.dp).padding(start = 12.dp))
            }
            if (results.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(R.string.search_empty),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                return@Scaffold
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Search results span echoes: a message id alone repeats.
                items(results, key = { it.echoName + '\u0000' + it.messageId }) { result ->
                    Card(modifier = Modifier.fillMaxWidth().clickable { onOpenResult(result) }) {
                        Column(Modifier.fillMaxWidth().padding(12.dp)) {
                            Row {
                                Text(
                                    text = result.echoName,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = result.senderName,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (result.subject.isNotBlank()) {
                                Text(
                                    text = result.subject,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                            if (result.body.isNotBlank()) {
                                Text(
                                    text = result.body,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProtocolsScreen(
    protocols: List<ProtocolUi>,
    onBack: () -> Unit,
    onVote: (ProtocolUi, String) -> Unit,
    onRespondRefill: (ProtocolUi) -> Unit,
    onVoteElection: (ProtocolUi, String) -> Unit,
    /** Outcome of answering a refill request ("Sent N message(s)"). */
    refillStatus: String? = null,
) {
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.protocols_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        if (protocols.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding).padding(24.dp), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.protocols_empty),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 12.dp,
                end = 12.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 8.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            refillStatus?.let { status ->
                item(key = "refill-status") {
                    Text(
                        text = status,
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            items(protocols, key = { it.echoName + '\u0000' + it.messageId }) { protocol ->
                ProtocolCard(
                    protocol = protocol,
                    onVote = { variant -> onVote(protocol, variant) },
                    onRespondRefill = { onRespondRefill(protocol) },
                    onVoteElection = { candidate -> onVoteElection(protocol, candidate) },
                )
            }
        }
    }
}

@Composable
private fun ProtocolCard(
    protocol: ProtocolUi,
    onVote: (String) -> Unit,
    onRespondRefill: () -> Unit,
    onVoteElection: (String) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AssistChip(onClick = {}, label = { Text(protocol.type) })
                Spacer(Modifier.width(8.dp))
                Text(
                    text = protocol.echoName,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = protocol.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = "${protocol.senderName} · ${formatTimestamp(protocol.timestamp)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (protocol.detail.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(protocol.detail, style = MaterialTheme.typography.bodySmall)
            }
            protocol.electionCandidate?.let { candidate ->
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onVoteElection(candidate) }) {
                        Text(stringResource(R.string.election_vote))
                    }
                    OutlinedButton(onClick = { onVoteElection("") }) {
                        Text(stringResource(R.string.election_retract))
                    }
                }
            }
            if (protocol.canRespondRefill) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onRespondRefill) {
                    Text(stringResource(R.string.protocol_respond))
                }
            }
            if (protocol.isPoster) {
                Spacer(Modifier.height(8.dp))
                Column {
                    protocol.variants.forEach { variant ->
                        val count = protocol.variantCounts[variant] ?: 0
                        val mine = protocol.myVote == variant
                        OutlinedButton(
                            onClick = { onVote(variant) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = if (count > 0) "$variant ($count)" else variant,
                                fontWeight = if (mine) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (mine) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                    }
                    if (protocol.totalVotes > 0) {
                        Text(
                            text = stringResource(R.string.vote_card_total, protocol.totalVotes),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(4.dp))
                    }
                    if (protocol.myVote != null) {
                        OutlinedButton(onClick = { onVote("") }) {
                            Text(stringResource(R.string.vote_retract))
                        }
                    }
                }
            }
        }
    }
}

private val EMOJIS = listOf(
    "😀", "😂", "🤣", "😊", "😍", "🤔", "😎", "😢",
    "😡", "👍", "👎", "👏", "🙏", "🤝", "💪", "🔥",
    "❤️", "💔", "🎉", "✨", "⭐", "🌞", "🌙", "☕",
    "🍺", "🍕", "🎁", "📌", "📎", "✅", "❌", "⚠️",
    "🚀", "💻", "📱", "🛠️", "📣", "🔔", "🔒", "🔑",
    "📝", "📚", "🎵", "🎮", "⚽", "🏆", "🌍", "🐱",
    "🐶", "🌲", "🌸", "🍀", "❄️", "☀️", "🌧️", "⚡",
    "🕐", "➡️", "⬅️", "⬆️", "⬇️", "ℹ️", "❗", "❓",
)

@Composable
fun EmojiPickerDialog(onPick: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.emoji_title)) },
        text = {
            LazyVerticalGrid(columns = GridCells.Fixed(8), modifier = Modifier.height(280.dp)) {
                items(EMOJIS) { emoji ->
                    TextButton(onClick = { onPick(emoji) }) {
                        Text(emoji, style = MaterialTheme.typography.titleLarge)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
        },
    )
}

/**
 * Renders message attachments: image thumbnails (tap to open full size) or file chips (tap to preview).
 * Files that cannot be rendered inline stay honest chips instead of silently doing nothing.
 */
@Composable
fun AttachmentsRow(attachments: List<AttachmentUi>, modifier: Modifier = Modifier) {
    if (attachments.isEmpty()) return
    var viewer by remember { mutableStateOf<AttachmentUi?>(null) }
    viewer?.let { opened ->
        AttachmentViewerDialog(attachment = opened, onDismiss = { viewer = null })
    }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = modifier) {
        items(attachments) { attachment ->
            val bitmap = remember(attachment.dataUrl) { decodeDataUrl(attachment.dataUrl) }
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = attachment.name,
                    modifier = Modifier
                        .size(72.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { viewer = attachment },
                    contentScale = ContentScale.Crop,
                )
            } else {
                AssistChip(
                    onClick = { viewer = attachment },
                    label = { Text(attachment.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                )
            }
        }
    }
}

/** Full-size attachment rendered inside a message screen (#9). */
@Composable
fun AttachmentViewer(attachment: AttachmentUi, modifier: Modifier = Modifier) {
    val bitmap = remember(attachment.dataUrl) { decodeDataUrl(attachment.dataUrl) }
    Column(modifier = modifier.fillMaxWidth()) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = attachment.name,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .clip(RoundedCornerShape(8.dp)),
                contentScale = ContentScale.Fit,
            )
        } else {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        text = attachment.name.ifBlank { stringResource(R.string.attach_file) },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = if (attachment.size > 0) {
                            "${attachment.type.ifBlank { "file" }} · ${formatFileSize(attachment.size)}"
                        } else {
                            attachment.type.ifBlank { "file" }
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Text(
            text = if (attachment.size > 0) {
                "${attachment.name} · ${formatFileSize(attachment.size)}"
            } else {
                attachment.name
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Full-screen preview used by every attachment thumbnail (#9). */
@Composable
fun AttachmentViewerDialog(attachment: AttachmentUi, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(12.dp), tonalElevation = 6.dp) {
            Column(Modifier.padding(12.dp)) {
                AttachmentViewer(attachment)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
                }
            }
        }
    }
}

internal fun formatFileSize(bytes: Int): String = when {
    bytes >= 1024 * 1024 -> String.format(Locale.getDefault(), "%.1f MiB", bytes / 1024f / 1024f)
    bytes >= 1024 -> "${bytes / 1024} KiB"
    else -> "$bytes B"
}

/** Composer attachments with a remove button. */
@Composable
fun ComposerAttachments(
    attachments: List<AttachmentUi>,
    onRemove: (Int) -> Unit,
) {
    if (attachments.isEmpty()) return
    Column {
        attachments.forEachIndexed { index, attachment ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = attachment.name,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { onRemove(index) }) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.delete))
                }
            }
        }
    }
}

@Composable
fun ComposerAttachmentsRow(attachments: List<AttachmentUi>) {
    AttachmentsRow(attachments)
}

private fun decodeDataUrl(url: String): androidx.compose.ui.graphics.ImageBitmap? {
    val marker = ";base64,"
    val index = url.indexOf(marker)
    if (index < 0) return null
    val encoded = url.substring(index + marker.length)
    val bytes = runCatching { android.util.Base64.decode(encoded, android.util.Base64.DEFAULT) }.getOrNull()
        ?: return null
    val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
    return bitmap.asImageBitmap()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EchoBrowserScreen(
    echoes: List<EchoUi>,
    onBack: () -> Unit,
    onToggle: (String, Boolean) -> Unit,
    onCreateLocal: () -> Unit,
    onRemoteSubscribe: () -> Unit = {},
) {
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                // Two text actions left the title less than a character wide in
                // Russian ("Эхо-конференции"); icon actions keep it readable.
                title = {
                    Text(
                        text = stringResource(R.string.browse_echoes),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(onClick = onRemoteSubscribe) {
                        Icon(
                            Icons.Default.Public,
                            contentDescription = stringResource(R.string.remote_subscribe_title),
                        )
                    }
                    IconButton(onClick = onCreateLocal) {
                        Icon(Icons.Default.Add, contentDescription = stringResource(R.string.create_echo))
                    }
                },
            )
        },
    ) { padding ->
        if (echoes.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding).padding(24.dp), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.echoes_empty),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 12.dp,
                end = 12.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 8.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(echoes, key = { it.name }) { echo ->
                Card(Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(echo.name, fontWeight = FontWeight.SemiBold)
                            if (echo.description.isNotBlank()) {
                                Text(
                                    text = echo.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Switch(
                            checked = echo.subscribed,
                            onCheckedChange = { checked -> onToggle(echo.name, checked) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun PolicyResignDialog(
    policyText: String,
    onAccept: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.policy_changed_title)) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()),
            ) {
                Text(policyText, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = onAccept) { Text(stringResource(R.string.policy_accept)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OutboxScreen(
    entries: List<OutboxUi>,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onDelete: (Long) -> Unit,
) {
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.outbox_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(onClick = onRetry) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.retry))
                    }
                },
            )
        },
    ) { padding ->
        if (entries.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding).padding(24.dp), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.outbox_empty),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 12.dp,
                end = 12.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 8.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(entries, key = { it.id }) { entry ->
                Card(Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = entry.echoName ?: entry.recipient ?: entry.type,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = buildString {
                                    append(entry.type)
                                    if (entry.attempts > 0) append(" · attempts: ").append(entry.attempts)
                                    if (!entry.lastError.isNullOrBlank()) append(" · ").append(entry.lastError)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { onDelete(entry.id) }) {
                            Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.delete))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun RefillDialog(
    date: String,
    sending: Boolean,
    error: UiError?,
    onDate: (String) -> Unit,
    onSend: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.refill_title)) },
        text = {
            Column {
                Text(stringResource(R.string.refill_hint), style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = date,
                    onValueChange = onDate,
                    label = { Text(stringResource(R.string.refill_before)) },
                    singleLine = true,
                )
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(errorText(it), color = MaterialTheme.colorScheme.error)
                }
                if (sending) {
                    Spacer(Modifier.height(8.dp))
                    CircularProgressIndicator(modifier = Modifier.width(24.dp).height(24.dp))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onSend, enabled = !sending) {
                Text(stringResource(R.string.refill_send))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
fun CreateEchoDialog(
    name: String,
    description: String,
    type: CreateEchoType,
    rules: String,
    networkChannel: Boolean,
    writers: String,
    electWritersFrom: String,
    retractVote: Boolean,
    retractVoteDays: String,
    error: UiError?,
    onName: (String) -> Unit,
    onDescription: (String) -> Unit,
    onType: (CreateEchoType) -> Unit,
    onRules: (String) -> Unit,
    onNetworkChannel: (Boolean) -> Unit,
    onWriters: (String) -> Unit,
    onElectWritersFrom: (String) -> Unit,
    onRetractVote: (Boolean) -> Unit,
    onRetractVoteDays: (String) -> Unit,
    onCreate: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.create_echo)) },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                // Conference kinds of fm-app: a local echo, an echo with rules,
                // a channel (writers decide who may post) and a local blog.
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedButton(
                        onClick = { onType(CreateEchoType.LocalEcho) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = stringResource(R.string.fm_createecho_local),
                            fontWeight = if (type == CreateEchoType.LocalEcho) FontWeight.Bold else FontWeight.Normal,
                        )
                    }
                    OutlinedButton(
                        onClick = { onType(CreateEchoType.WithRules) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = stringResource(R.string.create_echo_with_rules),
                            fontWeight = if (type == CreateEchoType.WithRules) FontWeight.Bold else FontWeight.Normal,
                        )
                    }
                    OutlinedButton(
                        onClick = { onType(CreateEchoType.Channel) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = stringResource(R.string.create_echo_channel),
                            fontWeight = if (type == CreateEchoType.Channel) FontWeight.Bold else FontWeight.Normal,
                        )
                    }
                    OutlinedButton(
                        onClick = { onType(CreateEchoType.Blog) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = stringResource(R.string.fm_createecho_blog),
                            fontWeight = if (type == CreateEchoType.Blog) FontWeight.Bold else FontWeight.Normal,
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = onName,
                    label = { Text(stringResource(R.string.create_echo_name)) },
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = description,
                    onValueChange = onDescription,
                    label = { Text(stringResource(R.string.create_echo_description)) },
                )
                if (type == CreateEchoType.WithRules) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = rules,
                        onValueChange = onRules,
                        label = { Text(stringResource(R.string.create_echo_rules)) },
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = networkChannel, onCheckedChange = onNetworkChannel)
                        Text(
                            text = stringResource(R.string.create_echo_network_channel),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                if (type == CreateEchoType.WithRules || type == CreateEchoType.Channel) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = writers,
                        onValueChange = onWriters,
                        label = { Text(stringResource(R.string.create_echo_writers)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = stringResource(R.string.create_echo_writers_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = electWritersFrom,
                        onValueChange = onElectWritersFrom,
                        label = { Text(stringResource(R.string.create_echo_elect_from)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = retractVote, onCheckedChange = onRetractVote)
                        Text(
                            text = stringResource(R.string.create_echo_retract_vote),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (retractVote) {
                        OutlinedTextField(
                            value = retractVoteDays,
                            onValueChange = onRetractVoteDays,
                            label = { Text(stringResource(R.string.create_echo_retract_days)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(errorText(it), color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onCreate) { Text(stringResource(R.string.create_echo_create)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
fun PolicyViewerDialog(text: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.policy_title)) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text(text, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
        },
    )
}

@Composable
fun RemoveEchoDialog(
    name: String,
    reason: String,
    sending: Boolean,
    status: String?,
    error: UiError?,
    onName: (String) -> Unit,
    onReason: (String) -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.remove_echo_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = onName,
                    label = { Text(stringResource(R.string.remove_echo_name)) },
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = reason,
                    onValueChange = onReason,
                    label = { Text(stringResource(R.string.remove_echo_reason)) },
                )
                status?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                error?.let { Text(errorText(it), color = MaterialTheme.colorScheme.error) }
                if (sending) {
                    Spacer(Modifier.height(8.dp))
                    CircularProgressIndicator(modifier = Modifier.width(24.dp).height(24.dp))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onSubmit, enabled = !sending) {
                Text(stringResource(R.string.remove_echo_submit))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
fun PolicyEditDialog(
    state: PolicyEditUi,
    onText: (String) -> Unit,
    onField: (String, String) -> Unit,
    onRateLimit: (String, String) -> Unit,
    onAllChunks: (Boolean) -> Unit,
    onPreview: () -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!state.sending) onDismiss() },
        title = { Text(stringResource(R.string.change_policy_title)) },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                Text(
                    text = stringResource(R.string.policy_edit_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = state.text,
                    onValueChange = onText,
                    label = { Text(stringResource(R.string.change_policy_text)) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = state.allChunksOnly, onCheckedChange = onAllChunks)
                    Text(
                        text = stringResource(R.string.change_policy_all_chunks),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                // One field per policy parameter, grouped like fm-app does.
                state.fields.forEach { field ->
                    PolicyParamRow(field = field, onChange = { onField(field.key, it) })
                }
                if (state.rateLimits.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.policy_edit_rate_limit),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    state.rateLimits.forEach { (key, value) ->
                        OutlinedTextField(
                            value = value,
                            onValueChange = { onRateLimit(key, it) },
                            label = { Text("rate_limit.$key") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(4.dp))
                    }
                }
                if (state.showDiff) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.policy_edit_diff),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    if (state.changes.isEmpty()) {
                        Text(
                            text = stringResource(R.string.policy_edit_no_changes),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        state.changes.forEach { change ->
                            Text(
                                text = change,
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            )
                        }
                    }
                }
                state.status?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.primary)
                }
                state.error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(errorText(it), color = MaterialTheme.colorScheme.error)
                }
                if (state.sending) {
                    Spacer(Modifier.height(8.dp))
                    CircularProgressIndicator(modifier = Modifier.width(24.dp).height(24.dp))
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onPreview, enabled = !state.sending) {
                    Text(stringResource(R.string.policy_edit_preview))
                }
                TextButton(onClick = onSubmit, enabled = !state.sending && !state.hasInvalidFields) {
                    Text(stringResource(R.string.change_policy_submit))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !state.sending) { Text(stringResource(R.string.cancel)) }
        },
    )
}

/** One policy parameter: a text/number/duration field or a set of choices. */
@Composable
private fun PolicyParamRow(field: PolicyParamFieldUi, onChange: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            text = field.key,
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        when (field.kind) {
            PolicyParamKind.Select -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                field.options.forEach { option ->
                    OutlinedButton(
                        onClick = { onChange(option) },
                    ) {
                        Text(
                            text = option,
                            fontWeight = if (field.value == option) FontWeight.Bold else FontWeight.Normal,
                            color = if (field.value == option) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                    }
                }
            }
            PolicyParamKind.Number, PolicyParamKind.Duration, PolicyParamKind.Text -> Column {
                OutlinedTextField(
                    value = field.value,
                    onValueChange = onChange,
                    singleLine = true,
                    isError = field.invalid,
                    placeholder = {
                        if (field.kind == PolicyParamKind.Duration) {
                            Text(stringResource(R.string.policy_edit_duration_hint))
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (field.invalid) {
                    Text(
                        text = stringResource(R.string.policy_edit_duration_invalid),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
fun ModerationDialog(
    severity: String,
    duration: String,
    reason: String,
    sending: Boolean,
    error: UiError?,
    onSeverity: (String) -> Unit,
    onDuration: (String) -> Unit,
    onReason: (String) -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.moderation_title)) },
        text = {
            Column {
                // The dialog is narrow and the Russian labels are long, so the
                // severity choices use full-width rows instead of one Row.
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { onSeverity("warning") },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            stringResource(R.string.moderation_warning),
                            color = if (severity == "warning") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    OutlinedButton(
                        onClick = { onSeverity("punishment") },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            stringResource(R.string.moderation_punishment),
                            color = if (severity == "punishment") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (severity == "punishment") {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = duration,
                        onValueChange = onDuration,
                        label = { Text(stringResource(R.string.moderation_duration)) },
                        singleLine = true,
                    )
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = reason,
                    onValueChange = onReason,
                    label = { Text(stringResource(R.string.moderation_reason)) },
                )
                error?.let { Text(errorText(it), color = MaterialTheme.colorScheme.error) }
                if (sending) {
                    Spacer(Modifier.height(8.dp))
                    CircularProgressIndicator(modifier = Modifier.width(24.dp).height(24.dp))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onSubmit, enabled = !sending) {
                Text(stringResource(R.string.moderation_submit))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
fun ContactsDialog(
    contacts: List<ContactUi>,
    address: String,
    name: String,
    onAddress: (String) -> Unit,
    onName: (String) -> Unit,
    onAdd: () -> Unit,
    onPick: (String) -> Unit,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.contacts_title)) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                contacts.forEach { contact ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = if (contact.name.isBlank()) contact.address else "${contact.name} (${contact.address})",
                            modifier = Modifier.weight(1f).clickable { onPick(contact.address) },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        IconButton(onClick = { onDelete(contact.address) }) {
                            Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.delete))
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = address,
                    onValueChange = onAddress,
                    label = { Text(stringResource(R.string.contact_address)) },
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = onName,
                    label = { Text(stringResource(R.string.contact_name)) },
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onAdd) { Text(stringResource(R.string.contact_add)) }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
        },
    )
}
