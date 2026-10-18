package lan.fmnode.android.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import lan.fmnode.android.network.Invite
import lan.fmnode.android.network.InviteParser
import lan.fmnode.android.network.NetworkManager
import lan.fmnode.android.network.NetworkSession
import lan.fmnode.android.network.PolicyInfo
import lan.fmnode.android.session.Session
import lan.fmnode.android.storage.KeyVault
import lan.fmnode.android.storage.KeystoreExportCodec
import lan.fmnode.android.storage.MessageRow
import lan.fmnode.android.storage.ProfileEntity
import lan.fmnode.android.crypto.CryptoService

enum class AppScreen {
    Unlock, Networks, AddNetwork,
    Echoes, Echo, Compose,
    Netmail, NetmailDetail, NetmailCompose,
    Search, Protocols,
    QrScanner,
    Settings,
}

enum class NetmailFolder { Inbox, Sent }

enum class AddStep { Url, Policy }

/** Errors surfaced by the UI; known cases map to localized strings. */
sealed interface UiError {
    data object WrongPassword : UiError
    data object PasswordsDiffer : UiError
    data object PasswordShort : UiError
    data class Failed(val text: String) : UiError
}

data class EchoUi(
    val name: String,
    val description: String,
    val unread: Int,
)

data class MessageUi(
    val messageId: String,
    val senderName: String,
    val senderAddress: String,
    val recipientAddress: String?,
    val timestamp: Long,
    val subject: String,
    val body: String,
    val own: Boolean,
)

data class NetmailComposeState(
    val recipient: String = "",
    val subject: String = "",
    val body: String = "",
    val sending: Boolean = false,
    val error: UiError? = null,
    val keyRequested: Boolean = false,
)

data class SearchResultUi(
    val messageId: String,
    val echoName: String,
    val senderName: String,
    val timestamp: Long,
    val subject: String,
    val body: String,
)

data class ProtocolUi(
    val messageId: String,
    val type: String,
    val echoName: String,
    val senderName: String,
    val timestamp: Long,
    val title: String,
    val detail: String,
    val variants: List<String>,
    val isPoster: Boolean,
)

data class ComposeState(
    val subject: String = "",
    val body: String = "",
    val sending: Boolean = false,
    val error: UiError? = null,
)

data class AddNetworkState(
    val url: String = "",
    val step: AddStep = AddStep.Url,
    val policyText: String = "",
    val policy: PolicyInfo? = null,
    val invite: Invite? = null,
    val displayName: String = "",
    val password: String = "",
    val passwordConfirm: String = "",
    val createPassword: Boolean = false,
    val isLoading: Boolean = false,
    val error: UiError? = null,
    val awaitingImportPassword: Boolean = false,
    val imported: Boolean = false,
    val importError: UiError? = null,
)

data class UiState(
    val screen: AppScreen = AppScreen.Networks,
    val profiles: List<ProfileEntity> = emptyList(),
    val activeId: String? = null,
    val activeProfile: ProfileEntity? = null,
    val unlocked: Boolean = false,
    val echoes: List<EchoUi> = emptyList(),
    val selectedEcho: EchoUi? = null,
    val messages: List<MessageUi> = emptyList(),
    val compose: ComposeState = ComposeState(),
    val netmail: List<MessageUi> = emptyList(),
    val netmailFolder: NetmailFolder = NetmailFolder.Inbox,
    val selectedNetmail: MessageUi? = null,
    val netmailCompose: NetmailComposeState = NetmailComposeState(),
    val protocols: List<ProtocolUi> = emptyList(),
    val searchQuery: String = "",
    val searchResults: List<SearchResultUi> = emptyList(),
    val searchLoading: Boolean = false,
    val add: AddNetworkState = AddNetworkState(),
    val unlockError: UiError? = null,
    val syncError: UiError? = null,
    val syncing: Boolean = false,
)

/**
 * Multi-network application state: unlock the vault, add/switch networks and
 * browse echo conferences of the active network.
 */
