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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import lan.fmnode.android.network.FmApiException
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
    Admin,
    EchoBrowser,
    Outbox,
    Settings,
}

enum class NetmailFolder { Inbox, Sent }

enum class ScanPurpose { AddNetwork, Merge }

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
    val subscribed: Boolean = true,
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
    val attachments: List<AttachmentUi> = emptyList(),
    val reactions: Map<String, Int> = emptyMap(),
    val myReaction: String? = null,
)

data class OutboxUi(
    val id: Long,
    val type: String,
    val echoName: String?,
    val recipient: String?,
    val attempts: Int,
    val lastError: String?,
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

data class AttachmentUi(
    val name: String,
    val type: String,
    val size: Int,
    val dataUrl: String,
)

data class NodeUi(
    val address: String,
    val url: String,
    val isRecoverNode: Boolean,
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
    val canRespondRefill: Boolean = false,
    val electionCandidate: String? = null,
)

data class ComposeState(
    val subject: String = "",
    val body: String = "",
    val attachments: List<AttachmentUi> = emptyList(),
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
    val allEchoes: List<EchoUi> = emptyList(),
    val outbox: List<OutboxUi> = emptyList(),
    val refillDialog: Boolean = false,
    val refillDate: String = "",
    val refillSending: Boolean = false,
    val refillError: UiError? = null,
    val showPolicyResign: Boolean = false,
    val policyResignText: String = "",
    val scanPurpose: ScanPurpose = ScanPurpose.AddNetwork,
    val voucherCode: String? = null,
    val voucherLoading: Boolean = false,
    val voucherError: UiError? = null,
    val adminNodes: List<NodeUi> = emptyList(),
    val adminLoading: Boolean = false,
    val adminMergeUrl: String = "",
    val adminMergeStatus: String? = null,
    val adminMergeError: UiError? = null,
    val excommTarget: String = "",
    val excommReason: String = "",
    val excommSending: Boolean = false,
    val excommStatus: String? = null,
    val excommError: UiError? = null,
    val createEchoDialog: Boolean = false,
    val createEchoName: String = "",
    val createEchoDescription: String = "",
    val createEchoError: UiError? = null,
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
                combine(
                    opened.database.echoDao().observeAll(),
                    opened.database.messageDao().observeUnreadSummary(opened.profile.address),
                ) { echoes, unread ->
                    val counts = unread.associate { it.echoName to it.count }
                    echoes.map { echo ->
                        EchoUi(
                            name = echo.name,
                            description = echo.description,
                            unread = counts[echo.name] ?: 0,
                            subscribed = echo.subscribed,
                        )
                    }
                }.collect { list -> _state.update { it.copy(allEchoes = list) } }
            }
            launch {
                opened.database.outboxDao().observeAll().collect { rows ->
                    _state.update {
                        it.copy(
                            outbox = rows.map { entry ->
                                OutboxUi(
                                    id = entry.id,
                                    type = entry.type,
                                    echoName = entry.echoName,
                                    recipient = entry.recipientAddress,
                                    attempts = entry.attempts,
                                    lastError = entry.lastError,
                                )
                            },
                        )
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
                            _state.update { it.copy(messages = mapMessages(rows, opened)) }
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
            if (e is FmApiException && e.next == "policy") {
                _state.update { it.copy(syncing = false, syncError = null) }
                startPolicyResign(opened)
                return
            }
            _state.update {
                it.copy(syncing = false, syncError = UiError.Failed(e.message ?: "Failed"))
            }
        }
    }

    private var pendingPolicy: PolicyInfo? = null

    private suspend fun startPolicyResign(opened: NetworkSession) {
        if (_state.value.showPolicyResign) return
        runCatching { opened.fetchPolicy() }
            .onSuccess { policy ->
                pendingPolicy = policy
                _state.update {
                    it.copy(showPolicyResign = true, policyResignText = formatPolicy(policy.payload))
                }
            }
            .onFailure { e ->
                _state.update { it.copy(syncError = UiError.Failed(e.message ?: "Failed")) }
            }
    }

    fun acceptPolicyResign() {
        val policy = pendingPolicy ?: return
        val opened = networkSession ?: return
        viewModelScope.launch {
            try {
                opened.resignPolicy(policy)
                manager.updateProfilePolicy(opened.profile.id, policy.payload.toString())
                pendingPolicy = null
                _state.update { it.copy(showPolicyResign = false, policyResignText = "") }
                syncOnce(opened)
            } catch (e: Exception) {
                _state.update { it.copy(syncError = UiError.Failed(e.message ?: "Failed")) }
            }
        }
    }

    fun cancelPolicyResign() {
        pendingPolicy = null
        _state.update { it.copy(showPolicyResign = false, policyResignText = "") }
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

    /** Adds files (already read from the system picker) to the composer. */
    fun addAttachments(files: List<Triple<String, String, ByteArray>>) {
        val existing = _state.value.compose.attachments
        val result = existing.toMutableList()
        var total = existing.sumOf { it.size }
        for ((name, type, bytes) in files) {
            if (bytes.size > MAX_ATTACHMENT_BYTES) {
                _state.update {
                    it.copy(compose = it.compose.copy(error = UiError.Failed("Attachment is too large (max 2 MiB)")))
                }
                return
            }
            total += bytes.size
            if (total > MAX_ATTACHMENTS_TOTAL) {
                _state.update {
                    it.copy(compose = it.compose.copy(error = UiError.Failed("Attachments are too large (max 6 MiB)")))
                }
                return
            }
            result += AttachmentUi(
                name = name,
                type = type,
                size = bytes.size,
                dataUrl = "data:$type;base64," + android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP),
            )
        }
        _state.update { it.copy(compose = it.compose.copy(attachments = result, error = null)) }
    }

    fun removeAttachment(index: Int) {
        _state.update {
            val list = it.compose.attachments.toMutableList()
            if (index in list.indices) list.removeAt(index)
            it.copy(compose = it.compose.copy(attachments = list))
        }
    }

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
                val attachments = if (current.compose.attachments.isEmpty()) {
                    null
                } else {
                    kotlinx.serialization.json.buildJsonArray {
                        current.compose.attachments.forEach { attachment ->
                            add(
                                buildJsonObject {
                                    put("name", attachment.name)
                                    put("type", attachment.type)
                                    put("size", attachment.size)
                                    put("data", attachment.dataUrl)
                                },
                            )
                        }
                    }
                }
                opened.postEcho(
                    echo.name,
                    current.compose.subject.trim(),
                    current.compose.body.trim(),
                    attachments = attachments,
                )
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

    fun voteElection(protocol: ProtocolUi, candidate: String) {
        val opened = networkSession ?: return
        viewModelScope.launch {
            runCatching { opened.postElectionVote(protocol.echoName, candidate) }
            syncOnce(opened)
        }
    }

    fun respondRefill(protocol: ProtocolUi) {
        val opened = networkSession ?: return
        viewModelScope.launch {
            runCatching { opened.postReaction(protocol.messageId, "👍", protocol.echoName) }
            syncOnce(opened)
        }
    }

    fun castVote(protocol: ProtocolUi, variant: String) {
        val opened = networkSession ?: return
        viewModelScope.launch {
            runCatching { opened.postVote(protocol.echoName, protocol.title, variant) }
            syncOnce(opened)
        }
    }

    // ------------------------------------------------------------------- QR

    fun openScanner(purpose: ScanPurpose = ScanPurpose.AddNetwork) {
        _state.update { it.copy(screen = AppScreen.QrScanner, scanPurpose = purpose) }
    }

    fun closeScanner() {
        val screen = if (_state.value.scanPurpose == ScanPurpose.Merge) AppScreen.Admin else AppScreen.AddNetwork
        _state.update { it.copy(screen = screen) }
    }

    fun onQrScanned(value: String) {
        if (_state.value.scanPurpose == ScanPurpose.Merge) {
            _state.update { it.copy(screen = AppScreen.Admin, adminMergeUrl = value.trim()) }
            startMerge()
            return
        }
        _state.update {
            it.copy(
                screen = AppScreen.AddNetwork,
                add = it.add.copy(url = value.trim(), error = null, step = AddStep.Url),
            )
        }
    }

    // ---------------------------------------------------------------- outbox

    fun openOutbox() {
        _state.update { it.copy(screen = AppScreen.Outbox) }
    }

    fun closeOutbox() {
        _state.update { it.copy(screen = AppScreen.Settings) }
    }

    fun retryOutbox() {
        val opened = networkSession ?: return
        viewModelScope.launch { syncOnce(opened) }
    }

    fun deleteOutboxEntry(id: Long) {
        val opened = networkSession ?: return
        viewModelScope.launch { opened.database.outboxDao().delete(id) }
    }

    // ---------------------------------------------------------------- refill

    fun openRefillDialog() {
        _state.update {
            it.copy(
                refillDialog = true,
                refillDate = java.time.LocalDate.now().toString(),
                refillError = null,
            )
        }
    }

    fun closeRefillDialog() {
        _state.update { it.copy(refillDialog = false) }
    }

    fun setRefillDate(value: String) {
        _state.update { it.copy(refillDate = value, refillError = null) }
    }

    fun sendRefill() {
        val echo = _state.value.selectedEcho ?: return
        val opened = networkSession ?: return
        val date = _state.value.refillDate.trim()
        viewModelScope.launch {
            _state.update { it.copy(refillSending = true, refillError = null) }
            try {
                val parsed = java.time.LocalDate.parse(date)
                opened.postRefillRequest(echo.name, parsed.toString() + "T00:00:00.000Z")
                _state.update { it.copy(refillDialog = false, refillSending = false) }
                syncOnce(opened)
            } catch (e: Exception) {
                _state.update {
                    it.copy(refillSending = false, refillError = UiError.Failed(e.message ?: "Failed"))
                }
            }
        }
    }

    // -------------------------------------------------------------- reactions

    fun toggleReaction(message: MessageUi, emoji: String) {
        val opened = networkSession ?: return
        val echo = _state.value.selectedEcho ?: return
        viewModelScope.launch {
            val next = if (message.myReaction == emoji) "" else emoji
            runCatching { opened.postReaction(message.messageId, next, echo.name) }
            syncOnce(opened)
        }
    }

    // ----------------------------------------------------------- echo browser

    fun openEchoBrowser() {
        _state.update { it.copy(screen = AppScreen.EchoBrowser) }
    }

    fun closeEchoBrowser() {
        _state.update { it.copy(screen = AppScreen.Echoes) }
    }

    fun toggleSubscription(echoName: String, subscribed: Boolean) {
        val opened = networkSession ?: return
        viewModelScope.launch {
            runCatching { opened.setSubscribed(echoName, subscribed) }
            runCatching { opened.refreshEchoes() }
        }
    }

    // ------------------------------------------------------------------- admin

    fun openAdmin() {
        _state.update { it.copy(screen = AppScreen.Admin, adminMergeStatus = null, adminMergeError = null) }
        loadNodes()
    }

    fun closeAdmin() {
        _state.update { it.copy(screen = AppScreen.Settings) }
    }

    fun loadNodes() {
        val opened = networkSession ?: return
        viewModelScope.launch {
            _state.update { it.copy(adminLoading = true) }
            try {
                val nodes = opened.nodeList().mapNotNull { node ->
                    val address = (node["address"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                    NodeUi(
                        address = address,
                        url = (node["url"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                        isRecoverNode = (node["is_recover_node"] as? JsonPrimitive)?.content == "true",
                    )
                }
                _state.update { it.copy(adminNodes = nodes, adminLoading = false) }
            } catch (e: Exception) {
                _state.update { it.copy(adminLoading = false, adminMergeError = UiError.Failed(e.message ?: "Failed")) }
            }
        }
    }

    fun setAdminMergeUrl(value: String) {
        _state.update { it.copy(adminMergeUrl = value, adminMergeError = null, adminMergeStatus = null) }
    }

    fun startMerge() {
        val opened = networkSession ?: return
        val url = _state.value.adminMergeUrl.trim()
        val invite = InviteParser.parse(url)
        if (invite == null) {
            _state.update { it.copy(adminMergeError = UiError.Failed("Invalid invite URL")) }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(adminLoading = true, adminMergeError = null, adminMergeStatus = null) }
            try {
                val stateKey = opened.startMerge(invite)
                _state.update {
                    it.copy(adminLoading = false, adminMergeStatus = "Merge proposed: $stateKey")
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(adminLoading = false, adminMergeError = UiError.Failed(e.message ?: "Failed"))
                }
            }
        }
    }

    fun setExcommTarget(value: String) =
        _state.update { it.copy(excommTarget = value, excommError = null) }

    fun setExcommReason(value: String) =
        _state.update { it.copy(excommReason = value, excommError = null) }

    fun submitExcommunicate() {
        val opened = networkSession ?: return
        val target = _state.value.excommTarget.trim()
        val reason = _state.value.excommReason.trim()
        if (target.isEmpty()) {
            _state.update { it.copy(excommError = UiError.Failed("Target address is required")) }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(excommSending = true, excommError = null, excommStatus = null) }
            try {
                val data = buildJsonObject {
                    put("echo", "Admin council")
                    put("proto", "excommunicate")
                    put("type", "poster")
                    put("from", opened.profile.address)
                    put("to", "all")
                    put("protoAddress", target)
                    put("message", reason)
                    put("variants", kotlinx.serialization.json.buildJsonArray {
                        add(JsonPrimitive("excommunicate"))
                        add(JsonPrimitive("read-only"))
                    })
                    put("date", java.time.Instant.now().toString())
                }.toString()
                opened.postProtocol("Admin council", "excommunicate", data)
                _state.update { it.copy(excommSending = false, excommStatus = "Proposal submitted") }
                syncOnce(opened)
            } catch (e: Exception) {
                _state.update {
                    it.copy(excommSending = false, excommError = UiError.Failed(e.message ?: "Failed"))
                }
            }
        }
    }

    // ------------------------------------------------------- create local echo

    fun openCreateEchoDialog() {
        _state.update {
            it.copy(createEchoDialog = true, createEchoName = "", createEchoDescription = "", createEchoError = null)
        }
    }

    fun closeCreateEchoDialog() {
        _state.update { it.copy(createEchoDialog = false) }
    }

    fun setCreateEchoName(value: String) =
        _state.update { it.copy(createEchoName = value, createEchoError = null) }

    fun setCreateEchoDescription(value: String) =
        _state.update { it.copy(createEchoDescription = value, createEchoError = null) }

    fun createLocalEcho() {
        val opened = networkSession ?: return
        val name = _state.value.createEchoName.trim()
        if (name.isEmpty()) {
            _state.update { it.copy(createEchoError = UiError.Failed("Name is required")) }
            return
        }
        viewModelScope.launch {
            try {
                opened.createLocalEcho(name, _state.value.createEchoDescription.trim())
                runCatching { opened.refreshEchoes() }
                _state.update { it.copy(createEchoDialog = false) }
            } catch (e: Exception) {
                _state.update { it.copy(createEchoError = UiError.Failed(e.message ?: "Failed")) }
            }
        }
    }

    fun createVoucher() {
        val opened = networkSession ?: return
        viewModelScope.launch {
            _state.update { it.copy(voucherLoading = true, voucherError = null) }
            try {
                val code = opened.createVoucher()
                _state.update { it.copy(voucherLoading = false, voucherCode = code) }
            } catch (e: Exception) {
                _state.update {
                    it.copy(voucherLoading = false, voucherError = UiError.Failed(e.message ?: "Failed"))
                }
            }
        }
    }

    fun dismissVoucher() {
        _state.update { it.copy(voucherCode = null) }
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

    private fun mapMessages(rows: List<MessageRow>, opened: NetworkSession): List<MessageUi> {
        val me = opened.profile.address
        val myName = opened.profile.displayName
        // target message -> sender -> (emoji, timestamp); last reaction wins.
        val reactions = mutableMapOf<String, MutableMap<String, Pair<String, Long>>>()
        val messageRows = mutableListOf<MessageRow>()
        for (row in rows) {
            val data = runCatching {
                messageJson.parseToJsonElement(row.dataJson).jsonObject
            }.getOrNull()
            val reaction = (data?.get("reaction") as? JsonPrimitive)?.contentOrNull.orEmpty()
            val target = (data?.get("reactionMessageId") as? JsonPrimitive)?.contentOrNull.orEmpty()
            if (target.isNotEmpty()) {
                val bySender = reactions.getOrPut(target) { mutableMapOf() }
                val previous = bySender[row.senderAddress]
                if (previous == null || row.timestamp >= previous.second) {
                    if (reaction.isEmpty()) bySender.remove(row.senderAddress)
                    else bySender[row.senderAddress] = reaction to row.timestamp
                }
                continue
            }
            messageRows += row
        }
        val protocolTypes = setOf(
            "vote", "election", "excommunicate", "change_policy", "change_rules",
            "remove_echo", "refill", "merge", "conflict", "recover", "appeal",
            "remote_subscribe",
        )
        return messageRows
            .filter { it.type !in protocolTypes }
            .map { row ->
            val bySender = reactions[row.messageId]
            val counts = mutableMapOf<String, Int>()
            var mine: String? = null
            bySender?.forEach { (sender, pair) ->
                counts[pair.first] = (counts[pair.first] ?: 0) + 1
                if (sender == me) mine = pair.first
            }
            row.toUi(me, myName).copy(reactions = counts, myReaction = mine)
        }
    }

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
            canRespondRefill = type == "refill" && innerType == "request",
            electionCandidate = if (type == "election" && innerType == "poster") {
                (data["from"] as? JsonPrimitive)?.contentOrNull ?: senderAddress
            } else {
                null
            },
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
        val attachments = (data?.get("attachments") as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { element ->
                val att = element as? JsonObject ?: return@mapNotNull null
                AttachmentUi(
                    name = (att["name"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                    type = (att["type"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                    size = (att["size"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0,
                    dataUrl = (att["data"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                )
            }
            ?: emptyList()
        return MessageUi(
            messageId = messageId,
            senderName = name,
            senderAddress = senderAddress,
            recipientAddress = recipientAddress,
            timestamp = timestamp,
            subject = subject,
            body = body,
            own = own,
            attachments = attachments,
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
        private const val MAX_ATTACHMENT_BYTES = 2 * 1024 * 1024
        private const val MAX_ATTACHMENTS_TOTAL = 6 * 1024 * 1024

        fun factory(manager: NetworkManager, session: Session): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    AppViewModel(manager, session) as T
            }
    }
}
