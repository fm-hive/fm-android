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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import lan.fmnode.android.R
import lan.fmnode.android.storage.ProfileEntity
import lan.fmnode.android.ui.theme.FmTheme

@Composable
fun FmRoot(
    state: UiState,
    onUnlock: (String) -> Unit = {},
    onLock: () -> Unit = {},
    onOpenAddNetwork: () -> Unit = {},
    onCloseAddNetwork: () -> Unit = {},
    onUrlChange: (String) -> Unit = {},
    onDisplayNameChange: (String) -> Unit = {},
    onPasswordChange: (String) -> Unit = {},
    onPasswordConfirmChange: (String) -> Unit = {},
    onFetchPolicy: () -> Unit = {},
    onRegister: () -> Unit = {},
    onSelectProfile: (String) -> Unit = {},
    onDeleteProfile: (String) -> Unit = {},
    onBackFromNetworks: () -> Unit = {},
    onRefresh: () -> Unit = {},
    onOpenNetworks: () -> Unit = {},
    onSelectEcho: (String) -> Unit = {},
    onBackFromEcho: () -> Unit = {},
    onOpenCompose: () -> Unit = {},
    onBackFromCompose: () -> Unit = {},
    onComposeSubject: (String) -> Unit = {},
    onComposeBody: (String) -> Unit = {},
    onSendCompose: () -> Unit = {},
    onOpenNetmail: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onNetmailFolder: (NetmailFolder) -> Unit = {},
    onOpenNetmailMessage: (String) -> Unit = {},
    onBackFromNetmailDetail: () -> Unit = {},
    onOpenNetmailCompose: () -> Unit = {},
    onBackFromNetmailCompose: () -> Unit = {},
    onNetmailRecipient: (String) -> Unit = {},
    onNetmailSubject: (String) -> Unit = {},
    onNetmailBody: (String) -> Unit = {},
    onSendNetmail: () -> Unit = {},
    onSelectEchoTab: () -> Unit = {},
    onDeleteActiveNetwork: () -> Unit = {},
) {
    when (state.screen) {
        AppScreen.Unlock -> UnlockScreen(state.unlockError, onUnlock)
        AppScreen.Networks -> NetworksScreen(
            profiles = state.profiles,
            activeId = state.activeId,
            canGoBack = state.activeId != null,
            onBack = onBackFromNetworks,
            onAdd = onOpenAddNetwork,
            onSelect = onSelectProfile,
            onDelete = onDeleteProfile,
        )
        AppScreen.AddNetwork -> AddNetworkScreen(
            state = state.add,
            onBack = onCloseAddNetwork,
            onUrlChange = onUrlChange,
            onDisplayNameChange = onDisplayNameChange,
            onPasswordChange = onPasswordChange,
            onPasswordConfirmChange = onPasswordConfirmChange,
            onFetchPolicy = onFetchPolicy,
            onRegister = onRegister,
        )
        AppScreen.Echoes -> EchoesListScreen(
            title = state.activeProfile?.title ?: stringResource(R.string.app_name),
            echoes = state.echoes,
            syncing = state.syncing,
            error = state.syncError,
            onRefresh = onRefresh,
            onNetworks = onOpenNetworks,
            onLock = onLock,
            onSelectEcho = onSelectEcho,
            onNetmail = onOpenNetmail,
            onSettings = onOpenSettings,
        )
        AppScreen.Echo -> EchoConversationScreen(
            echoName = state.selectedEcho?.name ?: "",
            messages = state.messages,
            syncing = state.syncing,
            onBack = onBackFromEcho,
            onRefresh = onRefresh,
            onCompose = onOpenCompose,
        )
        AppScreen.Compose -> EchoComposeScreen(
            echoName = state.selectedEcho?.name ?: "",
            state = state.compose,
            onBack = onBackFromCompose,
            onSubject = onComposeSubject,
            onBody = onComposeBody,
            onSend = onSendCompose,
        )
        AppScreen.Netmail -> NetmailScreen(
            messages = state.netmail,
            folder = state.netmailFolder,
            onFolder = onNetmailFolder,
            onOpen = onOpenNetmailMessage,
            onCompose = onOpenNetmailCompose,
            onEchoes = onSelectEchoTab,
            onSettings = onOpenSettings,
        )
        AppScreen.NetmailDetail -> NetmailDetailScreen(
            message = state.selectedNetmail,
            onBack = onBackFromNetmailDetail,
        )
        AppScreen.NetmailCompose -> NetmailComposeScreen(
            state = state.netmailCompose,
            onBack = onBackFromNetmailCompose,
            onRecipient = onNetmailRecipient,
            onSubject = onNetmailSubject,
            onBody = onNetmailBody,
            onSend = onSendNetmail,
        )
        AppScreen.Settings -> SettingsScreen(
            profile = state.activeProfile,
            onLock = onLock,
            onDeleteNetwork = onDeleteActiveNetwork,
            onEchoes = onSelectEchoTab,
            onNetmail = onOpenNetmail,
        )
    }
}

