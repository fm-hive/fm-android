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
import lan.fmnode.android.storage.MessageRow
import lan.fmnode.android.storage.ProfileEntity

enum class AppScreen { Unlock, Networks, AddNetwork, Echoes, Echo, Compose }

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
    val timestamp: Long,
    val subject: String,
    val body: String,
    val own: Boolean,
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
                )
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

    // ------------------------------------------------------------------ helpers

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
