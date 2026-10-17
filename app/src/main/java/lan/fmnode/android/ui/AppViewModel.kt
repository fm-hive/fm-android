package lan.fmnode.android.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import lan.fmnode.android.network.Invite
import lan.fmnode.android.network.InviteParser
import lan.fmnode.android.network.NetworkManager
import lan.fmnode.android.network.PolicyInfo
import lan.fmnode.android.session.Session
import lan.fmnode.android.storage.ProfileEntity

enum class AppScreen { Unlock, Networks, AddNetwork }

enum class AddStep { Url, Policy }

/** Errors surfaced by the UI; known cases map to localized strings. */
sealed interface UiError {
    data object WrongPassword : UiError
    data object PasswordsDiffer : UiError
    data object PasswordShort : UiError
    data class Failed(val text: String) : UiError
}

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
    val unlocked: Boolean = false,
    val add: AddNetworkState = AddNetworkState(),
    val unlockError: UiError? = null,
    val loading: Boolean = false,
)

/**
 * Multi-network application state: unlock the vault, add networks through the
 * two-phase policy flow, switch and delete profiles.
 */
class AppViewModel(
    private val manager: NetworkManager,
    private val session: Session,
) : ViewModel() {
    private val prettyJson = Json { prettyPrint = true }
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    init {
        viewModelScope.launch {
            manager.profiles.collect { profiles ->
                _state.update { s ->
                    val unlocked = session.isUnlocked
                    val screen = when {
                        s.screen == AppScreen.AddNetwork -> s.screen
                        profiles.isEmpty() -> AppScreen.Networks
                        !unlocked -> AppScreen.Unlock
                        else -> AppScreen.Networks
                    }
                    s.copy(profiles = profiles, screen = screen, unlocked = unlocked)
                }
            }
        }
        viewModelScope.launch {
            manager.activeProfileId()?.let { id -> _state.update { it.copy(activeId = id) } }
        }
    }

    fun unlock(password: String) {
        viewModelScope.launch {
            val profile = _state.value.profiles.firstOrNull() ?: return@launch
            if (session.unlock(password, profile)) {
                _state.update { it.copy(screen = AppScreen.Networks, unlocked = true, unlockError = null) }
            } else {
                _state.update { it.copy(unlockError = UiError.WrongPassword) }
            }
        }
    }

    fun lock() {
        session.lock()
        _state.update {
            it.copy(unlocked = false, screen = AppScreen.Unlock, unlockError = null)
        }
    }

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
        _state.update { it.copy(screen = AppScreen.Networks, add = AddNetworkState()) }
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
                val profile = manager.register(
                    invite = invite,
                    policy = policy,
                    password = password,
                    displayName = add.displayName,
                )
                session.setPassword(password)
                _state.update {
                    it.copy(
                        screen = AppScreen.Networks,
                        add = AddNetworkState(),
                        activeId = profile.id,
                        unlocked = true,
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(add = it.add.copy(isLoading = false, error = UiError.Failed(e.message ?: "Failed")))
                }
            }
        }
    }

    fun selectProfile(id: String) {
        viewModelScope.launch {
            manager.setActive(id)
            _state.update { it.copy(activeId = id) }
        }
    }

    fun deleteProfile(id: String) {
        viewModelScope.launch {
            manager.deleteProfile(id)
            _state.update { it.copy(activeId = manager.activeProfileId()) }
        }
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