// ----------------------------------------------------------------------- vault

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UnlockScreen(error: UiError?, onUnlock: (String) -> Unit) {
    var password by remember { mutableStateOf("") }
    Scaffold(
        topBar = { CenterAlignedTopAppBar(title = { Text(stringResource(R.string.app_name)) }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(stringResource(R.string.unlock_title), style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text(stringResource(R.string.password)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            if (error != null) {
                Spacer(Modifier.height(8.dp))
                Text(errorText(error), color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(16.dp))
            Button(onClick = { onUnlock(password) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.unlock))
            }
        }
    }
}

// -------------------------------------------------------------------- networks

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworksScreen(
    profiles: List<ProfileEntity>,
    activeId: String?,
    canGoBack: Boolean,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onSelect: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.networks_title)) },
                navigationIcon = {
                    if (canGoBack) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAdd) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.add_network))
            }
        },
    ) { padding ->
        if (profiles.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(R.string.networks_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 88.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(profiles, key = { it.id }) { profile ->
                ProfileCard(
                    profile = profile,
                    active = profile.id == activeId,
                    onSelect = { onSelect(profile.id) },
                    onDelete = { onDelete(profile.id) },
                )
            }
        }
    }
}

@Composable
private fun ProfileCard(
    profile: ProfileEntity,
    active: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(profile.title, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = profile.address,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = profile.apiUrl,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (active) {
                AssistChip(onClick = onSelect, label = { Text(stringResource(R.string.active)) })
                Spacer(Modifier.width(4.dp))
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.delete))
            }
        }
    }
}

