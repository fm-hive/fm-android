package lan.fmnode.android.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Mail
import androidx.compose.material.icons.filled.Settings
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
    folder: NetmailFolder,
    onFolder: (NetmailFolder) -> Unit,
    onOpen: (String) -> Unit,
    onCompose: () -> Unit,
    onEchoes: () -> Unit,
    onSettings: () -> Unit,
) {
    val visible = messages.filter { if (folder == NetmailFolder.Inbox) !it.own else it.own }
        .sortedByDescending { it.timestamp }
    Scaffold(
        topBar = { CenterAlignedTopAppBar(title = { Text(stringResource(R.string.netmail_tab)) }) },
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
            TabRow(selectedTabIndex = if (folder == NetmailFolder.Inbox) 0 else 1) {
                Tab(
                    selected = folder == NetmailFolder.Inbox,
                    onClick = { onFolder(NetmailFolder.Inbox) },
                    text = { Text(stringResource(R.string.inbox)) },
                )
                Tab(
                    selected = folder == NetmailFolder.Sent,
                    onClick = { onFolder(NetmailFolder.Sent) },
                    text = { Text(stringResource(R.string.sent)) },
                )
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
                items(visible, key = { it.messageId }) { message ->
                    NetmailCard(
                        message = message,
                        folder = folder,
                        onClick = { onOpen(message.messageId) },
                    )
                }
            }
        }
    }
}

@Composable
private fun NetmailCard(message: MessageUi, folder: NetmailFolder, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text(
                text = message.subject.ifBlank { stringResource(R.string.no_subject) },
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = if (folder == NetmailFolder.Inbox) {
                    "${message.senderName} (${message.senderAddress})"
                } else {
                    message.recipientAddress.orEmpty()
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetmailDetailScreen(
    message: MessageUi?,
    onBack: () -> Unit,
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
            Text(MarkdownRenderer.render(message.body), style = MaterialTheme.typography.bodyMedium)
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
                title = { Text(stringResource(R.string.netmail_compose)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(onClick = { showEmoji = true }) {
                        Icon(Icons.Default.EmojiEmotions, contentDescription = stringResource(R.string.emoji_title))
                    }
                    TextButton(onClick = onSend, enabled = !state.sending) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.send))
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
            OutlinedTextField(
                value = state.recipient,
                onValueChange = onRecipient,
                label = { Text(stringResource(R.string.recipient)) },
                singleLine = true,
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
            OutlinedTextField(
                value = state.body,
                onValueChange = onBody,
                label = { Text(stringResource(R.string.body)) },
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
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
