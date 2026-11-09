package lan.fmnode.android.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import lan.fmnode.android.network.FmApiException
import lan.fmnode.android.network.Invite
import lan.fmnode.android.network.InviteParser
import lan.fmnode.android.network.NetworkManager
import lan.fmnode.android.network.NetworkSession
import lan.fmnode.android.network.PolicyInfo
import lan.fmnode.android.session.Session
import lan.fmnode.android.storage.BackupService
import lan.fmnode.android.storage.BlogEntity
import lan.fmnode.android.storage.BlogPostEntity
import lan.fmnode.android.storage.KeyVault
import lan.fmnode.android.storage.KeystoreExportCodec
import lan.fmnode.android.storage.MessageRow
import lan.fmnode.android.storage.NetmailFolderEntity
import lan.fmnode.android.storage.ProfileEntity
import lan.fmnode.android.crypto.CryptoService
import lan.fmnode.android.crypto.Encoding

enum class AppScreen {
    Unlock, Networks, AddNetwork,
    Echoes, Echo, Compose, MessageDetail,
    Blog, BlogCompose,
    Netmail, NetmailDetail, NetmailCompose,
    Search, Protocols,
    QrScanner,
    Admin,
    EchoBrowser,
    Outbox,
    Settings,
}

/** How the conversation list is drawn: every message or one card per file bundle (#9). */
enum class EchoMessageView {
    All,
    Attachments,
}

const val NETMAIL_INBOX = "inbox"
const val NETMAIL_SENT = "sent"
const val NETMAIL_ECHO = "__netmail__"

data class NetmailFolderUi(
    val slug: String,
    val label: String,
    val custom: Boolean = false,
    val count: Int = 0,
)

enum class CreateEchoType { LocalEcho, Blog }

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
    val isBlog: Boolean = false,
    val blogId: String? = null,
    val serverId: Int? = null,
)

data class BlogUi(
    val id: String,
    val name: String,
    val description: String,
    val createdAt: Long,
    val updatedAt: Long,
)

data class BlogPostUi(
    val id: String,
    val blogId: String,
    val title: String,
    val body: String,
    val attachments: List<AttachmentUi>,
    val timestamp: Long,
)

data class BlogComposeState(
    val postId: String? = null,
    val title: String = "",
    val body: String = "",
    val attachments: List<AttachmentUi> = emptyList(),
    val saving: Boolean = false,
    val error: UiError? = null,
)

data class MessageVersionUi(
    val subject: String,
    val body: String,
    val timestamp: Long,
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
    val folderSlug: String? = null,
    val attachments: List<AttachmentUi> = emptyList(),
    val reactions: Map<String, Int> = emptyMap(),
    val myReaction: String? = null,
    val versions: List<MessageVersionUi> = emptyList(),
)

data class ContactUi(
    val address: String,
    val name: String,
)

/** One network of the vault with its echoes, shown as a group (#15). */
data class NetworkGroupUi(
    val profileId: String,
    val title: String,
    val address: String,
    /** Host and port of the node, keeps same-host networks apart. */
    val endpoint: String,
    val isActive: Boolean,
    val echoes: List<EchoUi>,
) {
    val unread: Int get() = echoes.sumOf { it.unread }
}

/** "host:port" of a node API URL, for telling networks apart on screen (#15). */
internal fun endpointLabel(apiUrl: String): String =
    runCatching {
        val uri = java.net.URI(apiUrl)
        val host = uri.host ?: return@runCatching apiUrl
        if (uri.port == -1) host else "$host:${uri.port}"
    }.getOrDefault(apiUrl)

/** A network that was renumbered by a merge, shown once (#11). */
data class AddressNoticeUi(
    val network: String,
    val address: String,
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
    val isOpenNode: Boolean = false,
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
    /** Echoes of every unlocked network, grouped by network (#15). */
    val networks: List<NetworkGroupUi> = emptyList(),
    val blogs: List<BlogUi> = emptyList(),
    val selectedEcho: EchoUi? = null,
    val selectedBlog: BlogUi? = null,
    val blogPosts: List<BlogPostUi> = emptyList(),
    val blogCompose: BlogComposeState = BlogComposeState(),
    val messages: List<MessageUi> = emptyList(),
    val compose: ComposeState = ComposeState(),
    val netmail: List<MessageUi> = emptyList(),
    val netmailFolders: List<NetmailFolderUi> = emptyList(),
    val netmailFolder: String = NETMAIL_INBOX,
    val netmailFolderDialog: Boolean = false,
    val netmailFolderName: String = "",
    val netmailFolderError: UiError? = null,
    val netmailMoveMessageId: String? = null,
    val selectedNetmail: MessageUi? = null,
    val netmailCompose: NetmailComposeState = NetmailComposeState(),
    val protocols: List<ProtocolUi> = emptyList(),
    val messageView: EchoMessageView = EchoMessageView.All,
    val selectedMessage: MessageUi? = null,
    val searchQuery: String = "",
    val searchResults: List<SearchResultUi> = emptyList(),
    val searchLoading: Boolean = false,
    val allEchoes: List<EchoUi> = emptyList(),
    val contacts: List<ContactUi> = emptyList(),
    val contactsDialog: Boolean = false,
    val selectionMode: Boolean = false,
    val selectedMessages: Set<String> = emptySet(),
    val newContactAddress: String = "",
    val newContactName: String = "",
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
    val recoveryDialog: Boolean = false,
    val recoveryUrl: String = "",
    val recoveryLoading: Boolean = false,
    val recoveryError: UiError? = null,
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
    val createEchoType: CreateEchoType = CreateEchoType.LocalEcho,
    val createEchoName: String = "",
    val createEchoDescription: String = "",
    val createEchoError: UiError? = null,
    val policyViewer: Boolean = false,
    val policyViewText: String = "",
    val removeEchoDialog: Boolean = false,
    val removeEchoName: String = "",
    val removeEchoReason: String = "",
    val removeEchoSending: Boolean = false,
    val removeEchoStatus: String? = null,
    val removeEchoError: UiError? = null,
    val policyEditDialog: Boolean = false,
    val policyEditText: String = "",
    val policyEditParams: String = "",
    val policyEditAllChunks: Boolean = false,
    val policyEditSending: Boolean = false,
    val policyEditStatus: String? = null,
    val policyEditError: UiError? = null,
    val moderationTarget: MessageUi? = null,
    val moderationSeverity: String = "warning",
    val moderationDuration: String = "24",
    val moderationReason: String = "",
    val moderationSending: Boolean = false,
    val moderationError: UiError? = null,
    val add: AddNetworkState = AddNetworkState(),
    val unlockError: UiError? = null,
    val syncError: UiError? = null,
    val syncing: Boolean = false,
    /** Reason the node rejected a message, shown until dismissed (#14). */
    val sendErrors: List<String> = emptyList(),
    /** New address after a merge, shown as a one-time notice (#11). */
    val addressNotice: AddressNoticeUi? = null,
    val logoutDialog: Boolean = false,
    val backupStatus: String? = null,
    val backupError: UiError? = null,
)