// ----------------------------------------------------------------- add network

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddNetworkScreen(
    state: AddNetworkState,
    onBack: () -> Unit,
    onUrlChange: (String) -> Unit,
    onDisplayNameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onPasswordConfirmChange: (String) -> Unit,
    onFetchPolicy: () -> Unit,
    onRegister: () -> Unit,
) {
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.add_network)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
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
        ) {
            when (state.step) {
                AddStep.Url -> {
                    OutlinedTextField(
                        value = state.url,
                        onValueChange = onUrlChange,
                        label = { Text(stringResource(R.string.invite_url)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = onFetchPolicy,
                        enabled = !state.isLoading && state.url.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (state.isLoading) {
                            CircularProgressIndicator(modifier = Modifier.width(20.dp).height(20.dp))
                        } else {
                            Text(stringResource(R.string.fetch_policy))
                        }
                    }
                }
                AddStep.Policy -> {
                    Text(stringResource(R.string.policy_title), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = state.policyText,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    OutlinedTextField(
                        value = state.displayName,
                        onValueChange = onDisplayNameChange,
                        label = { Text(stringResource(R.string.display_name)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (state.createPassword) {
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = state.password,
                            onValueChange = onPasswordChange,
                            label = { Text(stringResource(R.string.password)) },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = state.passwordConfirm,
                            onValueChange = onPasswordConfirmChange,
                            label = { Text(stringResource(R.string.password_confirm)) },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = onRegister,
                        enabled = !state.isLoading,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (state.isLoading) {
                            CircularProgressIndicator(modifier = Modifier.width(20.dp).height(20.dp))
                        } else {
                            Text(stringResource(R.string.register))
                        }
                    }
                }
            }
            state.error?.let {
                Spacer(Modifier.height(12.dp))
                Text(errorText(it), color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

// ---------------------------------------------------------------------- echoes

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EchoesListScreen(
    title: String,
    echoes: List<EchoUi>,
    syncing: Boolean,
    error: UiError?,
    onRefresh: () -> Unit,
    onNetworks: () -> Unit,
    onLock: () -> Unit,
    onSelectEcho: (String) -> Unit,
    onNetmail: () -> Unit,
    onSettings: () -> Unit,
) {
    Scaffold(
        bottomBar = {
            MainBottomBar(AppScreen.Echoes, onEchoes = {}, onNetmail = onNetmail, onSettings = onSettings)
        },
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(title, maxLines = 1) },
                actions = {
                    if (syncing) {
                        CircularProgressIndicator(modifier = Modifier.width(20.dp).height(20.dp))
                        Spacer(Modifier.width(8.dp))
                    }
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.refresh))
                    }
                    IconButton(onClick = onNetworks) {
                        Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.networks_title))
                    }
                    IconButton(onClick = onLock) {
                        Icon(Icons.Default.Lock, contentDescription = stringResource(R.string.lock))
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            error?.let {
                Text(
                    text = errorText(it),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            if (echoes.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.echoes_empty),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                return@Scaffold
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(echoes, key = { it.name }) { echo ->
                    EchoCard(echo = echo, onClick = { onSelectEcho(echo.name) })
                }
            }
        }
    }
}

@Composable
private fun EchoCard(echo: EchoUi, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(echo.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (echo.description.isNotBlank()) {
                    Text(
                        text = echo.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (echo.unread > 0) {
                Badge { Text(echo.unread.toString()) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EchoConversationScreen(
    echoName: String,
    messages: List<MessageUi>,
    syncing: Boolean,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onCompose: () -> Unit,
) {
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(echoName, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    if (syncing) {
                        CircularProgressIndicator(modifier = Modifier.width(20.dp).height(20.dp))
                        Spacer(Modifier.width(8.dp))
                    }
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.refresh))
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onCompose) {
                Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.compose))
            }
        },
    ) { padding ->
        if (messages.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.no_messages),
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
                bottom = padding.calculateBottomPadding() + 88.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(messages, key = { it.messageId }) { message ->
                MessageCard(message)
            }
        }
    }
}

@Composable
private fun MessageCard(message: MessageUi) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = message.senderName,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = formatTimestamp(message.timestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = message.senderAddress,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (message.subject.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(message.subject, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            }
            if (message.body.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(message.body, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EchoComposeScreen(
    echoName: String,
    state: ComposeState,
    onBack: () -> Unit,
    onSubject: (String) -> Unit,
    onBody: (String) -> Unit,
    onSend: () -> Unit,
) {
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(echoName, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
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

// -------------------------------------------------------------------- helpers

@Composable
internal fun errorText(error: UiError): String = when (error) {
    UiError.WrongPassword -> stringResource(R.string.error_password)
    UiError.PasswordsDiffer -> stringResource(R.string.error_passwords_differ)
    UiError.PasswordShort -> stringResource(R.string.error_password_short)
    is UiError.Failed -> error.text
}

private fun formatTimestamp(seconds: Long): String {
    val millis = if (seconds < 10_000_000_000L) seconds * 1000 else seconds
    return SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(millis))
}

// ------------------------------------------------------------------ previews

private fun sampleProfile(id: String, title: String, address: String, api: String) = ProfileEntity(
    id = id,
    title = title,
    appUrl = api.replace("/api", "/app"),
    apiUrl = api,
    nodePublicKeyB64 = "",
    address = address,
    displayName = "Ivan",
    isAdmin = false,
    isModerator = false,
    publicKeyB64 = "",
    encryptedSeedB64 = "",
    seedSaltB64 = "",
    seedIvB64 = "",
    seedIterations = 100000,
    policyJson = "{}",
    createdAt = 0,
    lastSyncAt = null,
)

@Preview(showBackground = true)
@Composable
private fun NetworksPreview() {
    FmTheme {
        FmRoot(
            state = UiState(
                profiles = listOf(
                    sampleProfile("1", "fm.example.com (:0:1)", ":0:1", "https://fm.example.com/api"),
                    sampleProfile("2", "2.28.13.140 (:0:1)", ":0:1", "https://2.28.13.140/abc/api"),
                ),
                activeId = "1",
                unlocked = true,
            ),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun UnlockPreview() {
    FmTheme {
        FmRoot(state = UiState(screen = AppScreen.Unlock, unlockError = UiError.WrongPassword))
    }
}

@Preview(showBackground = true)
@Composable
private fun AddNetworkUrlPreview() {
    FmTheme {
        FmRoot(state = UiState(screen = AppScreen.AddNetwork))
    }
}

@Preview(showBackground = true)
@Composable
private fun AddNetworkPolicyPreview() {
    FmTheme {
        FmRoot(
            state = UiState(
                screen = AppScreen.AddNetwork,
                add = AddNetworkState(
                    step = AddStep.Policy,
                    policyText = "Правила сети\n\n{ \"voting_days\": \"30\", \"min_percent_for_network_decisions\": \"50\" }",
                    createPassword = true,
                ),
            ),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun EchoesPreview() {
    FmTheme {
        FmRoot(
            state = UiState(
                screen = AppScreen.Echoes,
                unlocked = true,
                activeProfile = sampleProfile("1", "fm.example.com (:0:1)", ":0:1", "https://fm.example.com/api"),
                echoes = listOf(
                    EchoUi("General", "General discussions", 3),
                    EchoUi("Tech", "Technology talks", 0),
                    EchoUi("Admin council", "Council of the network", 1),
                ),
            ),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun EchoPreview() {
    FmTheme {
        FmRoot(
            state = UiState(
                screen = AppScreen.Echo,
                selectedEcho = EchoUi("General", "General discussions", 0),
                messages = listOf(
                    MessageUi(
                        messageId = "1",
                        senderName = "Petya",
                        senderAddress = ":0:2",
                        recipientAddress = null,
                        timestamp = 1792200000,
                        subject = "Привет",
                        body = "Как дела?",
                        own = false,
                    ),
                    MessageUi(
                        messageId = "2",
                        senderName = "Ivan",
                        senderAddress = ":0:1",
                        recipientAddress = null,
                        timestamp = 1792200600,
                        subject = "Re: Привет",
                        body = "Всё хорошо!",
                        own = true,
                    ),
                ),
            ),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ComposePreview() {
    FmTheme {
        FmRoot(
            state = UiState(
                screen = AppScreen.Compose,
                selectedEcho = EchoUi("General", "", 0),
                compose = ComposeState(subject = "Тема", body = "Текст сообщения"),
            ),
        )
    }
}