class AppViewModel(
    private val manager: NetworkManager,
    private val session: Session,
) : ViewModel() {
    private val prettyJson = Json { prettyPrint = true }
    private val messageJson = Json { ignoreUnknownKeys = true }
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    private var importedSeed: ByteArray? = null
    private var importJsonText: String? = null
    private var networkSession: NetworkSession? = null
    private var flowsJob: Job? = null
    private var syncJob: Job? = null

    init {
        viewModelScope.launch {
            manager.profiles.collect { profiles ->
                _state.update { it.copy(profiles = profiles) }
                if (profiles.isEmpty()) {
                    closeSession()
                    _state.update { it.copy(screen = AppScreen.Networks, echoes = emptyList()) }
                }
            }
        }
        viewModelScope.launch {
            val id = manager.activeProfileId()
            val profile = id?.let { db -> manager.activeProfile() }
            _state.update { it.copy(activeId = id, activeProfile = profile) }
        }
    }

    // ------------------------------------------------------------ vault/session

    fun unlock(password: String) {
        viewModelScope.launch {
            val profile = _state.value.profiles.firstOrNull() ?: return@launch
            if (!session.unlock(password, profile)) {
                _state.update { it.copy(unlockError = UiError.WrongPassword) }
                return@launch
            }
            _state.update { it.copy(unlocked = true, unlockError = null) }
            openActiveSession()
        }
    }

    fun lock() {
        closeSession()
        session.lock()
        _state.update {
            it.copy(
                unlocked = false,
                screen = AppScreen.Unlock,
                echoes = emptyList(),
                messages = emptyList(),
                selectedEcho = null,
                unlockError = null,
            )
        }
    }

    /** Opens the message database/API session of the active profile. */
    private suspend fun openActiveSession() {
        val password = session.passwordOrNull() ?: return
        val profile = manager.activeProfile()
        if (profile == null) {
            closeSession()
            _state.update { it.copy(screen = AppScreen.Networks) }
            return
        }
        closeSession()
        _state.update {
            it.copy(
                activeProfile = profile,
                activeId = profile.id,
                syncing = true,
                syncError = null,
            )
        }
        try {
            val opened = manager.openSession(profile, password)
            networkSession = opened
            opened.refreshEchoes()
            collectSessionFlows(opened)
            syncOnce(opened)
            startSyncLoop(opened)
            _state.update { it.copy(screen = AppScreen.Echoes) }
        } catch (e: Exception) {
            closeSession()
            _state.update {
                it.copy(
                    syncing = false,
                    syncError = UiError.Failed(e.message ?: "Failed"),
                    screen = AppScreen.Networks,
                )
            }
        }
    }

    private fun collectSessionFlows(opened: NetworkSession) {
        flowsJob?.cancel()
        flowsJob = viewModelScope.launch {
            launch {
                combine(
                    opened.database.echoDao().observeSubscribed(),
                    opened.database.messageDao().observeUnreadSummary(opened.profile.address),
                ) { echoes, unread ->
                    val counts = unread.associate { it.echoName to it.count }
                    echoes.map { echo ->
                        EchoUi(
                            name = echo.name,
                            description = echo.description,
                            unread = counts[echo.name] ?: 0,
                        )
                    }
                }.collect { list ->
                    _state.update { s ->
                        val selected = s.selectedEcho?.let { current ->
                            list.firstOrNull { it.name == current.name }
                        }
                        s.copy(echoes = list, selectedEcho = selected)
                    }
                }
            }
            launch {
                opened.database.messageDao().observeProtocols().collect { rows ->
                    _state.update { it.copy(protocols = rows.mapNotNull { row -> row.toProtocolUi() }) }
                }
            }
            launch {
                opened.database.messageDao().observeEchoRows(NetworkSession.NETMAIL_ECHO).collect { rows ->
                    val me = opened.profile.address
                    val myName = opened.profile.displayName
                    _state.update { it.copy(netmail = rows.map { row -> row.toUi(me, myName) }) }
                }
            }
            launch {
                _state
                    .map { it.selectedEcho?.name }
                    .distinctUntilChanged()
                    .collectLatest { echoName ->
                        if (echoName == null) {
                            _state.update { it.copy(messages = emptyList()) }
                            return@collectLatest
                        }
                        opened.database.messageDao().observeEchoRows(echoName).collect { rows ->
                            val me = opened.profile.address
                            val myName = opened.profile.displayName
                            _state.update { it.copy(messages = rows.map { row -> row.toUi(me, myName) }) }
                        }
                    }
            }
        }
    }

    private fun closeSession() {
        syncJob?.cancel()
        syncJob = null
        flowsJob?.cancel()
        flowsJob = null
        networkSession = null
    }

    private fun startSyncLoop(opened: NetworkSession) {
        syncJob?.cancel()
        syncJob = viewModelScope.launch {
            while (isActive) {
                delay(30_000)
                if (networkSession === opened) syncOnce(opened)
            }
        }
    }

    private suspend fun syncOnce(opened: NetworkSession) {
        _state.update { it.copy(syncing = true) }
        try {
            opened.sync()
            _state.update { it.copy(syncing = false, syncError = null) }
        } catch (e: Exception) {
            _state.update {
                it.copy(syncing = false, syncError = UiError.Failed(e.message ?: "Failed"))
            }
        }
    }

    // --------------------------------------------------------------- navigation

    fun refresh() {
        viewModelScope.launch {
            val opened = networkSession ?: return@launch
            runCatching { opened.refreshEchoes() }
            syncOnce(opened)
        }
    }

    fun openNetworks() {
        closeSession()
        _state.update { it.copy(screen = AppScreen.Networks, selectedEcho = null, messages = emptyList()) }
    }

    fun backFromNetworks() {
        if (networkSession != null) {
            _state.update { it.copy(screen = AppScreen.Echoes) }
            return
        }
        if (_state.value.activeId != null && session.isUnlocked) {
            viewModelScope.launch { openActiveSession() }
        }
    }

    fun selectProfile(id: String) {
        viewModelScope.launch {
            manager.setActive(id)
            _state.update { it.copy(activeId = id) }
            openActiveSession()
        }
    }

    fun deleteProfile(id: String) {
        viewModelScope.launch {
            manager.deleteProfile(id)
            val active = manager.activeProfileId()
            _state.update { it.copy(activeId = active) }
            if (active == null) {
                closeSession()
                _state.update { it.copy(screen = AppScreen.Networks, echoes = emptyList()) }
            } else if (id == _state.value.activeProfile?.id || networkSession?.profile?.id == id) {
                openActiveSession()
            }
        }
    }

    fun selectEcho(name: String) {
        viewModelScope.launch {
            val opened = networkSession ?: return@launch
            val echo = _state.value.echoes.firstOrNull { it.name == name } ?: return@launch
            runCatching { opened.markRead(name, System.currentTimeMillis() / 1000) }
            _state.update { it.copy(screen = AppScreen.Echo, selectedEcho = echo) }
        }
    }

    fun backFromEcho() {
        _state.update { it.copy(screen = AppScreen.Echoes, selectedEcho = null, messages = emptyList()) }
    }

    fun openCompose() {
        _state.update { it.copy(screen = AppScreen.Compose, compose = ComposeState()) }
    }

    fun backFromCompose() {
        _state.update { it.copy(screen = AppScreen.Echo, compose = ComposeState()) }
    }

    fun setComposeSubject(value: String) =
        _state.update { it.copy(compose = it.compose.copy(subject = value, error = null)) }

    fun setComposeBody(value: String) =
        _state.update { it.copy(compose = it.compose.copy(body = value, error = null)) }

    fun sendCompose() {
        val current = _state.value
        val echo = current.selectedEcho ?: return
        val opened = networkSession ?: return
        if (current.compose.body.isBlank()) {
            _state.update { it.copy(compose = it.compose.copy(error = UiError.Failed("Message is empty"))) }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(compose = it.compose.copy(sending = true, error = null)) }
            try {
                opened.postEcho(echo.name, current.compose.subject.trim(), current.compose.body.trim())
                _state.update {
                    it.copy(screen = AppScreen.Echo, compose = ComposeState())
                }
                syncOnce(opened)
            } catch (e: Exception) {
                _state.update {
                    it.copy(compose = it.compose.copy(sending = false, error = UiError.Failed(e.message ?: "Failed")))
                }
            }
        }
    }

    // --------------------------------------------------------- add network flow

    fun openAddNetwork() {
        val current = _state.value
        if (current.profiles.isNotEmpty() && !session.isUnlocked) return
        _state.update {
            it.copy(
                screen = AppScreen.AddNetwork,
                add = AddNetworkState(createPassword = current.profiles.isEmpty()),
            )
        }
    }

    fun closeAddNetwork() {
        val current = _state.value
        val screen = when {
            networkSession != null || current.echoes.isNotEmpty() -> AppScreen.Echoes
            else -> AppScreen.Networks
        }
        _state.update { it.copy(screen = screen, add = AddNetworkState()) }
    }

    fun setAddUrl(value: String) = _state.update { it.copy(add = it.add.copy(url = value, error = null)) }

    fun setDisplayName(value: String) =
        _state.update { it.copy(add = it.add.copy(displayName = value, error = null)) }

    fun setAddPassword(value: String) =
        _state.update { it.copy(add = it.add.copy(password = value, error = null)) }

    fun setAddPasswordConfirm(value: String) =
        _state.update { it.copy(add = it.add.copy(passwordConfirm = value, error = null)) }

    fun fetchPolicy() {
        val current = _state.value.add
        val invite = InviteParser.parse(current.url)
        if (invite == null) {
            _state.update { it.copy(add = it.add.copy(error = UiError.Failed("Invalid invite URL"))) }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(add = it.add.copy(isLoading = true, error = null)) }
            try {
                val policy = manager.fetchPolicy(invite)
                _state.update {
                    it.copy(
                        add = it.add.copy(
                            step = AddStep.Policy,
                            policy = policy,
                            invite = invite,
                            policyText = formatPolicy(policy.payload),
                            isLoading = false,
                        ),
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(add = it.add.copy(isLoading = false, error = UiError.Failed(e.message ?: "Failed")))
                }
            }
        }
    }

    fun register() {
        val add = _state.value.add
        val invite = add.invite ?: return
        val policy = add.policy ?: return

        val password = if (add.createPassword) add.password else session.passwordOrNull()
        if (password.isNullOrEmpty()) {
            _state.update { it.copy(add = it.add.copy(error = UiError.WrongPassword)) }
            return
        }
        if (add.createPassword) {
            if (password.length < 4) {
                _state.update { it.copy(add = it.add.copy(error = UiError.PasswordShort)) }
                return
            }
            if (add.password != add.passwordConfirm) {
                _state.update { it.copy(add = it.add.copy(error = UiError.PasswordsDiffer)) }
                return
            }
        }

        viewModelScope.launch {
            _state.update { it.copy(add = it.add.copy(isLoading = true, error = null)) }
            try {
                manager.register(
                    invite = invite,
                    policy = policy,
                    password = password,
                    displayName = add.displayName,
                    seed = importedSeed,
                )
                importedSeed = null
                session.setPassword(password)
                _state.update {
                    it.copy(add = AddNetworkState(), unlocked = true)
                }
                openActiveSession()
            } catch (e: Exception) {
                _state.update {
                    it.copy(add = it.add.copy(isLoading = false, error = UiError.Failed(e.message ?: "Failed")))
                }
            }
        }
    }

    // ------------------------------------------------------------------- search

    private var searchJob: Job? = null

    fun openSearch() {
        _state.update { it.copy(screen = AppScreen.Search, searchQuery = "", searchResults = emptyList()) }
    }

    fun closeSearch() {
        searchJob?.cancel()
        _state.update { it.copy(screen = AppScreen.Echoes, searchQuery = "", searchResults = emptyList()) }
    }

    fun setSearchQuery(query: String) {
        _state.update { it.copy(searchQuery = query) }
        searchJob?.cancel()
        val opened = networkSession ?: return
        if (query.trim().length < 2) {
            _state.update { it.copy(searchResults = emptyList()) }
            return
        }
        searchJob = viewModelScope.launch {
            _state.update { it.copy(searchLoading = true) }
            val rows = runCatching { opened.database.messageDao().search(query.trim()) }.getOrDefault(emptyList())
            val me = opened.profile.address
            val myName = opened.profile.displayName
            _state.update {
                it.copy(
                    searchLoading = false,
                    searchResults = rows.map { row ->
                        val data = runCatching {
                            messageJson.parseToJsonElement(row.dataJson).jsonObject
                        }.getOrNull()
                        SearchResultUi(
                            messageId = row.messageId,
                            echoName = row.echoName,
                            senderName = row.senderName ?: if (row.senderAddress == me) myName else row.senderAddress,
                            timestamp = row.timestamp,
                            subject = (data?.get("subject") as? JsonPrimitive)?.contentOrNull.orEmpty(),
                            body = (data?.get("body") as? JsonPrimitive)?.contentOrNull.orEmpty(),
                        )
                    },
                )
            }
        }
    }

    fun openSearchResult(result: SearchResultUi) {
        if (result.echoName == NetworkSession.NETMAIL_ECHO) {
            openNetmailMessage(result.messageId)
        } else {
            selectEcho(result.echoName)
        }
    }

    // --------------------------------------------------------------- protocols

    fun openProtocols() {
        _state.update { it.copy(screen = AppScreen.Protocols) }
    }

    fun closeProtocols() {
        _state.update { it.copy(screen = AppScreen.Echoes) }
    }

    fun castVote(protocol: ProtocolUi, variant: String) {
        val opened = networkSession ?: return
        viewModelScope.launch {
            runCatching { opened.postVote(protocol.echoName, protocol.title, variant) }
            syncOnce(opened)
        }
    }

    // ------------------------------------------------------------------- QR

    fun openScanner() {
        if (_state.value.screen == AppScreen.AddNetwork) {
            _state.update { it.copy(screen = AppScreen.QrScanner) }
        }
    }

    fun closeScanner() {
        _state.update { it.copy(screen = AppScreen.AddNetwork) }
    }

    fun onQrScanned(value: String) {
        _state.update {
            it.copy(
                screen = AppScreen.AddNetwork,
                add = it.add.copy(url = value.trim(), error = null, step = AddStep.Url),
            )
        }
    }

    // ------------------------------------------------------------------ netmail

    fun openNetmail() {
        _state.update { it.copy(screen = AppScreen.Netmail) }
    }

    fun selectNetmailFolder(folder: NetmailFolder) {
        _state.update { it.copy(netmailFolder = folder) }
    }

    fun openNetmailMessage(messageId: String) {
        viewModelScope.launch {
            val opened = networkSession ?: return@launch
            val message = _state.value.netmail.firstOrNull { it.messageId == messageId } ?: return@launch
            runCatching { opened.markRead(NetworkSession.NETMAIL_ECHO, System.currentTimeMillis() / 1000) }
            _state.update { it.copy(screen = AppScreen.NetmailDetail, selectedNetmail = message) }
        }
    }

    fun backFromNetmailDetail() {
        _state.update { it.copy(screen = AppScreen.Netmail, selectedNetmail = null) }
    }

    fun openNetmailCompose(recipient: String = "") {
        _state.update {
            it.copy(
                screen = AppScreen.NetmailCompose,
                netmailCompose = NetmailComposeState(recipient = recipient),
            )
        }
    }

    fun backFromNetmailCompose() {
        _state.update { it.copy(screen = AppScreen.Netmail, netmailCompose = NetmailComposeState()) }
    }

    fun setNetmailRecipient(value: String) =
        _state.update { it.copy(netmailCompose = it.netmailCompose.copy(recipient = value, error = null)) }

    fun setNetmailSubject(value: String) =
        _state.update { it.copy(netmailCompose = it.netmailCompose.copy(subject = value, error = null)) }

    fun setNetmailBody(value: String) =
        _state.update { it.copy(netmailCompose = it.netmailCompose.copy(body = value, error = null)) }

    fun sendNetmailCompose() {
        val current = _state.value.netmailCompose
        val opened = networkSession ?: return
        if (current.recipient.isBlank()) {
            _state.update { it.copy(netmailCompose = it.netmailCompose.copy(error = UiError.Failed("Recipient is required"))) }
            return
        }
        if (current.body.isBlank()) {
            _state.update { it.copy(netmailCompose = it.netmailCompose.copy(error = UiError.Failed("Message is empty"))) }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(netmailCompose = it.netmailCompose.copy(sending = true, error = null)) }
            try {
                val result = opened.sendNetmail(
                    recipient = current.recipient.trim(),
                    subject = current.subject.trim(),
                    body = current.body.trim(),
                )
                if (result.needsKey) {
                    _state.update {
                        it.copy(netmailCompose = it.netmailCompose.copy(sending = false, keyRequested = true))
                    }
                    return@launch
                }
                _state.update { it.copy(screen = AppScreen.Netmail, netmailCompose = NetmailComposeState()) }
                syncOnce(opened)
            } catch (e: Exception) {
                _state.update {
                    it.copy(netmailCompose = it.netmailCompose.copy(sending = false, error = UiError.Failed(e.message ?: "Failed")))
                }
            }
        }
    }

    // ---------------------------------------------------------------- settings

    fun openSettings() {
        _state.update { it.copy(screen = AppScreen.Settings) }
    }

    fun deleteActiveNetwork() {
        val id = _state.value.activeId ?: return
        deleteProfile(id)
    }

    // ------------------------------------------------------------ keys import

    /** Called with the text of a selected keys export file. */
    fun onImportFile(text: String?) {
        if (text.isNullOrBlank()) return
        importJsonText = text
        _state.update {
            it.copy(add = it.add.copy(awaitingImportPassword = true, importError = null))
        }
    }

    fun cancelImport() {
        importJsonText = null
        _state.update { it.copy(add = it.add.copy(awaitingImportPassword = false, importError = null)) }
    }

    /** Decrypts the imported keys with the export password. */
    fun confirmImportPassword(password: String) {
        val text = importJsonText ?: return
        try {
            val imported = KeystoreExportCodec.decode(text)
            val seed = imported.encryptedSeed?.let { KeyVault.decrypt(it, password) }
                ?: imported.plainSeed
                ?: error("no private key in the file")
            require(seed.size == 32) { "invalid key length" }
            importedSeed = seed
            importJsonText = null
            _state.update {
                it.copy(add = it.add.copy(awaitingImportPassword = false, imported = true, importError = null))
            }
        } catch (_: Exception) {
            _state.update { it.copy(add = it.add.copy(importError = UiError.WrongPassword)) }
        }
    }

    /** Builds the keys export JSON of the active profile with a new password. */
    fun exportKeysJson(password: String): String? {
        val profile = _state.value.activeProfile ?: return null
        val sessionPassword = session.passwordOrNull() ?: return null
        return runCatching {
            val seed = manager.seedOf(profile, sessionPassword)
            val publicKey = CryptoService.publicKeyFromSeed(seed)
            val encrypted = KeyVault.encrypt(seed, password)
            KeystoreExportCodec.encode(publicKey, encrypted)
        }.getOrNull()
    }

    // ------------------------------------------------------------------ helpers

    private fun MessageRow.toProtocolUi(): ProtocolUi? {
        val data = runCatching {
            messageJson.parseToJsonElement(dataJson).jsonObject
        }.getOrNull() ?: return null
        val innerType = (data["type"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val title = (data["message"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val variants = (data["variants"] as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            ?: emptyList()
        val vote = (data["vote"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val result = (data["result"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val detail = when {
            innerType == "vote" && vote.isNotEmpty() -> vote
            innerType == "vote" && vote.isEmpty() -> "retract"
            innerType == "result" -> result
            variants.isNotEmpty() -> variants.joinToString(", ")
            else -> ""
        }
        return ProtocolUi(
            messageId = messageId,
            type = type,
            echoName = echoName,
            senderName = senderName ?: senderAddress,
            timestamp = timestamp,
            title = title,
            detail = detail,
            variants = variants,
            isPoster = innerType == "poster" && variants.isNotEmpty(),
        )
    }

    private fun MessageRow.toUi(me: String, myName: String): MessageUi {
        val data = runCatching {
            messageJson.parseToJsonElement(dataJson).jsonObject
        }.getOrNull()
        val subject = (data?.get("subject") as? JsonPrimitive)?.contentOrNull.orEmpty()
        val body = (data?.get("body") as? JsonPrimitive)?.contentOrNull.orEmpty()
        val own = senderAddress == me
        val name = when {
            own && myName.isNotBlank() -> myName
            !senderName.isNullOrBlank() -> senderName!!
            else -> senderAddress
        }
        return MessageUi(
            messageId = messageId,
            senderName = name,
            senderAddress = senderAddress,
            recipientAddress = recipientAddress,
            timestamp = timestamp,
            subject = subject,
            body = body,
            own = own,
        )
    }

    private fun formatPolicy(payload: JsonElement): String {
        val policy = (payload as? JsonObject)?.get("policy") as? JsonObject
            ?: return payload.toString()
        val text = (policy["text"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val parameters = policy["parameters"]
        val pretty = parameters?.let {
            runCatching { prettyJson.encodeToString(JsonElement.serializer(), it) }.getOrNull()
        }.orEmpty()
        return buildString {
            append(text)
            if (pretty.isNotBlank()) {
                if (isNotEmpty()) append("\n\n")
                append(pretty)
            }
        }
    }

    companion object {
        fun factory(manager: NetworkManager, session: Session): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    AppViewModel(manager, session) as T
            }
    }
}
