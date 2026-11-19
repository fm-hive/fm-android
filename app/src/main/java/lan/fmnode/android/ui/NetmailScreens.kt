package lan.fmnode.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Forward
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Mail
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import lan.fmnode.android.R

@Composable
fun MainBottomBar(
    selected: AppScreen,
    onEchoes: () -> Unit,
    onNetmail: () -> Unit,
    onSettings: () -> Unit,
) {
    NavigationBar {
        NavigationBarItem(
            selected = selected == AppScreen.Echoes,
            onClick = onEchoes,
            icon = { Icon(Icons.Default.Forum, contentDescription = null) },
            label = { Text(stringResource(R.string.echoes_tab)) },
        )
        NavigationBarItem(
            selected = selected == AppScreen.Netmail,
            onClick = onNetmail,
            icon = { Icon(Icons.Default.Mail, contentDescription = null) },
            label = { Text(stringResource(R.string.netmail_tab)) },
        )
        NavigationBarItem(
            selected = selected == AppScreen.Settings,
            onClick = onSettings,
            icon = { Icon(Icons.Default.Settings, contentDescription = null) },
            label = { Text(stringResource(R.string.settings_tab)) },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetmailScreen(
    messages: List<MessageUi>,
    customFolders: List<NetmailFolderUi>,
    folder: String,
    folderDialog: Boolean,
    folderName: String,
    folderError: UiError?,
    moveMessageId: String?,
    onFolder: (String) -> Unit,
    onOpen: (String) -> Unit,
    onCompose: () -> Unit,
    onOpenFolderDialog: () -> Unit,
    onCloseFolderDialog: () -> Unit,
    onFolderName: (String) -> Unit,
    onCreateFolder: () -> Unit,
    onDeleteFolder: (String) -> Unit,
    onOpenMove: (String) -> Unit,
    onCloseMove: () -> Unit,
    onMove: (String?) -> Unit,
    onEchoes: () -> Unit,
    onSettings: () -> Unit,
    onLock: () -> Unit,
    onLogout: () -> Unit,
) {
    val folders = listOf(
        NetmailFolderUi(NETMAIL_INBOX, stringResource(R.string.inbox), count = messages.count { it.folderSlug == null && !it.own }),
        NetmailFolderUi(NETMAIL_SENT, stringResource(R.string.sent), count = messages.count { it.folderSlug == null && it.own }),
    ) + customFolders.map { f ->
        f.copy(count = messages.count { it.folderSlug == f.slug })
    }
    val visible = messages.filter {
        when (folder) {
            NETMAIL_INBOX -> it.folderSlug == null && !it.own
            NETMAIL_SENT -> it.folderSlug == null && it.own
            else -> it.folderSlug == folder
        }
    }.sortedByDescending { it.timestamp }
    if (folderDialog) {
        NetmailFolderDialog(
            name = folderName,
            error = folderError,
            onName = onFolderName,
            onCreate = onCreateFolder,
            onDismiss = onCloseFolderDialog,
        )
    }
    if (moveMessageId != null) {
        MoveToFolderDialog(
            folders = customFolders,
            onMove = onMove,
            onDismiss = onCloseMove,
        )
    }
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.netmail_tab)) },
                actions = { AppMenu(onLock = onLock, onLogout = onLogout) },
            )
        },
        bottomBar = {
            MainBottomBar(AppScreen.Netmail, onEchoes = onEchoes, onNetmail = {}, onSettings = onSettings)
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onCompose) {
                Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.compose))
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
            ) {
                folders.forEach { item ->
                    AssistChip(
                        onClick = { onFolder(item.slug) },
                        label = {
                            Text(if (item.count > 0) "${item.label} (${item.count})" else item.label)
                        },
                    )
                    if (item.custom) {
                        IconButton(onClick = { onDeleteFolder(item.slug) }) {
                            Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.delete))
                        }
                    }
                }
                IconButton(onClick = onOpenFolderDialog) {
                    Icon(Icons.Default.Add, contentDescription = stringResource(R.string.create_folder))
                }
            }
            if (visible.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(R.string.no_netmail),
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
                // Netmail is one echo: the id alone is unique here.
                items(visible, key = { it.messageId }) { message ->
                    NetmailCard(
                        message = message,
                        folder = folder,
                        onClick = { onOpen(message.messageId) },
                        onMove = { onOpenMove(message.messageId) },
                    )
                }
            }
        }
    }
}

@Composable
private fun NetmailCard(
    message: MessageUi,
    folder: String,
    onClick: () -> Unit,
    onMove: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = message.subject.ifBlank { stringResource(R.string.no_subject) },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = if (folder == NETMAIL_SENT) {
                        message.recipientAddress.orEmpty()
                    } else {
                        "${message.senderName} (${message.senderAddress})"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onMove) {
                Icon(Icons.Default.Folder, contentDescription = stringResource(R.string.move_to_folder))
            }
        }
    }
}