private fun slugifyNetmailFolder(name: String): String =
    name.trim().lowercase()
        .replace(Regex("[^a-z0-9а-яё]+"), "_")
        .trim('_')
        .ifEmpty { "folder_${System.currentTimeMillis()}" }

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

    /** Parsed full export waiting for the password, if the selected file was one. */
    private var importFull: lan.fmnode.android.storage.ImportedFullBackup? = null
    private var networkSession: NetworkSession? = null
    private var flowsJob: Job? = null
    private var syncJob: Job? = null

    /** Every unlocked network (#15): echo groups are read from all of them. */
    private val sessions = linkedMapOf<String, NetworkSession>()
    private val groupJobs = linkedMapOf<String, Job>()
    private val groupEchoes = mutableMapOf<String, List<EchoUi>>()

    init {
        // A vault exists but is locked: the password is required before any
        // profile or message data can be read (#3).
        _state.update { it.copy(screen = if (manager.vaultExists()) AppScreen.Unlock else AppScreen.Networks) }
        viewModelScope.launch {
            manager.profiles.collect { profiles ->
                _state.update { state -> rebuildNetworkGroups(state.copy(profiles = profiles)) }
                if (profiles.isEmpty() && !manager.vaultExists()) {
                    closeSession()
                    _state.update { it.copy(screen = AppScreen.Networks, echoes = emptyList()) }
                }
            }
        }
        viewModelScope.launch {
            if (!session.isUnlocked) return@launch
            val id = manager.activeProfileId()
            val profile = id?.let { manager.activeProfile() }
            _state.update { it.copy(activeId = id, activeProfile = profile) }
        }
    }

    // ------------------------------------------------------------ vault/session

    fun unlock(password: String) {
        viewModelScope.launch {
            if (!manager.openVault(password)) {
                _state.update { it.copy(unlockError = UiError.WrongPassword) }
                return@launch
            }
            session.setPassword(password)
            val profile = manager.activeProfile() ?: manager.firstProfile()
            if (profile == null) {
                _state.update {
                    it.copy(unlocked = true, unlockError = null, screen = AppScreen.Networks, activeProfile = null)
                }
                return@launch
            }
            _state.update {
                it.copy(unlocked = true, unlockError = null, activeId = profile.id, activeProfile = profile)
            }
            openActiveSession()
        }
    }

    fun lock() = lockVault()

    /** Shows the logout confirmation (the app always requires the password on entry). */
    fun requestLogout() = _state.update { it.copy(logoutDialog = true) }

    fun cancelLogout() = _state.update { it.copy(logoutDialog = false) }

    /** Logout: same as fm-app — drop decrypted data and require the password. */
    fun confirmLogout() {
        _state.update { it.copy(logoutDialog = false) }
        lockVault()
    }

    private fun lockVault() {
        closeSession()
        manager.closeVault()
        session.lock()
        _state.update {
            clearDecryptedState(
                it.copy(
                    unlocked = false,
                    screen = if (manager.vaultExists()) AppScreen.Unlock else AppScreen.Networks,
                    unlockError = null,
                    activeId = null,
                ),
            )
        }
    }

    /** Drops every decrypted value from the UI state. */
    private fun clearDecryptedState(state: UiState): UiState = state.copy(
        activeProfile = if (state.unlocked) state.activeProfile else null,
        echoes = emptyList(),
        blogs = emptyList(),
        selectedEcho = null,
        selectedBlog = null,
        blogPosts = emptyList(),
        blogCompose = BlogComposeState(),
        messages = emptyList(),
        compose = ComposeState(),
        netmail = emptyList(),
        netmailFolders = emptyList(),
        netmailFolder = NETMAIL_INBOX,
        netmailFolderDialog = false,
        netmailFolderName = "",
        netmailFolderError = null,
        netmailMoveMessageId = null,
        selectedNetmail = null,
        netmailCompose = NetmailComposeState(),
        protocols = emptyList(),
        searchQuery = "",
        searchResults = emptyList(),
        searchLoading = false,
        allEchoes = emptyList(),
        contacts = emptyList(),
        contactsDialog = false,
        selectionMode = false,
        selectedMessages = emptySet(),
        outbox = emptyList(),
        refillDialog = false,
        showPolicyResign = false,
        adminNodes = emptyList(),
        adminMergeStatus = null,
        adminMergeError = null,
        excommTarget = "",
        excommReason = "",
        excommStatus = null,
        excommError = null,
        moderationTarget = null,
        createEchoDialog = false,
        createEchoType = CreateEchoType.LocalEcho,
        policyViewer = false,
        policyEditDialog = false,
        removeEchoDialog = false,
        syncing = false,
        syncError = null,
        logoutDialog = false,
        backupStatus = null,
        backupError = null,
    )

    /** Opens the message database/API session of the active profile. */
    private suspend fun openActiveSession() {
        val password = session.passwordOrNull() ?: return
        val profile = manager.activeProfile()
        if (profile == null) {
            closeSession()
            _state.update { it.copy(screen = AppScreen.Networks) }
            return
        }
        _state.update {
            it.copy(
                activeProfile = profile,
                activeId = profile.id,
                syncing = true,
                syncError = null,
            )
        }
        try {
            val opened = sessionFor(profile, password)
            networkSession = opened
            // Local work first: the echo list is stored on the device, so the
            // network must never keep the user off the app. A node that is down,
            // slow or unhappy about the policy only fills syncError later.
            collectSessionFlows(opened)
            _state.update { it.copy(screen = AppScreen.Echoes, syncing = true, syncError = null) }
            // The other networks of the vault are opened in the background so
            // they never delay the active one (#15).
            viewModelScope.launch { openOtherSessions(password, profile.id) }
            runCatching { opened.refreshEchoes() }
                .onFailure { e -> _state.update { it.copy(syncError = UiError.Failed(e.message ?: "Failed")) } }
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

    /**
     * Opens the sessions of every other network of the vault (#15). Their echo
     * lists feed the grouped echo screen and their unread counters stay fresh
     * through a background sync; nothing of them is shown in the active screens.
     */
    private suspend fun openOtherSessions(password: String, activeProfileId: String) {
        val profiles: List<ProfileEntity> = runCatching { manager.profilesFirst() }.getOrDefault(emptyList())
        for (profile in profiles) {
            if (profile.id == activeProfileId) continue
            val other = runCatching { sessionFor(profile, password) }.getOrNull() ?: continue
            // A network that was never opened on this device has an empty echo
            // table; fill it in the background so its group is not blank (#15).
            viewModelScope.launch {
                runCatching { other.refreshEchoes() }
                runCatching { other.sync() }
            }
        }
    }

    /** Returns the open session of [profile], opening and observing it once. */
    private suspend fun sessionFor(profile: ProfileEntity, password: String): NetworkSession {
        sessions[profile.id]?.let { return it }
        val opened = manager.openSession(profile, password)
        sessions[profile.id] = opened
        startGroupCollector(opened)
        return opened
    }

    /** Keeps the echo group of one network up to date (#15). */
    private fun startGroupCollector(opened: NetworkSession) {
        groupJobs[opened.profile.id]?.cancel()
        groupJobs[opened.profile.id] = viewModelScope.launch {
            combine(
                opened.database.echoDao().observeSubscribed(),
                opened.database.blogDao().observeAll(),
                opened.database.messageDao().observeUnreadSummary(opened.profile.address),
            ) { echoes, blogs, unread ->
                val counts = unread.associate { it.echoName to it.count }
                blogs.map { blog ->
                    EchoUi(
                        name = blog.name,
                        description = blog.description,
                        unread = 0,
                        isBlog = true,
                        blogId = blog.id,
                    )
                } + echoes.map { echo ->
                    EchoUi(
                        name = echo.name,
                        description = echo.description,
                        unread = counts[echo.name] ?: 0,
                        subscribed = echo.subscribed,
                        serverId = echo.serverId,
                    )
                }
            }.catch { }.collect { list ->
                groupEchoes[opened.profile.id] = list
                _state.update { state -> rebuildNetworkGroups(state) }
            }
        }
    }

    /** Rebuilds the grouped echo list from the per-network echo cache (#15). */
    private fun rebuildNetworkGroups(state: UiState): UiState {
        val groups = state.profiles.mapNotNull { profile ->
            val echoes = groupEchoes[profile.id] ?: return@mapNotNull null
            NetworkGroupUi(
                profileId = profile.id,
                title = profile.title,
                address = profile.address,
                endpoint = endpointLabel(profile.apiUrl),
                isActive = profile.id == state.activeId,
                echoes = echoes,
            )
        }
        return state.copy(networks = groups)
    }

    private fun collectSessionFlows(opened: NetworkSession) {
        flowsJob?.cancel()
        flowsJob = viewModelScope.launch {
            launch {
                combine(
                    opened.database.echoDao().observeSubscribed(),
                    opened.database.blogDao().observeAll(),
                    opened.database.messageDao().observeUnreadSummary(opened.profile.address),
                ) { echoes, blogs, unread ->
                    val counts = unread.associate { it.echoName to it.count }
                    val blogRows = blogs.map { blog ->
                        BlogUi(blog.id, blog.name, blog.description, blog.createdAt, blog.updatedAt)
                    }
                    val rows = blogRows.map { blog ->
                        EchoUi(
                            name = blog.name,
                            description = blog.description,
                            unread = 0,
                            isBlog = true,
                            blogId = blog.id,
                        )
                    } + echoes.map { echo ->
                        EchoUi(
                            name = echo.name,
                            description = echo.description,
                            unread = counts[echo.name] ?: 0,
                            serverId = echo.serverId,
                        )
                    }
                    rows to blogRows
                }.catch { }.collect { (list, blogRows) ->
                    _state.update { s ->
                        val selected = s.selectedEcho?.let { current ->
                            list.firstOrNull {
                                current.serverId != null && it.serverId == current.serverId
                            } ?: list.firstOrNull { it.name == current.name }
                        }
                        s.copy(echoes = list, blogs = blogRows, selectedEcho = selected)
                    }
                }
            }
            launch {
                combine(
                    opened.database.echoDao().observeAll(),
                    opened.database.blogDao().observeAll(),
                    opened.database.messageDao().observeUnreadSummary(opened.profile.address),
                ) { echoes, blogs, unread ->
                    val counts = unread.associate { it.echoName to it.count }
                    blogs.map { blog ->
                        EchoUi(
                            name = blog.name,
                            description = blog.description,
                            unread = 0,
                            isBlog = true,
                            blogId = blog.id,
                        )
                    } + echoes.map { echo ->
                        EchoUi(
                            name = echo.name,
                            description = echo.description,
                            unread = counts[echo.name] ?: 0,
                            subscribed = echo.subscribed,
                            serverId = echo.serverId,
                        )
                    }
                }.catch { }.collect { list -> _state.update { it.copy(allEchoes = list) } }
            }
            launch {
                opened.database.netmailFolderDao().observeAll().catch { }.collect { folders ->
                    _state.update {
                        it.copy(netmailFolders = folders.map { folder ->
                            NetmailFolderUi(folder.slug, folder.name, custom = true)
                        })
                    }
                }
            }
            launch {
                _state.map { it.selectedBlog?.id }.distinctUntilChanged().collectLatest { blogId ->
                    if (blogId == null) {
                        _state.update { it.copy(blogPosts = emptyList()) }
                        return@collectLatest
                    }
                    opened.database.blogPostDao().observeByBlog(blogId).catch { }.collect { posts ->
                        _state.update { it.copy(blogPosts = posts.map { it.toUi() }) }
                    }
                }
            }
            launch {
                opened.database.contactDao().observeAll().catch { }.collect { rows ->
                    _state.update {
                        it.copy(contacts = rows.map { contact -> ContactUi(contact.address, contact.name) })
                    }
                }
            }
            launch {
                opened.database.outboxDao().observeAll().catch { }.collect { rows ->
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
                opened.database.messageDao().observeProtocols().catch { }.collect { rows ->
                    _state.update { it.copy(protocols = rows.mapNotNull { row -> row.toProtocolUi() }) }
                }
            }
            launch {
                opened.database.messageDao().observeEchoRows(NetworkSession.NETMAIL_ECHO).catch { }.collect { rows ->
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
                        opened.database.messageDao().observeEchoRows(echoName).catch { }.collect { rows ->
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
        groupJobs.values.forEach { it.cancel() }
        groupJobs.clear()
        sessions.clear()
        groupEchoes.clear()
        networkSession = null
        _state.update { it.copy(networks = emptyList()) }
    }

    /**
     * Keeps every network fresh: the active one drives the open screens, the
     * others only refresh their echo groups and unread counters (#15).
     */
    private fun startSyncLoop(opened: NetworkSession) {
        syncJob?.cancel()
        syncJob = viewModelScope.launch {
            var tick = 0
            while (isActive) {
                delay(30_000)
                tick++
                if (networkSession?.profile?.id == opened.profile.id || networkSession == opened) {
                    syncOnce(opened)
                }
                if (tick % 2 == 0) syncOtherSessions()
            }
        }
    }

    /**
     * Background sync of the networks that are not on screen (#15). Their
     * rejections and renumbering must reach the same indicators as the active
     * one: a message written in another network is still a message here.
     */
    private suspend fun syncOtherSessions() {
        val activeId = networkSession?.profile?.id
        for ((id, opened) in sessions.toList()) {
            if (id == activeId) continue
            runCatching {
                val outcome = opened.sync()
                collectSendErrors(outcome.errors + opened.drainErrors())
                runCatching { opened.refreshEchoes() }
                // A merge can renumber users of any network (#11).
                refreshIdentity(opened)
            }
        }
    }

    /** Shows the reasons the node rejected messages, newest first (#14). */
    private fun collectSendErrors(errors: List<String>) {
        if (errors.isEmpty()) return
        _state.update { state ->
            state.copy(sendErrors = (errors + state.sendErrors).distinct().take(MAX_SEND_ERRORS))
        }
    }

    private suspend fun syncOnce(opened: NetworkSession) {
        _state.update { it.copy(syncing = true) }
        try {
            val outcome = opened.sync()
            // The node delivers server-side rejections and takes no other
            // notice of them, so they become a visible indicator (#14).
            collectSendErrors(outcome.errors + opened.drainErrors())
            // A merge can renumber users while the app is running (#11).
            refreshIdentity(opened)
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

    /**
     * Adopts an address the node assigned after a merge (#11). The profile row,
     * the live session and the UI state are updated, queued messages are
     * re-signed on the next flush and the user gets a one-time notice.
     */
    private suspend fun refreshIdentity(opened: NetworkSession) {
        val identity = runCatching { opened.identity() }.getOrNull() ?: return
        val oldAddress = opened.profile.address
        val newAddress = identity.address
        if (newAddress.isEmpty() || newAddress == oldAddress) return
        opened.applyAddressChange(newAddress)
        manager.updateProfileAddress(opened.profile.id, newAddress)
        _state.update { state ->
            val updatedProfile = state.activeProfile?.let { current ->
                if (current.id == opened.profile.id) current.copy(address = newAddress) else current
            }
            state.copy(
                activeProfile = updatedProfile,
                addressNotice = AddressNoticeUi(network = opened.profile.title, address = newAddress),
                // Errors caused by the stale address are no longer relevant.
                sendErrors = state.sendErrors.filterNot { it.contains(oldAddress) },
            )
        }
    }

    fun dismissAddressNotice() = _state.update { it.copy(addressNotice = null) }

    fun dismissSendErrors() = _state.update { it.copy(sendErrors = emptyList()) }

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
        _state.update {
            it.copy(
                screen = AppScreen.Networks,
                selectedEcho = null,
                selectedMessage = null,
                messages = emptyList(),
            )
        }
    }

    fun backFromNetworks() {
        // Move through the screen history first (#8): with an open session the
        // network list is not a root screen.
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

    /**
     * Opens an echo of any network from the grouped list (#15). A conference of
     * another network first makes that network active, because echo ids, drafts
     * and outbox entries are per-network.
     */
    fun selectEchoInNetwork(profileId: String, echoName: String) {
        if (profileId == _state.value.activeId) {
            selectEcho(echoName)
            return
        }
        viewModelScope.launch {
            manager.setActive(profileId)
            _state.update {
                it.copy(
                    activeId = profileId,
                    activeProfile = runCatching { manager.profileById(profileId) }.getOrNull(),
                )
            }
            openActiveSession()
            selectEcho(echoName)
        }
    }

    fun deleteProfile(id: String) {
        viewModelScope.launch {
            val deletingCurrent = id == _state.value.activeProfile?.id || networkSession?.profile?.id == id
            if (deletingCurrent) {
                closeSession()
                yield()
            } else {
                // A non-active network keeps its own session and echo group.
                groupJobs.remove(id)?.cancel()
                sessions.remove(id)
                groupEchoes.remove(id)
            }
            manager.deleteProfile(id)
            if (manager.profileCount() == 0) {
                manager.clearVault()
                session.lock()
                _state.update {
                    clearDecryptedState(
                        it.copy(
                            unlocked = false,
                            activeId = null,
                            activeProfile = null,
                            screen = AppScreen.Networks,
                            unlockError = null,
                        ),
                    )
                }
                return@launch
            }
            val active = manager.activeProfileId()
            _state.update { it.copy(activeId = active) }
            if (deletingCurrent && active != null) {
                openActiveSession()
            }
        }
    }

    fun selectEcho(name: String) {
        val blog = _state.value.blogs.firstOrNull { it.name == name }
        if (blog != null) {
            _state.update { it.copy(screen = AppScreen.Blog, selectedBlog = blog, selectedEcho = null) }
            return
        }
        viewModelScope.launch {
            val opened = networkSession ?: return@launch
            val echo = _state.value.echoes.firstOrNull { it.name == name } ?: return@launch
            runCatching { opened.markRead(name, System.currentTimeMillis() / 1000) }
            _state.update {
                it.copy(
                    screen = AppScreen.Echo,
                    selectedEcho = echo,
                    selectedBlog = null,
                    selectedMessage = null,
                    messageView = EchoMessageView.All,
                )
            }
        }
    }

    fun backFromEcho() {
        _state.update {
            it.copy(
                screen = AppScreen.Echoes,
                selectedEcho = null,
                selectedBlog = null,
                selectedMessage = null,
                messageView = EchoMessageView.All,
                messages = emptyList(),
            )
        }
    }

    /** Switches the conversation list between all messages and file bundles (#9). */
    fun setMessageView(view: EchoMessageView) = _state.update { it.copy(messageView = view) }

    /** Dedicated read-only screen for one message, including full-size attachments (#9). */
    fun openMessage(message: MessageUi) =
        _state.update { it.copy(screen = AppScreen.MessageDetail, selectedMessage = message) }

    fun backFromMessage() = _state.update {
        it.copy(
            screen = if (it.selectedEcho != null) AppScreen.Echo else AppScreen.Echoes,
            selectedMessage = null,
        )
    }

    fun backFromBlog() {
        _state.update { it.copy(screen = AppScreen.Echoes, selectedBlog = null, blogPosts = emptyList()) }
    }

    fun openCompose() {
        val echo = _state.value.selectedEcho ?: return
        val opened = networkSession
        _state.update { it.copy(screen = AppScreen.Compose, compose = ComposeState()) }
        if (opened != null) {
            viewModelScope.launch {
                opened.database.draftDao().get(echo.name)?.let { draft ->
                    _state.update {
                        if (it.screen == AppScreen.Compose) {
                            it.copy(compose = it.compose.copy(subject = draft.subject, body = draft.body))
                        } else {
                            it
                        }
                    }
                }
            }
        }
    }

    fun backFromCompose() {
        val echo = _state.value.selectedEcho
        val opened = networkSession
        val compose = _state.value.compose
        if (echo != null && opened != null && (compose.subject.isNotBlank() || compose.body.isNotBlank())) {
            viewModelScope.launch {
                opened.database.draftDao().upsert(
                    lan.fmnode.android.storage.DraftEntity(
                        echoName = echo.name,
                        subject = compose.subject,
                        body = compose.body,
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
            }
        }
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
        // A file bundle without any text is a valid message (#9): the composer
        // only rejects sends that would produce an empty card.
        if (!MessageMapping.canSend(current.compose.body, current.compose.attachments.size)) {
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
                    MessageMapping.subjectFor(current.compose.subject, current.compose.attachments.map { it.name }),
                    current.compose.body.trim(),
                    attachments = attachments,
                )
                // The node can reject the message in the response to this very
                // send; show why instead of leaving it as a silent no-op (#14).
                collectSendErrors(opened.drainErrors())
                runCatching { opened.database.draftDao().delete(echo.name) }
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

    // ----------------------------------------------------------------- contacts

    fun openContacts() = _state.update { it.copy(contactsDialog = true) }

    fun closeContacts() = _state.update { it.copy(contactsDialog = false) }

    fun setNewContactAddress(value: String) =
        _state.update { it.copy(newContactAddress = value) }

    fun setNewContactName(value: String) =
        _state.update { it.copy(newContactName = value) }

    fun addContact() {
        val opened = networkSession ?: return
        val address = _state.value.newContactAddress.trim()
        if (address.isEmpty()) return
        viewModelScope.launch {
            opened.database.contactDao().upsert(
                lan.fmnode.android.storage.ContactEntity(
                    address = address,
                    name = _state.value.newContactName.trim(),
                    updatedAt = System.currentTimeMillis(),
                ),
            )
            _state.update { it.copy(newContactAddress = "", newContactName = "") }
        }
    }

    fun deleteContact(address: String) {
        val opened = networkSession ?: return
        viewModelScope.launch { opened.database.contactDao().delete(address) }
    }

    fun pickContact(address: String) {
        _state.update {
            it.copy(
                contactsDialog = false,
                netmailCompose = it.netmailCompose.copy(recipient = address, error = null),
            )
        }
    }

    // --------------------------------------------------------------- forwarding

    fun toggleSelectionMode() {
        _state.update {
            if (it.selectionMode) {
                it.copy(selectionMode = false, selectedMessages = emptySet())
            } else {
                it.copy(selectionMode = true, selectedMessages = emptySet())
            }
        }
    }

    fun toggleMessageSelection(messageId: String) {
        _state.update {
            val next = it.selectedMessages.toMutableSet()
            if (!next.add(messageId)) next.remove(messageId)
            it.copy(selectedMessages = next)
        }
    }

    fun forwardSelected() {
        val state = _state.value
        val selected = state.messages.filter { it.messageId in state.selectedMessages }
        if (selected.isEmpty()) return
        val quote = buildString {
            selected.forEachIndexed { index, message ->
                if (index > 0) append("\n")
                append("> ")
                append(message.senderName)
                append(" (")
                append(message.senderAddress)
                append("):\n")
                message.body.lineSequence().forEach { line ->
                    append("> ")
                    append(line)
                    append('\n')
                }
            }
        }
        _state.update {
            it.copy(
                screen = AppScreen.NetmailCompose,
                selectionMode = false,
                selectedMessages = emptySet(),
                netmailCompose = NetmailComposeState(
                    subject = "Fwd: ${selected.size} message(s)",
                    body = quote,
                ),
            )
        }
    }

    fun forwardMessage(message: MessageUi) {
        val quote = buildString {
            append("> ")
            append(message.senderName)
            append(" (")
            append(message.senderAddress)
            append("):\n")
            message.body.lineSequence().forEach { line ->
                append("> ")
                append(line)
                append('\n')
            }
        }
        _state.update {
            it.copy(
                screen = AppScreen.NetmailCompose,
                netmailCompose = NetmailComposeState(
                    subject = "Fwd: ${message.subject}",
                    body = quote,
                ),
            )
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

    // --------------------------------------------------------------- recovery

    fun openRecovery() {
        _state.update { it.copy(recoveryDialog = true, recoveryError = null) }
    }

    fun closeRecovery() {
        if (!_state.value.recoveryLoading) {
            _state.update { it.copy(recoveryDialog = false, recoveryError = null) }
        }
    }

    fun setRecoveryUrl(value: String) {
        _state.update { it.copy(recoveryUrl = value, recoveryError = null) }
    }

    fun submitRecovery() {
        val profile = _state.value.activeProfile ?: return
        val password = session.passwordOrNull() ?: return
        val invite = InviteParser.parse(_state.value.recoveryUrl.trim())
        if (invite == null || invite.address.isNullOrBlank()) {
            _state.update { it.copy(recoveryError = UiError.Failed("Invalid recovery invite")) }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(recoveryLoading = true, recoveryError = null) }
            try {
                manager.recover(invite, profile, password)
                _state.update { it.copy(recoveryDialog = false, recoveryLoading = false) }
                openActiveSession()
            } catch (e: Exception) {
                _state.update { it.copy(recoveryLoading = false, recoveryError = UiError.Failed(e.message ?: "Failed")) }
            }
        }
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
                        isOpenNode = (node["is_open_node"] as? JsonPrimitive)?.content == "true",
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

    // ------------------------------------------------------------ policy view

    fun openPolicyViewer() {
        val payload = _state.value.activeProfile?.policyJson ?: return
        val text = runCatching {
            val parsed = messageJson.parseToJsonElement(payload)
            formatPolicy(parsed)
        }.getOrDefault(payload)
        _state.update { it.copy(policyViewer = true, policyViewText = text) }
    }

    fun closePolicyViewer() {
        _state.update { it.copy(policyViewer = false) }
    }

    // ------------------------------------------------------------- remove echo

    fun openRemoveEchoDialog() {
        _state.update {
            it.copy(removeEchoDialog = true, removeEchoName = "", removeEchoReason = "", removeEchoStatus = null, removeEchoError = null)
        }
    }

    fun closeRemoveEchoDialog() {
        _state.update { it.copy(removeEchoDialog = false) }
    }

    fun setRemoveEchoName(value: String) =
        _state.update { it.copy(removeEchoName = value, removeEchoError = null) }

    fun setRemoveEchoReason(value: String) =
        _state.update { it.copy(removeEchoReason = value, removeEchoError = null) }

    fun submitRemoveEcho() {
        val opened = networkSession ?: return
        val target = _state.value.removeEchoName.trim()
        if (target.isEmpty()) {
            _state.update { it.copy(removeEchoError = UiError.Failed("Echo name is required")) }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(removeEchoSending = true, removeEchoError = null, removeEchoStatus = null) }
            try {
                val data = buildJsonObject {
                    put("proto", "remove_echo")
                    put("type", "")
                    put("from", opened.profile.address)
                    put("to", "all")
                    put("message", _state.value.removeEchoReason.trim().ifBlank { "Remove echo: $target" })
                    put("date", java.time.Instant.now().toString())
                    put("echo", "Moderators council")
                    put("node1", opened.profile.address)
                    put("subject", "Remove echo: $target")
                    put("variants", kotlinx.serialization.json.buildJsonArray {
                        add(JsonPrimitive("remove"))
                        add(JsonPrimitive("keep"))
                    })
                    put("echo_name", target)
                }.toString()
                opened.postProtocol("Moderators council", "remove_echo", data)
                _state.update { it.copy(removeEchoSending = false, removeEchoStatus = "Proposal submitted") }
                syncOnce(opened)
            } catch (e: Exception) {
                _state.update {
                    it.copy(removeEchoSending = false, removeEchoError = UiError.Failed(e.message ?: "Failed"))
                }
            }
        }
    }

    // ------------------------------------------------------------- policy edit

    fun openPolicyEditDialog() {
        val payload = _state.value.activeProfile?.policyJson ?: return
        val parsed = runCatching { messageJson.parseToJsonElement(payload).jsonObject }.getOrNull()
        val policy = (parsed?.get("policy") as? JsonObject)
        val text = (policy?.get("text") as? JsonPrimitive)?.contentOrNull.orEmpty()
        val params = policy?.get("parameters")?.let {
            runCatching { prettyJson.encodeToString(JsonElement.serializer(), it) }.getOrNull().orEmpty()
        }.orEmpty()
        _state.update {
            it.copy(
                policyEditDialog = true,
                policyEditText = text,
                policyEditParams = params,
                policyEditAllChunks = false,
                policyEditStatus = null,
                policyEditError = null,
            )
        }
    }

    fun closePolicyEditDialog() {
        _state.update { it.copy(policyEditDialog = false) }
    }

    fun setPolicyEditText(value: String) = _state.update { it.copy(policyEditText = value) }
    fun setPolicyEditParams(value: String) = _state.update { it.copy(policyEditParams = value) }
    fun setPolicyEditAllChunks(value: Boolean) = _state.update { it.copy(policyEditAllChunks = value) }

    fun submitPolicyEdit() {
        val opened = networkSession ?: return
        val parameters = runCatching {
            messageJson.parseToJsonElement(_state.value.policyEditParams)
        }.getOrElse {
            _state.update { it.copy(policyEditError = UiError.Failed("Parameters must be valid JSON")) }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(policyEditSending = true, policyEditError = null, policyEditStatus = null) }
            try {
                val data = buildJsonObject {
                    put("proto", "change_policy")
                    put("from", opened.profile.address)
                    put("to", "all")
                    put("subject", "Change policy")
                    put("message", "Policy change proposal")
                    put("date", java.time.Instant.now().toString())
                    put("node1", opened.profile.address)
                    put("all_chunks_only", _state.value.policyEditAllChunks)
                    put(
                        "proposed_policy",
                        buildJsonObject {
                            put("text", _state.value.policyEditText)
                            put("parameters", parameters)
                        },
                    )
                }.toString()
                opened.postProtocol("Admin council", "change_policy", data)
                _state.update { it.copy(policyEditSending = false, policyEditStatus = "Proposal submitted") }
                syncOnce(opened)
            } catch (e: Exception) {
                _state.update {
                    it.copy(policyEditSending = false, policyEditError = UiError.Failed(e.message ?: "Failed"))
                }
            }
        }
    }

    // ------------------------------------------------------------- moderation

    fun openModeration(message: MessageUi) {
        _state.update {
            it.copy(moderationTarget = message, moderationSeverity = "warning", moderationDuration = "24", moderationReason = "", moderationError = null)
        }
    }

    fun closeModeration() = _state.update { it.copy(moderationTarget = null) }

    fun setModerationSeverity(value: String) = _state.update { it.copy(moderationSeverity = value) }
    fun setModerationDuration(value: String) = _state.update { it.copy(moderationDuration = value) }
    fun setModerationReason(value: String) = _state.update { it.copy(moderationReason = value) }

    fun submitModeration() {
        val opened = networkSession ?: return
        val echo = _state.value.selectedEcho ?: return
        val target = _state.value.moderationTarget ?: return
        viewModelScope.launch {
            _state.update { it.copy(moderationSending = true, moderationError = null) }
            try {
                val original = opened.database.messageDao().getByEchoAndId(echo.name, target.messageId)
                val originalJson = buildJsonObject {
                    put("message_id", target.messageId)
                    put("sender_address", target.senderAddress)
                    put("timestamp", target.timestamp)
                    put("type", original?.type ?: "echomail")
                    put("echo_name", echo.name)
                    if (original != null) {
                        put("data", original.dataJson)
                        if (original.jws != null) put("jws", original.jws)
                    }
                }
                val data = buildJsonObject {
                    put("severity", _state.value.moderationSeverity)
                    put("to", target.senderAddress)
                    put("message", _state.value.moderationReason.trim().ifBlank {
                        "Moderation: ${_state.value.moderationSeverity}"
                    })
                    put("echo", echo.name)
                    put("originalMessage", originalJson)
                    put("rules", buildJsonObject {})
                    if (_state.value.moderationSeverity == "punishment") {
                        put(
                            "duration_hours",
                            _state.value.moderationDuration.trim().toIntOrNull() ?: 24,
                        )
                    }
                }.toString()
                opened.postProtocol(echo.name, "echomail", data)
                _state.update { it.copy(moderationSending = false, moderationTarget = null) }
                syncOnce(opened)
            } catch (e: Exception) {
                _state.update {
                    it.copy(moderationSending = false, moderationError = UiError.Failed(e.message ?: "Failed"))
                }
            }
        }
    }

    // ------------------------------------------------------- create local echo

    fun openCreateEchoDialog() {
        _state.update {
            it.copy(
                createEchoDialog = true,
                createEchoType = CreateEchoType.LocalEcho,
                createEchoName = "",
                createEchoDescription = "",
                createEchoError = null,
            )
        }
    }

    fun setCreateEchoType(type: CreateEchoType) =
        _state.update { it.copy(createEchoType = type, createEchoError = null) }

    fun closeCreateEchoDialog() {
        _state.update { it.copy(createEchoDialog = false) }
    }

    fun setCreateEchoName(value: String) =
        _state.update { it.copy(createEchoName = value, createEchoError = null) }

    fun setCreateEchoDescription(value: String) =
        _state.update { it.copy(createEchoDescription = value, createEchoError = null) }

    fun createLocalEcho() {
        val opened = networkSession ?: return
        val state = _state.value
        val name = state.createEchoName.trim()
        if (name.isEmpty()) {
            _state.update { it.copy(createEchoError = UiError.Failed("Name is required")) }
            return
        }
        if (state.createEchoType == CreateEchoType.Blog) {
            if (state.blogs.any { it.name.equals(name, ignoreCase = true) } ||
                state.allEchoes.any { it.name.equals(name, ignoreCase = true) && !it.isBlog }
            ) {
                _state.update { it.copy(createEchoError = UiError.Failed("Name is already in use")) }
                return
            }
            viewModelScope.launch {
                val id = "blog_${System.currentTimeMillis()}_${java.util.UUID.randomUUID().toString().take(6)}"
                val blog = BlogEntity(id, name, state.createEchoDescription.trim(), System.currentTimeMillis(), System.currentTimeMillis())
                opened.database.blogDao().upsert(blog)
                opened.broadcastBlogOp(buildJsonObject {
                    put("op", "blog")
                    put("blog", buildJsonObject {
                        put("id", blog.id); put("name", blog.name); put("description", blog.description); put("createdAt", blog.createdAt)
                    })
                })
                _state.update {
                    it.copy(
                        createEchoDialog = false,
                        screen = AppScreen.Blog,
                        selectedBlog = BlogUi(id, name, blog.description, blog.createdAt, blog.updatedAt),
                    )
                }
            }
            return
        }
        viewModelScope.launch {
            try {
                opened.createLocalEcho(name, state.createEchoDescription.trim())
                runCatching { opened.refreshEchoes() }
                _state.update { it.copy(createEchoDialog = false) }
            } catch (e: Exception) {
                _state.update { it.copy(createEchoError = UiError.Failed(e.message ?: "Failed")) }
            }
        }
    }

    // -------------------------------------------------------------------- blogs

    fun openBlogPostEditor(post: BlogPostUi? = null) {
        _state.update {
            it.copy(
                screen = AppScreen.BlogCompose,
                blogCompose = BlogComposeState(
                    postId = post?.id,
                    title = post?.title.orEmpty(),
                    body = post?.body.orEmpty(),
                    attachments = post?.attachments.orEmpty(),
                ),
            )
        }
    }

    fun backFromBlogCompose() {
        _state.update { it.copy(screen = AppScreen.Blog, blogCompose = BlogComposeState()) }
    }

    fun setBlogPostTitle(value: String) =
        _state.update { it.copy(blogCompose = it.blogCompose.copy(title = value, error = null)) }

    fun setBlogPostBody(value: String) =
        _state.update { it.copy(blogCompose = it.blogCompose.copy(body = value, error = null)) }

    fun addBlogAttachments(files: List<Triple<String, String, ByteArray>>) {
        val existing = _state.value.blogCompose.attachments
        val result = existing.toMutableList()
        var total = existing.sumOf { it.size }
        for ((name, type, bytes) in files) {
            if (bytes.size > MAX_ATTACHMENT_BYTES) {
                _state.update { it.copy(blogCompose = it.blogCompose.copy(error = UiError.Failed("Attachment is too large (max 2 MiB)"))) }
                return
            }
            total += bytes.size
            if (total > MAX_ATTACHMENTS_TOTAL) {
                _state.update { it.copy(blogCompose = it.blogCompose.copy(error = UiError.Failed("Attachments are too large (max 6 MiB)"))) }
                return
            }
            result += AttachmentUi(name, type, bytes.size, "data:$type;base64," + android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP))
        }
        _state.update { it.copy(blogCompose = it.blogCompose.copy(attachments = result, error = null)) }
    }

    fun removeBlogAttachment(index: Int) {
        val list = _state.value.blogCompose.attachments.toMutableList()
        if (index in list.indices) list.removeAt(index)
        _state.update { it.copy(blogCompose = it.blogCompose.copy(attachments = list)) }
    }

    fun saveBlogPost() {
        val opened = networkSession ?: return
        val blog = _state.value.selectedBlog ?: return
        val compose = _state.value.blogCompose
        val title = compose.title.trim()
        val body = compose.body.trim()
        if (title.isEmpty() && body.isEmpty()) {
            _state.update { it.copy(blogCompose = compose.copy(error = UiError.Failed("Post is empty"))) }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(blogCompose = compose.copy(saving = true, error = null)) }
            try {
                val id = compose.postId ?: "blog_${System.currentTimeMillis()}_${java.util.UUID.randomUUID().toString().take(6)}"
                val attachments = buildJsonArray {
                    compose.attachments.forEach { attachment ->
                        add(buildJsonObject {
                            put("name", attachment.name)
                            put("type", attachment.type)
                            put("size", attachment.size)
                            put("data", attachment.dataUrl)
                        })
                    }
                }.toString()
                val post = BlogPostEntity(
                    id = id,
                    blogId = blog.id,
                    title = title,
                    body = body,
                    attachmentsJson = attachments,
                    timestamp = compose.postId?.let { old ->
                        _state.value.blogPosts.firstOrNull { it.id == old }?.timestamp
                    } ?: System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis(),
                )
                opened.database.blogPostDao().upsert(post)
                opened.broadcastBlogOp(buildJsonObject {
                    put("op", "blogPost")
                    put("post", buildJsonObject {
                        put("id", post.id); put("blogId", post.blogId); put("title", post.title); put("body", post.body)
                        put("attachments", kotlinx.serialization.json.Json.parseToJsonElement(post.attachmentsJson))
                        put("timestamp", post.timestamp)
                    })
                })
                _state.update { it.copy(screen = AppScreen.Blog, blogCompose = BlogComposeState()) }
            } catch (e: Exception) {
                _state.update { it.copy(blogCompose = compose.copy(saving = false, error = UiError.Failed(e.message ?: "Failed"))) }
            }
        }
    }

    fun deleteBlogPost(id: String) {
        val opened = networkSession ?: return
        viewModelScope.launch {
            opened.database.blogPostDao().delete(id)
            opened.broadcastBlogOp(buildJsonObject { put("op", "blogPostDelete"); put("id", id) })
        }
    }

    fun deleteSelectedBlog() {
        val opened = networkSession ?: return
        val blog = _state.value.selectedBlog ?: return
        viewModelScope.launch {
            opened.database.blogPostDao().deleteByBlog(blog.id)
            opened.database.blogDao().delete(blog.id)
            opened.broadcastBlogOp(buildJsonObject { put("op", "blogDelete"); put("id", blog.id) })
            _state.update { it.copy(screen = AppScreen.Echoes, selectedBlog = null, blogPosts = emptyList()) }
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

    fun selectNetmailFolder(folder: String) {
        _state.update { it.copy(netmailFolder = folder) }
    }

    fun openNetmailFolderDialog() {
        _state.update {
            it.copy(netmailFolderDialog = true, netmailFolderName = "", netmailFolderError = null)
        }
    }

    fun closeNetmailFolderDialog() {
        _state.update { it.copy(netmailFolderDialog = false, netmailFolderName = "", netmailFolderError = null) }
    }

    fun setNetmailFolderName(value: String) =
        _state.update { it.copy(netmailFolderName = value, netmailFolderError = null) }

    fun createNetmailFolder() {
        val opened = networkSession ?: return
        val name = _state.value.netmailFolderName.trim()
        if (name.isEmpty()) {
            _state.update { it.copy(netmailFolderError = UiError.Failed("Folder name is required")) }
            return
        }
        viewModelScope.launch {
            val slug = slugifyNetmailFolder(name)
            if (slug.isEmpty() || opened.database.netmailFolderDao().getBySlug(slug) != null) {
                _state.update { it.copy(netmailFolderError = UiError.Failed("A folder with this name already exists")) }
                return@launch
            }
            opened.database.netmailFolderDao().upsert(NetmailFolderEntity(slug, name, System.currentTimeMillis()))
            _state.update {
                it.copy(
                    netmailFolderDialog = false,
                    netmailFolderName = "",
                    netmailFolderError = null,
                    netmailFolder = slug,
                )
            }
        }
    }

    fun deleteNetmailFolder(slug: String) {
        val opened = networkSession ?: return
        viewModelScope.launch {
            opened.database.messageDao().deleteByFolder(slug)
            opened.database.netmailFolderDao().delete(slug)
            _state.update {
                it.copy(netmailFolder = if (it.netmailFolder == slug) NETMAIL_INBOX else it.netmailFolder)
            }
        }
    }

    fun openNetmailMove(messageId: String) {
        _state.update { it.copy(netmailMoveMessageId = messageId) }
    }

    fun closeNetmailMove() = _state.update { it.copy(netmailMoveMessageId = null) }

    fun moveNetmailToFolder(folderSlug: String?) {
        val opened = networkSession ?: return
        val messageId = _state.value.netmailMoveMessageId ?: return
        viewModelScope.launch {
            opened.database.messageDao().setFolder(NetworkSession.NETMAIL_ECHO, messageId, folderSlug)
            _state.update { it.copy(netmailMoveMessageId = null) }
        }
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
        loadNodes()
    }

    fun requestVoucher(node: NodeUi) {
        val opened = networkSession ?: return
        val admin = node.address.substringBeforeLast(':') + ":1"
        viewModelScope.launch {
            runCatching { opened.requestVoucher(admin, node.address) }
                .onFailure { err -> _state.update { state -> state.copy(syncError = UiError.Failed(err.message ?: "Failed")) } }
        }
    }

    fun deleteActiveNetwork() {
        val id = _state.value.activeId ?: return
        deleteProfile(id)
    }

    // ------------------------------------------------------------ keys import

    /**
     * Called with the text of a selected backup/export file.
     *
     * Three formats are recognised: a full v2.0 export (keys + everything),
     * an fm_settings export and a standalone keys export. A full export and a
     * keys export are both identities, so both are accepted while adding a
     * network — the password prompt decrypts the seed and the profile is
     * adopted without a network round-trip.
     */
    fun onImportFile(text: String?) {
        if (text.isNullOrBlank()) {
            _state.update {
                it.copy(add = it.add.copy(importError = UiError.Failed("The selected file is empty")))
            }
            return
        }
        val root = runCatching { messageJson.parseToJsonElement(text).jsonObject }.getOrNull()
        val type = root?.get("type")?.jsonPrimitive?.contentOrNull
        val version = root?.get("version")?.jsonPrimitive?.contentOrNull

        // Settings-only export: a merge into the active network, never an identity.
        if (type == "fm_settings") {
            if (networkSession == null || _state.value.activeProfile == null) {
                _state.update {
                    it.copy(add = it.add.copy(importError = UiError.Failed("Open a network to import settings")))
                }
            } else {
                importSettingsBackup(text)
            }
            return
        }

        // Full export of a network, imported while that network is already open:
        // merge its messages/settings into the active profile.
        val full = if (version == "2.0") BackupService.parseFullBackup(text) else null
        if (full != null && canMergeIntoActive(full)) {
            mergeFullBackup(full)
            return
        }

        // An identity (keys export, or a full export on a fresh install/other key).
        if (full != null || root != null) {
            if (!canImportIdentity()) {
                // The vault is locked: the password is the way back in, the
                // import would otherwise be dropped silently.
                _state.update {
                    it.copy(add = it.add.copy(importError = UiError.Failed("Unlock the app before importing keys")))
                }
                return
            }
            importJsonText = text
            importFull = full
            // Never fill the field from the export: fm-app stores no node URL in
            // its export, so a filled-in value would only hide a missing one.
            _state.update {
                it.copy(
                    add = it.add.copy(
                        awaitingImportPassword = true,
                        importError = null,
                        error = null,
                    ),
                    backupStatus = null,
                    backupError = null,
                )
            }
            return
        }

        _state.update {
            it.copy(add = it.add.copy(importError = UiError.Failed("This file is not an FM export")))
        }
    }

    /** An identity can be adopted when the vault is open or does not exist yet. */
    private fun canImportIdentity(): Boolean {
        val current = _state.value
        return session.isUnlocked || !manager.vaultExists() || current.profiles.isEmpty()
    }

    /**
     * Endpoint of the network being imported: the field wins because it is also
     * how the node key gets pinned; a full export provides one on its own, so a
     * keys export is the only format that really needs the field.
     */
    private fun parsedInviteFor(full: lan.fmnode.android.storage.ImportedFullBackup?): Invite? =
        InviteParser.parse(_state.value.add.url)
            ?: full?.nodeUrl?.let { InviteParser.parse(it) }

    /** Whether a full export belongs to the network that is currently open. */
    private fun canMergeIntoActive(full: lan.fmnode.android.storage.ImportedFullBackup): Boolean {
        val profile = _state.value.activeProfile ?: return false
        if (networkSession == null) return false
        return profile.publicKeyB64 == full.publicKeyB64
    }

    fun clearBackupMessage() = _state.update { it.copy(backupStatus = null, backupError = null) }

    fun exportSettingsJson(): String? {
        val profile = _state.value.activeProfile ?: return null
        val opened = networkSession ?: return null
        return kotlinx.coroutines.runBlocking { BackupService.exportSettings(opened.database, profile) }
    }

    fun exportFullJson(): String? {
        val profile = _state.value.activeProfile ?: return null
        val opened = networkSession ?: return null
        return kotlinx.coroutines.runBlocking { BackupService.exportFull(opened.database, profile) }
    }

    private fun importSettingsBackup(text: String) {
        val profile = _state.value.activeProfile ?: return
        val opened = networkSession ?: return
        viewModelScope.launch {
            try {
                val result = BackupService.importSettings(opened.database, profile, text)
                _state.update {
                    it.copy(
                        backupStatus = result.warning ?: "Settings imported",
                        backupError = null,
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(backupError = UiError.Failed(e.message ?: "Import failed"), backupStatus = null) }
            }
        }
    }

    private fun mergeFullBackup(full: lan.fmnode.android.storage.ImportedFullBackup) {
        val profile = _state.value.activeProfile ?: return
        val opened = networkSession ?: return
        viewModelScope.launch {
            try {
                val result = BackupService.importFull(opened.database, profile, full.payload)
                _state.update {
                    it.copy(
                        backupStatus = result.warning ?: importSummary(result),
                        backupError = null,
                    )
                }
                syncOnce(opened)
            } catch (e: Exception) {
                _state.update { it.copy(backupError = UiError.Failed(e.message ?: "Import failed"), backupStatus = null) }
            }
        }
    }

    private fun importSummary(result: BackupService.Result): String =
        "Imported: ${result.messages} messages, ${result.blogs} blogs, ${result.posts} posts, ${result.folders} folders"

    fun cancelImport() {
        importJsonText = null
        importFull = null
        _state.update { it.copy(add = it.add.copy(awaitingImportPassword = false, importError = null)) }
    }

    /**
     * Decrypts the imported keys with the export password and adopts the
     * identity of the file as a network profile.
     *
     * The profile is built from the export itself (address, display name, node
     * key, encrypted seed), so no invite URL and no node request is involved:
     * the node already knows this key pair. When a full export is imported, its
     * messages are merged right after the session opens.
     */
    fun confirmImportPassword(password: String) {
        val text = importJsonText
        if (text == null) {
            _state.update {
                it.copy(add = it.add.copy(awaitingImportPassword = false, importError = UiError.Failed("No file selected")))
            }
            return
        }
        if (password.isEmpty()) {
            _state.update { it.copy(add = it.add.copy(importError = UiError.Failed("Enter the export password"))) }
            return
        }

        val full = importFull
        // The endpoint comes from the field first, then from the export itself
        // (fm-app writes `appUrl` into a full export). Only a keys export has
        // neither and needs the field filled in.
        val invite = parsedInviteFor(full)
        if (invite == null) {
            _state.update {
                it.copy(
                    add = it.add.copy(
                        importError = UiError.Failed(
                            if (_state.value.add.url.isBlank()) {
                                "Enter the network URL first (for example https://node.example)"
                            } else {
                                "Invalid network URL: ${_state.value.add.url}"
                            },
                        ),
                    ),
                )
            }
            return
        }
        viewModelScope.launch {
            val keystore = try {
                withContext(Dispatchers.Default) { KeystoreExportCodec.decode(text) }
            } catch (e: Exception) {
                _state.update {
                    it.copy(add = it.add.copy(awaitingImportPassword = false, importError = UiError.Failed(e.message ?: "Invalid keys file")))
                }
                return@launch
            }

            val seed = try {
                // PBKDF2-HMAC-SHA256 with 100k iterations: never on the main thread.
                withContext(Dispatchers.Default) {
                    keystore.encryptedSeed?.let { KeyVault.decrypt(it, password) } ?: keystore.plainSeed
                }
            } catch (e: Exception) {
                // AES-GCM: a wrong password fails the tag check, so any failure
                // here is a wrong password.
                _state.update { it.copy(add = it.add.copy(importError = UiError.WrongPassword)) }
                return@launch
            }

            if (seed == null) {
                _state.update {
                    it.copy(add = it.add.copy(awaitingImportPassword = false, importError = UiError.Failed("No private key in the file")))
                }
                return@launch
            }
            if (seed.size != 32) {
                _state.update {
                    it.copy(add = it.add.copy(awaitingImportPassword = false, importError = UiError.Failed("Invalid private key length")))
                }
                return@launch
            }
            if (!CryptoService.publicKeyFromSeed(seed).contentEquals(keystore.publicKey)) {
                _state.update {
                    it.copy(add = it.add.copy(awaitingImportPassword = false, importError = UiError.Failed("The private key does not match the public key")))
                }
                return@launch
            }

            // The password of the whole installation: reuse the one that
            // already protects the vault, create it that way for a first network.
            val appPassword = session.passwordOrNull()
                ?: _state.value.add.password.takeIf { it.isNotEmpty() }
                ?: password

            _state.update {
                it.copy(
                    add = it.add.copy(awaitingImportPassword = false, isLoading = true, importError = null, imported = true),
                    backupStatus = null,
                    backupError = null,
                )
            }

            try {
                // The invite URL is the endpoint of the network: the node key from
                // it pins the node, the same way the add-network flow does.
                val profile = manager.adoptProfile(
                    password = appPassword,
                    seed = seed,
                    address = full?.address ?: invite?.address ?: "",
                    displayName = full?.displayName ?: _state.value.add.displayName,
                    nodePublicKeyB64 = invite?.nodePublicKey?.let { Encoding.base64Encode(it) }
                        ?: full?.nodePublicKeyB64,
                    appUrl = invite?.appUrl ?: _state.value.add.url,
                    apiUrl = invite?.apiUrl ?: _state.value.add.url,
                    title = null,
                )
                session.setPassword(appPassword)
                importedSeed = seed
                importJsonText = null
                importFull = null
                _state.update { it.copy(add = AddNetworkState(), unlocked = true) }
                openActiveSession()

                // The profile is stored at this point: never drop the imported
                // payload because the node was unreachable. openActiveSession()
                // reports its own error and stays on the networks screen.
                val opened = networkSession
                if (full != null && opened != null && opened.profile.id == profile.id) {
                    mergeImportedFull(full)
                } else if (full != null) {
                    _state.update {
                        it.copy(
                            backupError = UiError.Failed(
                                "Network keys were saved, but the messages could not be imported. " +
                                    "Reconnect and import the same file again.",
                            ),
                        )
                    }
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(add = it.add.copy(isLoading = false, importError = UiError.Failed(e.message ?: "Import failed")))
                }
            }
        }
    }

    /** Imports the payload of a full export into the profile just adopted. */
    private suspend fun mergeImportedFull(full: lan.fmnode.android.storage.ImportedFullBackup) {
        val opened = networkSession ?: return
        try {
            val result = BackupService.importFull(opened.database, opened.profile, full.payload)
            _state.update {
                it.copy(
                    backupStatus = result.warning ?: importSummary(result),
                    backupError = null,
                )
            }
            syncOnce(opened)
        } catch (e: Exception) {
            _state.update {
                it.copy(backupError = UiError.Failed(e.message ?: "Messages were not imported"), backupStatus = null)
            }
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
            // Attachment-only uploads without a body/echo context are not
            // conversation messages: they are rendered as file bundles (#9).
            if (row.isAttachmentOnly()) continue
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

    /**
     * Uploads that carry files and nothing else (the shape fm-node uses for
     * "just files, no letter") are shown as their own entry instead of an empty card.
     */
    private fun MessageRow.isAttachmentOnly(): Boolean {
        val data = runCatching {
            messageJson.parseToJsonElement(dataJson).jsonObject
        }.getOrNull() ?: return false
        return MessageMapping.isAttachmentOnly(data)
    }

    private fun MessageRow.toUi(me: String, myName: String): MessageUi {
        val data = runCatching {
            messageJson.parseToJsonElement(dataJson).jsonObject
        }.getOrNull()
        val subject = (data?.get("subject") as? JsonPrimitive)?.contentOrNull.orEmpty()
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
        // A file bundle without a letter still gets a readable title (#9).
        val body = MessageMapping.renderBody(data)
        val versions = versionsJson?.let { raw ->
            runCatching {
                (messageJson.parseToJsonElement(raw) as kotlinx.serialization.json.JsonArray).mapNotNull { element ->
                    val version = element as? JsonObject ?: return@mapNotNull null
                    val versionData = (version["data"] as? JsonPrimitive)?.contentOrNull?.let { data ->
                        runCatching { messageJson.parseToJsonElement(data).jsonObject }.getOrNull()
                    }
                    MessageVersionUi(
                        subject = (versionData?.get("subject") as? JsonPrimitive)?.contentOrNull.orEmpty(),
                        body = (versionData?.get("body") as? JsonPrimitive)?.contentOrNull.orEmpty(),
                        timestamp = (version["timestamp"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: 0,
                    )
                }
            }.getOrDefault(emptyList())
        } ?: emptyList()
        return MessageUi(
            messageId = messageId,
            senderName = name,
            senderAddress = senderAddress,
            recipientAddress = recipientAddress,
            timestamp = timestamp,
            subject = subject,
            body = body,
            own = own,
            folderSlug = folderSlug,
            attachments = attachments,
            versions = versions,
        )
    }

    private fun BlogPostEntity.toUi(): BlogPostUi {
        val attachments = runCatching {
            (messageJson.parseToJsonElement(attachmentsJson) as kotlinx.serialization.json.JsonArray).mapNotNull { element ->
                val item = element as? JsonObject ?: return@mapNotNull null
                AttachmentUi(
                    name = (item["name"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                    type = (item["type"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                    size = (item["size"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0,
                    dataUrl = (item["data"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                )
            }
        }.getOrDefault(emptyList())
        return BlogPostUi(id, blogId, title, body, attachments, timestamp)
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
        private const val MAX_SEND_ERRORS = 10

        fun factory(manager: NetworkManager, session: Session): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    AppViewModel(manager, session) as T
            }
    }
}