@Composable
private fun NetmailFolderDialog(
    name: String,
    error: UiError?,
    onName: (String) -> Unit,
    onCreate: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.create_folder)) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = onName,
                    label = { Text(stringResource(R.string.folder_name)) },
                    singleLine = true,
                )
                error?.let { Text(errorText(it), color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(onClick = onCreate) { Text(stringResource(R.string.fm_createecho_create)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun MoveToFolderDialog(
    folders: List<NetmailFolderUi>,
    onMove: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.move_to_folder)) },
        text = {
            Column {
                TextButton(onClick = { onMove(null) }) { Text(stringResource(R.string.inbox)) }
                folders.forEach { folder ->
                    TextButton(onClick = { onMove(folder.slug) }) { Text(folder.label) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetmailDetailScreen(
    message: MessageUi?,
    onBack: () -> Unit,
    onForward: (MessageUi) -> Unit,
    onReply: (MessageUi) -> Unit = {},
    showTearline: Boolean = false,
) {
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.netmail_tab)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    if (message != null) {
                        IconButton(onClick = { onReply(message) }) {
                            Icon(
                                Icons.AutoMirrored.Filled.Reply,
                                contentDescription = stringResource(R.string.fm_messageactions_reply),
                            )
                        }
                        IconButton(onClick = { onForward(message) }) {
                            Icon(Icons.Default.Forward, contentDescription = stringResource(R.string.forward))
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (message == null) return@Scaffold
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
        ) {
            Text(
                text = message.subject.ifBlank { stringResource(R.string.no_subject) },
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "${message.senderName} (${message.senderAddress}) → ${message.recipientAddress.orEmpty()}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                MarkdownRenderer.render(message.body) { ref ->
                    attachmentRefName(ref, message.messageId, message.attachments)
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            if (message.tagline.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = message.tagline,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (showTearline && message.tearline.isNotBlank()) {
                Text(
                    text = "--- ${message.tearline}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (message.attachments.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                AttachmentsRow(message.attachments)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetmailComposeScreen(
    state: NetmailComposeState,
    onBack: () -> Unit,
    onRecipient: (String) -> Unit,
    onSubject: (String) -> Unit,
    onBody: (String) -> Unit,
    onSend: () -> Unit,
    onContacts: () -> Unit,
    onAttachFiles: () -> Unit = {},
    onRemoveAttachment: (Int) -> Unit = {},
    onTagline: (String) -> Unit = {},
    onClearReply: () -> Unit = {},
) {
    var showEmoji by remember { mutableStateOf(false) }
    if (showEmoji) {
        EmojiPickerDialog(
            onPick = { emoji -> onBody(state.body + emoji) },
            onDismiss = { showEmoji = false },
        )
    }
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                // The long Russian title wrapped to three lines next to the
                // text send action; a single-line title and an icon action
                // keep the top bar height stable.
                title = {
                    Text(
                        text = stringResource(R.string.netmail_compose),
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
                    IconButton(onClick = onAttachFiles) {
                        Icon(Icons.Default.AttachFile, contentDescription = stringResource(R.string.attach_file))
                    }
                    IconButton(onClick = { showEmoji = true }) {
                        Icon(Icons.Default.EmojiEmotions, contentDescription = stringResource(R.string.emoji_title))
                    }
                    IconButton(onClick = onSend, enabled = !state.sending && !state.keyRequested) {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            contentDescription = stringResource(R.string.send),
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
                .padding(12.dp),
        ) {
            ReplyBanner(label = state.replyToLabel, onClear = onClearReply)
            OutlinedTextField(
                value = state.recipient,
                onValueChange = onRecipient,
                label = { Text(stringResource(R.string.recipient)) },
                singleLine = true,
                trailingIcon = {
                    IconButton(onClick = onContacts) {
                        Icon(
                            Icons.Default.Person,
                            contentDescription = stringResource(R.string.contacts_title),
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = state.subject,
                onValueChange = onSubject,
                label = { Text(stringResource(R.string.subject)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            ComposerBodyEditor(
                body = state.body,
                onBody = onBody,
                attachments = state.attachments,
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = state.tagline,
                onValueChange = onTagline,
                label = { Text(stringResource(R.string.fm_editor_tagline)) },
                placeholder = { Text(stringResource(R.string.fm_editor_taglineplaceholder)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (state.attachments.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                ComposerAttachments(attachments = state.attachments, onRemove = onRemoveAttachment)
            }
            if (state.keyRequested) {
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.key_requested), color = MaterialTheme.colorScheme.primary)
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
    }
}
