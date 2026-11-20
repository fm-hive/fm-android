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
import kotlinx.coroutines.withTimeoutOrNull
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
import lan.fmnode.android.BuildConfig
import lan.fmnode.android.network.FmApiException
import lan.fmnode.android.network.Invite
import lan.fmnode.android.network.InviteParser
import lan.fmnode.android.network.NetworkManager
import lan.fmnode.android.network.NetworkSession
import lan.fmnode.android.network.PolicyInfo
import lan.fmnode.android.network.RateLimitUsage
import lan.fmnode.android.network.RulesId
import lan.fmnode.android.session.Session
import lan.fmnode.android.storage.BackupService
import lan.fmnode.android.storage.BlogEntity
import lan.fmnode.android.storage.BlogPostEntity
import lan.fmnode.android.storage.KeyVault
import lan.fmnode.android.storage.KeystoreExportCodec
import lan.fmnode.android.storage.MessageRow
import lan.fmnode.android.storage.NetmailFolderEntity
import lan.fmnode.android.storage.NoopPasswordStore
import lan.fmnode.android.storage.PasswordStore
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

/** Where picked files should be added. */
enum class AttachmentTarget { Echo, Netmail, Blog }

const val NETMAIL_INBOX = "inbox"
const val NETMAIL_SENT = "sent"
const val NETMAIL_ECHO = "__netmail__"

data class NetmailFolderUi(
    val slug: String,
    val label: String,
    val custom: Boolean = false,
    val count: Int = 0,
)

enum class CreateEchoType { LocalEcho, WithRules, Channel, Blog }

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
    /** Raw rules JSON; a non-empty value means subscribing needs acceptance. */
    val rulesText: String = "",
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

/** One parameter row of the policy editor. */
data class PolicyParamFieldUi(
    val key: String,
    val kind: PolicyParamKind,
    val value: String,
    val original: String,
    val options: List<String> = emptyList(),
    val maxLength: Int? = null,
) {
    val changed: Boolean get() = value.trim() != original.trim()
    val invalid: Boolean get() = kind == PolicyParamKind.Duration && !PolicyDuration.isValid(value)
}

/**
 * Policy change proposal (#21): the policy text plus one field per parameter,
 * the nested rate limits and a diff preview. Unknown parameters of the node
 * are preserved as they are.
 */
data class PolicyEditUi(
    val text: String,
    val originalText: String,
    val fields: List<PolicyParamFieldUi>,
    val rateLimits: Map<String, String>,
    val originalRateLimits: Map<String, String>,
    val preserved: JsonObject,
    val allChunksOnly: Boolean = false,
    val showDiff: Boolean = false,
    val sending: Boolean = false,
    val status: String? = null,
    val error: UiError? = null,
) {
    val textChanged: Boolean get() = text != originalText

    /** The editor refuses to submit while a duration value is malformed (#29). */
    val hasInvalidFields: Boolean get() = fields.any { it.invalid }

    /** Changes to show in the diff preview, e.g. `voting_period: 1m → 2d`. */
    val changes: List<String>
        get() = buildList {
            if (textChanged) add("text")
            fields.filter { it.changed }.forEach { field ->
                add("${field.key}: ${field.original.ifBlank { "—" }} → ${field.value.ifBlank { "—" }}")
            }
            rateLimits.forEach { (key, value) ->
                val original = originalRateLimits[key].orEmpty()
                if (value.trim() != original.trim()) {
                    add("rate_limit.$key: ${original.ifBlank { "—" }} → ${value.ifBlank { "—" }}")
                }
            }
        }
}

/** A vote (poster + collected votes + result) shown in the conference feed. */
data class VoteCardUi(
    /** Poster text: the key votes and results refer to (`poster_message`). */
    val posterMessage: String,
    val echoName: String,
    val question: String,
    val variants: List<String>,
    /** Variant to votes cast for it. */
    val counts: Map<String, Int>,
    /** This user's variant; null when not voted. */
    val myVote: String?,
    val totalVotes: Int,
    val finished: Boolean,
    val winner: String? = null,
    val success: Boolean = true,
    val resultText: String = "",
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
    /** Conference of the message; vote actions need it. */
    val echoName: String = "",
    /** Optional user line under the body (#309). */
    val tagline: String = "",
    /** Client line under the tagline, shown when the setting is enabled (#311). */
    val tearline: String = "",
    /** Set for vote posters: the card replaces the plain message body. */
    val vote: VoteCardUi? = null,
    /** Set for warnings/punishments that a node admin can appeal. */
    val moderation: ModerationUi? = null,
    /** Set when this is a protocol/service message listed in the feed (#46). */
    val protocolType: String? = null,
    /** Refill cutoff date for a refill request card. */
    val refillBefore: String? = null,
)

data class ContactUi(
    val address: String,
    val name: String,
)

/** One network of the vault with its echoes, shown as a group (#15). */
data class NetworkGroupUi(
    val profileId: String,
    /** Network name from the accepted policy; falls back to the endpoint. */
    val networkName: String,
    /** Profile title (host and address), used while no policy name is known. */
    val title: String,
    val address: String,
    /** Host and port of the node, keeps same-host networks apart. */
    val endpoint: String,
    val isActive: Boolean,
    val echoes: List<EchoUi>,
) {
    val unread: Int get() = echoes.sumOf { it.unread }

    /**
     * What the UI shows as the network title: the name from the accepted policy,
     * not the HTTP endpoint (#20). An unnamed network keeps its profile title so
     * the header never degrades to a bare URL.
     */
    val displayName: String get() = networkName.ifBlank { title }
}

/** A voucher row shown in the settings list. */
data class VoucherUi(
    val code: String,
    val type: String,
    val status: String,
    val createdBy: String,
    val expiresAt: String,
)

/** Echo rules the user must accept before subscribing. */
data class RulesPromptUi(
    val echoName: String,
    val rulesText: String,
)

/** Proposal editor for conference rules and its rename (§ change_rules). */
data class RulesEditState(
    val echoName: String,
    val rulesJson: String,
    val newName: String = "",
    val allChunksOnly: Boolean = false,
    val sending: Boolean = false,
    val status: String? = null,
    val error: UiError? = null,
)

/** Draft of a new vote poster (vote protocol). */
data class VoteComposeState(
    val question: String = "",
    val variants: List<String> = listOf("", ""),
    val sending: Boolean = false,
    val error: UiError? = null,
)

/** User-defined group of conferences inside one network (#366). */
data class EchoGroupUi(
    val id: String,
    val name: String,
    val echoes: List<String>,
)

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
    val attachments: List<AttachmentUi> = emptyList(),
    /** ``reply_to_message_id`` of the quoted message, if this is a reply. */
    val replyToMessageId: String? = null,
    /** Author shown in the reply banner. */
    val replyToLabel: String = "",
    /** Quoted text prepended to the body, removed when the reply is cancelled. */
    val replyQuote: String = "",
    /** Per-message tagline; the default is copied from settings on open. */
    val tagline: String = "",
    /** Policy/echo/rate-limit problems; blocking issues disable Send. */
    val limitIssues: List<MessageLimitIssue> = emptyList(),
    val sending: Boolean = false,
    val error: UiError? = null,
    val keyRequested: Boolean = false,
)

/** Moderation action attached to a warning/punishment echomail (appeal protocol). */
data class ModerationUi(
    val severity: String,
    val targetAddress: String,
    val originalMessageId: String,
    /** Who issued the action: the sender of the moderation notice, not the punished message. */
    val moderatorAddress: String = "",
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

/** A user registered on a node, as listed by the node metadata. */
data class NodeClientUi(
    val address: String,
    val name: String,
)

data class NodeUi(
    val address: String,
    val url: String,
    val isRecoverNode: Boolean,
    val isOpenNode: Boolean = false,
    val isEchomailRouter: Boolean = false,
    /** Display name of the node admin, reported by the node (#361). */
    val name: String = "",
    /** Users of that node, reported by the node (#363). */
    val clients: List<NodeClientUi> = emptyList(),
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
    /** Refill cutoff date (ISO or YYYY-MM-DD) for a refill request card. */
    val refillBefore: String? = null,
    val electionCandidate: String? = null,
    /** Variant to votes, collected from the echo votes of a vote poster. */
    val variantCounts: Map<String, Int> = emptyMap(),
    /** This user's variant (null = not voted, empty = retracted). */
    val myVote: String? = null,
    val totalVotes: Int = 0,
)

data class ComposeState(
    val subject: String = "",
    val body: String = "",
    val attachments: List<AttachmentUi> = emptyList(),
    /** Send as `announcement` instead of `echomail` (announcements-enabled echoes). */
    val announcement: Boolean = false,
    /** ``reply_to_message_id`` of the quoted message, if this is a reply. */
    val replyToMessageId: String? = null,
    /** Author shown in the reply banner. */
    val replyToLabel: String = "",
    /** Quoted text prepended to the body, removed when the reply is cancelled. */
    val replyQuote: String = "",
    /** Per-message tagline; the default is copied from settings on open. */
    val tagline: String = "",
    /** Policy/echo/rate-limit problems; blocking issues disable Send. */
    val limitIssues: List<MessageLimitIssue> = emptyList(),
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
    /** "Save password" checkbox while creating the first network (#22). */
    val rememberPassword: Boolean = false,
    val isLoading: Boolean = false,
    val error: UiError? = null,
    val awaitingImportPassword: Boolean = false,
    val imported: Boolean = false,
    val importError: UiError? = null,
)

/**
 * Network display name from an accepted policy.
 *
 * An empty string means the policy carries no `net_name`; callers decide what
 * to show instead. A name that happens to equal the product name (`"FM"`) is a
 * real name and is returned as is — it must not be confused with "no name".
 */
internal fun networkNameFromPolicyJson(policyJson: String): String {
    val json = Json { ignoreUnknownKeys = true }
    return runCatching {
        val root = json.parseToJsonElement(policyJson).jsonObject
        val parameters = root["policy"]?.jsonObject?.get("parameters")?.jsonObject
        parameters?.get("net_name")?.jsonPrimitive?.contentOrNull?.trim()
            ?.takeIf { it.isNotEmpty() } ?: ""
    }.getOrDefault("")
}

/**
 * Payload of a `remove_echo` proposal.
 *
 * The **node** owns the vote: it creates the `remove`/`keep` poster itself as
 * soon as it sees a proposal without `variants` (fm-go checks
 * `len(Variants) == 0`). Sending `variants` from the client made the node treat
 * the message as a vote chunk and skip the proposal entirely, so this payload
 * deliberately carries only the target and the reason.
 */
internal fun removeEchoPayload(
    from: String,
    echoName: String,
    reason: String,
    council: String,
    date: String,
): String = buildJsonObject {
    put("proto", "remove_echo")
    put("from", from)
    put("to", "all")
    put("message", reason.ifBlank { "Remove echo: $echoName" })
    put("date", date)
    put("echo", council)
    put("node1", from)
    put("subject", "Remove echo: $echoName")
    put("echo_name", echoName)
}.toString()

/** Network news conference name from the accepted policy (interactive posts are node-only). */
internal fun networkNewsEchoName(policyJson: String): String {
    val json = Json { ignoreUnknownKeys = true }
    return runCatching {
        val root = json.parseToJsonElement(policyJson).jsonObject
        val groups = root["policy"]?.jsonObject?.get("parameters")?.jsonObject
            ?.get("special_echogroups")?.jsonObject
        groups?.get("network_news")?.jsonPrimitive?.contentOrNull?.trim()
            ?.takeIf { it.isNotEmpty() } ?: "Network news"
    }.getOrDefault("Network news")
}

/** Admin council echo name from the accepted policy. */
internal fun adminCouncilName(policyJson: String): String {
    val json = Json { ignoreUnknownKeys = true }
    return runCatching {
        val root = json.parseToJsonElement(policyJson).jsonObject
        val groups = root["policy"]?.jsonObject?.get("parameters")?.jsonObject
            ?.get("special_echogroups")?.jsonObject
        groups?.get("admin")?.jsonPrimitive?.contentOrNull?.trim()
            ?.takeIf { it.isNotEmpty() } ?: "Admin council"
    }.getOrDefault("Admin council")
}

/** Moderators council echo name from the accepted policy. */
internal fun moderatorsCouncilName(policyJson: String): String {
    val json = Json { ignoreUnknownKeys = true }
    return runCatching {
        val root = json.parseToJsonElement(policyJson).jsonObject
        val groups = root["policy"]?.jsonObject?.get("parameters")?.jsonObject
            ?.get("special_echogroups")?.jsonObject
        groups?.get("moderators")?.jsonPrimitive?.contentOrNull?.trim()
            ?.takeIf { it.isNotEmpty() } ?: "Moderators council"
    }.getOrDefault("Moderators council")
}

/** Boolean network policy parameter of the accepted policy (default when absent). */
internal fun policyFlag(policyJson: String, key: String, default: Boolean): Boolean {    val json = Json { ignoreUnknownKeys = true }
    return runCatching {
        val root = json.parseToJsonElement(policyJson).jsonObject
        val parameters = root["policy"]?.jsonObject?.get("parameters")?.jsonObject
        (parameters?.get(key) as? JsonPrimitive)?.contentOrNull?.let { it == "true" } ?: default
    }.getOrDefault(default)
}

/** Human-readable rules for the acceptance dialog. */
internal fun formatEchoRules(rulesJson: String): String {
    val rules = RulesId.rulesMap(rulesJson) ?: return rulesJson
    val text = (rules["text"] as? JsonPrimitive)?.contentOrNull.orEmpty()
    val parameters = rules["parameters"] as? JsonObject
    val pretty = if (parameters != null && parameters.isNotEmpty()) {
        Json { prettyPrint = true }.encodeToString(JsonElement.serializer(), parameters)
    } else {
        ""
    }
    return listOf(text, pretty).filter { it.isNotBlank() }.joinToString("\n\n")
}

/** Rules in the canonical `{text, parameters}` form, ready for editing. */
internal fun prettyEchoRules(rulesJson: String): String {
    val rules = RulesId.rulesMap(rulesJson)
        ?: return "{\n  \"text\": \"\",\n  \"parameters\": {}\n}"
    return Json { prettyPrint = true }.encodeToString(JsonElement.serializer(), rules)
}

data class UiState(
    val screen: AppScreen = AppScreen.Networks,
    val profiles: List<ProfileEntity> = emptyList(),
    val activeId: String? = null,
    val activeProfile: ProfileEntity? = null,
    val networkName: String = "",
    /** Default tagline and tearline switch, stored in the profile meta. */
    val tagline: String = "",
    val showTearline: Boolean = false,
    /** List protocol/service messages in the echo feed (#46). */
    val showServiceMessages: Boolean = false,
    /** Daily rate-limit usage reported by the node (address-wide). */
    val rateLimits: Map<String, RateLimitUsage> = emptyMap(),
    /** fm-app parity: allow sending over the daily rate limit. */
    val allowSendingWithoutRateLimit: Boolean = false,
    /** Pending image resize dialog (see prepareAttachments). */
    val imageResizeDialog: Boolean = false,
    val imageMaxDimension: Int = 1280,
    val rememberImageDimension: Boolean = false,
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
    /** Outcome of answering somebody else's refill request. */
    val refillRespondStatus: String? = null,
    /** Remote subscription to a channel hosted on another node. */
    val remoteSubscribeDialog: Boolean = false,
    val remoteSubscribeEcho: String = "",
    val remoteSubscribeHost: String = "",
    val remoteSubscribeSending: Boolean = false,
    val remoteSubscribeStatus: String? = null,
    val remoteSubscribeError: UiError? = null,
    /** Appeal of a moderator warning/punishment (admin only). */
    val appealTarget: MessageUi? = null,
    val appealReason: String = "",
    val appealSending: Boolean = false,
    val appealStatus: String? = null,
    val appealError: UiError? = null,
    val showPolicyResign: Boolean = false,
    val policyResignText: String = "",
    val scanPurpose: ScanPurpose = ScanPurpose.AddNetwork,
    val voucherCode: String? = null,
    /** `invite` or `invite_time` (admin-only reusable voucher). */
    val voucherType: String = NetworkSession.VOUCHER_INVITE,
    val voucherTtl: String = "24h",
    val voucherReusable: Boolean = false,
    val voucherExpiresAt: String? = null,
    val vouchers: List<VoucherUi> = emptyList(),
    val vouchersLoading: Boolean = false,
    val vouchersError: UiError? = null,
    /** Network policy switch; the create button hides when vouchers are off. */
    val voucherEnabled: Boolean = true,
    /** Ask the node for a recipient key when netmail cannot be encrypted yet. */
    val autoRequestPubkeys: Boolean = false,
    /** Answer other users' `pubkey_request` messages automatically. */
    val autoSendPubkey: Boolean = false,
    val voucherLoading: Boolean = false,
    val voucherError: UiError? = null,
    /** Rules of an echo the user is subscribing to; the dialog asks to accept. */
    val rulesPrompt: RulesPromptUi? = null,
    /** Rules/rename proposal editor of the current echo (moderator/compat). */
    val rulesEdit: RulesEditState? = null,
    /** New vote poster of the current conference (null = dialog closed). */
    val voteCompose: VoteComposeState? = null,
    /** User-defined echo groups of the active network (#366 of fm-app). */
    val echoGroups: List<EchoGroupUi> = emptyList(),
    val echoGroupsDialog: Boolean = false,
    val echoGroupDraftName: String = "",
    val echoGroupSelection: Set<String> = emptySet(),
    val echoGroupError: UiError? = null,
    /** Renaming the display name on the node. */
    val displayNameDialog: Boolean = false,
    val displayNameDraft: String = "",
    val displayNameSaving: Boolean = false,
    val displayNameError: UiError? = null,
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
    /** Rules JSON used when [createEchoType] is WithRules. */
    val createEchoRules: String = "",
    /** Keep a rules-based echo local instead of announcing it network-wide. */
    val createEchoNetworkChannel: Boolean = false,
    /** Comma-separated writers of a channel; empty means everybody. */
    val createEchoWriters: String = "",
    /** Comma-separated echoes a writer election may draw candidates from. */
    val createEchoElectWritersFrom: String = "",
    val createEchoRetractVote: Boolean = false,
    val createEchoRetractVoteDays: String = "1",
    val createEchoError: UiError? = null,
    val policyViewer: Boolean = false,
    val policyViewText: String = "",
    val removeEchoDialog: Boolean = false,
    val removeEchoName: String = "",
    val removeEchoReason: String = "",
    val removeEchoSending: Boolean = false,
    val removeEchoStatus: String? = null,
    val removeEchoError: UiError? = null,
    /** Policy change proposal of an admin; null when the dialog is closed. */
    val policyEdit: PolicyEditUi? = null,
    val moderationTarget: MessageUi? = null,
    val moderationSeverity: String = "warning",
    val moderationDuration: String = "24",
    val moderationReason: String = "",
    val moderationSending: Boolean = false,
    val moderationError: UiError? = null,
    val add: AddNetworkState = AddNetworkState(),
    val unlockError: UiError? = null,
    /** "Save password" checkbox of the unlock screen (#22). */
    val rememberPassword: Boolean = false,
    /** A saved password is being tried before the unlock form is shown (#22). */
    val autoUnlocking: Boolean = false,
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
    private val passwordStore: PasswordStore = NoopPasswordStore,
) : ViewModel() {
    private val prettyJson = Json { prettyPrint = true }
    private val messageJson = Json { ignoreUnknownKeys = true }
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    private var importedSeed: ByteArray? = null
    private var importJsonText: String? = null
    private var pendingAttachmentFiles: List<Triple<String, String, ByteArray>>? = null
    private var pendingAttachmentTarget: AttachmentTarget = AttachmentTarget.Echo

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
        val hasVault = manager.vaultExists()
        _state.update {
            it.copy(
                screen = if (hasVault) AppScreen.Unlock else AppScreen.Networks,
                autoUnlocking = hasVault,
            )
        }
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
            _state.update {
                it.copy(
                    activeId = id,
                    activeProfile = profile,
                    networkName = profile?.let { p -> networkNameFromPolicyJson(p.policyJson) }.orEmpty(),
                )
            }
        }
        // "Save password" support: try the Keystore-protected password before
        // showing the unlock form (#22).
        viewModelScope.launch {
            if (session.isUnlocked || !manager.vaultExists()) {
                _state.update { it.copy(autoUnlocking = false, rememberPassword = false) }
                return@launch
            }
            // Decrypting the saved password with the Android Keystore can take
            // a moment; the unlock screen shows a disabled field with dots and
            // a spinner while this runs (#45).
            val saved = withContext(Dispatchers.IO) { passwordStore.load() }
            if (saved == null) {
                _state.update { it.copy(autoUnlocking = false) }
                return@launch
            }
            // Show the checkbox as checked while the saved password is tried.
            _state.update { it.copy(rememberPassword = true) }
            if (!manager.openVault(saved)) {
                // The stored password no longer opens the vault: forget it and
                // give the form back in a clean state (#45).
                passwordStore.clear()
                _state.update {
                    it.copy(
                        autoUnlocking = false,
                        rememberPassword = false,
                        unlockError = UiError.WrongPassword,
                    )
                }
                return@launch
            }
            // Keep the locked form with the spinner until the session is fully
            // open: opening the profile database can take several seconds.
            _state.update { it.copy(rememberPassword = true) }
            completeUnlock(saved)
        }
    }

    // ------------------------------------------------------------ vault/session

    fun setRememberPassword(value: Boolean) =
        _state.update { it.copy(rememberPassword = value) }

    // --------------------------------------------------------------- privacy

    /**
     * Turns the node-side key request on for netmail. Without it a message to an
     * unknown recipient stays in the outbox: only a `pubkey_response` can make
     * it sendable, and nothing else asks for the key.
     */
    fun setAutoRequestPubkeys(value: Boolean) {
        val opened = networkSession
        _state.update { it.copy(autoRequestPubkeys = value) }
        opened ?: return
        viewModelScope.launch {
            runCatching { opened.setAutoRequestPubkeys(value) }
                .onFailure { _state.update { state -> state.copy(autoRequestPubkeys = !value) } }
        }
    }

    fun setAutoSendPubkey(value: Boolean) {
        val opened = networkSession
        _state.update { it.copy(autoSendPubkey = value) }
        opened ?: return
        viewModelScope.launch {
            runCatching { opened.setAutoSendPubkey(value) }
                .onFailure { _state.update { state -> state.copy(autoSendPubkey = !value) } }
        }
    }

    fun unlock(password: String) {
        viewModelScope.launch {
            _state.update { it.copy(autoUnlocking = false, unlockError = null) }
            if (!manager.openVault(password)) {
                _state.update { it.copy(unlockError = UiError.WrongPassword) }
                return@launch
            }
            if (_state.value.rememberPassword) passwordStore.save(password) else passwordStore.clear()
            completeUnlock(password)
        }
    }

    /** Opens the active profile after the vault accepted [password]. */
    private suspend fun completeUnlock(password: String) {
        session.setPassword(password)
        val profile = manager.activeProfile() ?: manager.firstProfile()
        if (profile == null) {
            _state.update {
                it.copy(
                    unlocked = true, unlockError = null, autoUnlocking = false,
                    screen = AppScreen.Networks, activeProfile = null,
                )
            }
            return
        }
        _state.update {
            it.copy(
                unlocked = true, unlockError = null, activeId = profile.id,
                activeProfile = profile,
                networkName = networkNameFromPolicyJson(profile.policyJson),
            )
        }
        try {
            openActiveSession()
        } finally {
            // The session is open (or opening failed and moved to Networks):
            // release the locked auto-unlock form.
            _state.update { it.copy(autoUnlocking = false) }
        }
    }

    fun lock() = lockVault()

    /** Shows the logout confirmation (the app always requires the password on entry). */
    fun requestLogout() = _state.update { it.copy(logoutDialog = true) }

    fun cancelLogout() = _state.update { it.copy(logoutDialog = false) }

    /** Logout: same as fm-app — drop decrypted data and require the password. */
    fun confirmLogout() {
        _state.update { it.copy(logoutDialog = false) }
        val opened = networkSession
        if (opened == null) {
            lockVault()
            return
        }
        viewModelScope.launch {
            // Best effort: remove this client from the sync list so it stops
            // receiving copies. Never block logout on a dead node.
            withTimeoutOrNull(2_000) { runCatching { opened.unregisterClient() } }
            lockVault()
        }
    }

    private fun lockVault() {
        closeSession()
        manager.closeVault()
        session.lock()
        passwordStore.clear()
        _state.update {
            clearDecryptedState(
                it.copy(
                    unlocked = false,
                    screen = if (manager.vaultExists()) AppScreen.Unlock else AppScreen.Networks,
                    unlockError = null,
                    activeId = null,
                    rememberPassword = false,
                    autoUnlocking = false,
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
        remoteSubscribeDialog = false,
        remoteSubscribeEcho = "",
        remoteSubscribeHost = "",
        remoteSubscribeSending = false,
        remoteSubscribeStatus = null,
        remoteSubscribeError = null,
        appealTarget = null,
        appealReason = "",
        appealSending = false,
        appealStatus = null,
        appealError = null,
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
        policyEdit = null,
        removeEchoDialog = false,
        syncing = false,
        syncError = null,
        logoutDialog = false,
        backupStatus = null,
        backupError = null,
    )

    /** Loads the user-defined conference groups of [opened] into the UI state (#366). */
    private suspend fun loadEchoGroups(opened: NetworkSession) {
        val groups = runCatching { opened.echoGroups() }.getOrDefault(emptyList())
        _state.update { state ->
            state.copy(echoGroups = groups.map { EchoGroupUi(it.id, it.name, it.echoes) })
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
        _state.update {
            it.copy(
                activeProfile = profile,
                activeId = profile.id,
                networkName = networkNameFromPolicyJson(profile.policyJson),
                // The node hides the voucher button when the network disabled them.
                voucherEnabled = policyFlag(profile.policyJson, "voucher_enable", true),
                syncError = null,
            )
        }
        try {
            val opened = sessionFor(profile, password)
            networkSession = opened
            val tagline = runCatching { opened.tagline() }.getOrDefault("")
            val showTearline = runCatching { opened.showTearline() }.getOrDefault(false)
            val showServiceMessages = runCatching {
                opened.showServiceMessages()
            }.getOrDefault(false)
            val allowRateOverride = runCatching {
                opened.allowSendingWithoutRateLimit()
            }.getOrDefault(false)
            _state.update {
                it.copy(
                    tagline = tagline,
                    showTearline = showTearline,
                    showServiceMessages = showServiceMessages,
                    allowSendingWithoutRateLimit = allowRateOverride,
                )
            }
            loadEchoGroups(opened)
            _state.update { it.copy(syncing = true) }
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
            // Privacy switches live in the profile database, not in the vault.
            _state.update {
                it.copy(
                    autoRequestPubkeys = runCatching { opened.autoRequestPubkeys() }.getOrDefault(false),
                    autoSendPubkey = runCatching { opened.autoSendPubkey() }.getOrDefault(false),
                    allowSendingWithoutRateLimit = runCatching {
                        opened.allowSendingWithoutRateLimit()
                    }.getOrDefault(false),
                )
            }
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

    /**
     * Drops the cached session of [profileId] so the next open builds a new one
     * from the stored profile. Needed when the endpoint changes (recovery): the
     * cached session keeps its old API client and would talk to the old node.
     */
    private fun forgetSession(profileId: String) {
        groupJobs.remove(profileId)?.cancel()
        groupEchoes.remove(profileId)
        val opened = sessions.remove(profileId) ?: return
        if (networkSession === opened) networkSession = null
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
                        rulesText = echo.rulesJson,
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
                // A network is shown by the name from its accepted policy (#20);
                // the HTTP endpoint stays as secondary information.
                // `displayName` falls back to the profile title when the policy
                // carries no name, so the endpoint never becomes the heading.
                networkName = networkNameFromPolicyJson(profile.policyJson),
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
                            rulesText = echo.rulesJson,
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
                            rulesText = echo.rulesJson,
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
                    // Votes are folded into their poster so the list shows counts.
                    val votes = VoteMapping.collect(rows, messageJson)
                    val me = opened.profile.address
                    _state.update {
                        it.copy(protocols = rows.mapNotNull { row -> row.toProtocolUi(votes, me) })
                    }
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
                        combine(
                            opened.database.messageDao().observeEchoRows(echoName).catch { },
                            _state.map { it.showServiceMessages }.distinctUntilChanged(),
                        ) { rows, showService -> rows to showService }
                            .collect { (rows, showService) ->
                                _state.update {
                                    it.copy(messages = mapMessages(rows, opened, showService))
                                }
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
            _state.update {
                it.copy(
                    syncing = false,
                    syncError = null,
                    rateLimits = opened.rateLimitUsage,
                )
            }
            revalidateCompose()
            revalidateNetmailCompose()
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
        // A name changed on another client follows the same route as the address.
        val remoteName = identity.name.trim()
        if (remoteName.isNotEmpty() && remoteName != opened.profile.displayName) {
            opened.applyNameChange(remoteName)
            manager.updateProfileDisplayName(opened.profile.id, remoteName)
            _state.update { state ->
                val updated = state.activeProfile?.let { current ->
                    if (current.id == opened.profile.id) current.copy(displayName = remoteName) else current
                }
                state.copy(activeProfile = updated)
            }
        }
        // Rights are granted and revoked on the node: follow them like the name,
        // otherwise a demoted admin keeps the admin UI forever.
        if (identity.isAdmin != opened.profile.isAdmin || identity.isModerator != opened.profile.isModerator) {
            manager.updateProfileRoles(opened.profile.id, identity.isAdmin, identity.isModerator)
            opened.applyRoleChange(identity.isAdmin, identity.isModerator)
            _state.update { state ->
                val updated = state.activeProfile?.let { current ->
                    if (current.id == opened.profile.id) {
                        current.copy(isAdmin = identity.isAdmin, isModerator = identity.isModerator)
                    } else {
                        current
                    }
                }
                state.copy(activeProfile = updated)
            }
        }
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
                val policyJson = policy.payload.toString()
                manager.updateProfilePolicy(opened.profile.id, policyJson)
                pendingPolicy = null
                _state.update {
                    it.copy(
                        showPolicyResign = false,
                        policyResignText = "",
                        networkName = networkNameFromPolicyJson(policyJson),
                        // Policy switches (e.g. vouchers) follow the new policy.
                        voucherEnabled = policyFlag(policyJson, "voucher_enable", true),
                        activeProfile = it.activeProfile?.copy(policyJson = policyJson),
                    )
                }
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
                passwordStore.clear()
                _state.update {
                    clearDecryptedState(
                        it.copy(
                            unlocked = false,
                            activeId = null,
                            activeProfile = null,
                            screen = AppScreen.Networks,
                            unlockError = null,
                            rememberPassword = false,
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
        val current = _state.value
        val echo = current.selectedEcho ?: return
        val opened = networkSession
        _state.update {
            it.copy(
                screen = AppScreen.Compose,
                compose = ComposeState(tagline = current.tagline),
            )
        }
        revalidateCompose()
        if (opened != null) {
            viewModelScope.launch {
                opened.database.draftDao().get(echo.name)?.let { draft ->
                    _state.update {
                        if (it.screen == AppScreen.Compose) {
                            // The draft carries only the text; keep the tagline
                            // and any reply context that was set on open.
                            it.copy(compose = it.compose.copy(subject = draft.subject, body = draft.body))
                        } else {
                            it
                        }
                    }
                }
                revalidateCompose()
            }
        }
    }

    /** Opens the echo composer with a quoted message (reply action). */
    fun replyToEchoMessage(message: MessageUi) {
        val current = _state.value
        val echo = current.echoes.firstOrNull { it.name == message.echoName } ?: return
        val label = message.senderName.ifBlank { message.senderAddress }
        val quote = Quote.formatFidoQuote(label, message.body)
        _state.update {
            it.copy(
                screen = AppScreen.Compose,
                selectedEcho = echo,
                compose = ComposeState(
                    subject = Quote.replySubject(message.subject),
                    body = if (quote.isBlank()) "" else "$quote\n\n",
                    replyToMessageId = message.messageId,
                    replyToLabel = label,
                    replyQuote = quote,
                    tagline = current.tagline,
                ),
            )
        }
        revalidateCompose()
    }

    /** Removes the quoted reply and its subject prefix. */
    fun clearComposeReply() {
        _state.update {
            val compose = it.compose
            val body = if (compose.replyQuote.isBlank()) {
                compose.body
            } else {
                compose.body.replaceFirst(compose.replyQuote + "\n\n", "")
            }
            it.copy(
                compose = compose.copy(
                    subject = "",
                    body = body,
                    replyToMessageId = null,
                    replyToLabel = "",
                    replyQuote = "",
                ),
            )
        }
        revalidateCompose()
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

    fun setComposeSubject(value: String) {
        _state.update { it.copy(compose = it.compose.copy(subject = value, error = null)) }
        revalidateCompose()
    }

    fun setComposeBody(value: String) {
        _state.update { it.copy(compose = it.compose.copy(body = value, error = null)) }
        revalidateCompose()
    }

    fun setComposeAnnouncement(value: Boolean) {
        _state.update { it.copy(compose = it.compose.copy(announcement = value)) }
        revalidateCompose()
    }

    fun setComposeTagline(value: String) {
        _state.update { it.copy(compose = it.compose.copy(tagline = value)) }
        revalidateCompose()
    }

    /** Effective client tearline for size estimation and sending. */
    private fun tearlineFor(state: UiState): String? {
        if (!state.showTearline) return null
        return "fm-android v${BuildConfig.VERSION_NAME} ${state.activeProfile?.address.orEmpty()}"
    }

    private fun revalidateCompose() {
        val state = _state.value
        val limits = MessageLimits.fromPolicy(
            state.activeProfile?.policyJson.orEmpty(),
            state.selectedEcho?.rulesText,
        )
        val kind = echoRateLimitKind(state.compose.announcement)
        val issues = limits.validate(
            subject = state.compose.subject,
            body = state.compose.body,
            tagline = state.compose.tagline,
            tearline = tearlineFor(state),
            attachments = state.compose.attachments,
            replyToMessageId = state.compose.replyToMessageId,
            senderName = state.activeProfile?.displayName.orEmpty(),
            rateLimit = kind?.let { state.rateLimits[it] },
            rateLimitKind = kind,
            allowRateLimitOverride = state.allowSendingWithoutRateLimit,
        )
        _state.update { it.copy(compose = it.compose.copy(limitIssues = issues)) }
    }

    /** True when one more message of [kind] would exceed the server usage. */
    private fun rateLimitOver(kind: String): Boolean {
        val usage = _state.value.rateLimits[kind] ?: return false
        return usage.limit > 0 && usage.used + 1 > usage.limit
    }

    /** Shows the same warning the composer gets and returns true when blocked. */
    private fun blockRateLimit(kind: String): Boolean {
        if (!rateLimitOver(kind)) return false
        if (_state.value.allowSendingWithoutRateLimit) return false
        _state.update {
            it.copy(
                sendErrors = (listOf("Daily rate limit exceeded for $kind") + it.sendErrors)
                    .take(MAX_SEND_ERRORS),
            )
        }
        return true
    }

    private fun rateLimitOverride(kind: String): Boolean =
        _state.value.allowSendingWithoutRateLimit && rateLimitOver(kind)

    private fun revalidateNetmailCompose() {
        val state = _state.value
        val limits = MessageLimits.fromPolicy(state.activeProfile?.policyJson.orEmpty())
        val kind = NETMAIL_RATE_LIMIT_KIND
        val issues = limits.validate(
            subject = state.netmailCompose.subject,
            body = state.netmailCompose.body,
            tagline = state.netmailCompose.tagline,
            tearline = tearlineFor(state),
            attachments = state.netmailCompose.attachments,
            replyToMessageId = state.netmailCompose.replyToMessageId,
            senderName = state.activeProfile?.displayName.orEmpty(),
            rateLimit = state.rateLimits[kind],
            rateLimitKind = kind,
            allowRateLimitOverride = state.allowSendingWithoutRateLimit,
        )
        _state.update { it.copy(netmailCompose = it.netmailCompose.copy(limitIssues = issues)) }
    }

    /**
     * Entry point for the system file picker. Images go through the resize
     * dialog; other files are added directly to the target composer.
     */
    fun prepareAttachments(
        files: List<Triple<String, String, ByteArray>>,
        target: AttachmentTarget,
    ) {
        if (files.isEmpty()) return
        if (files.none { it.second.startsWith("image/") }) {
            addToTarget(files, target)
            return
        }
        pendingAttachmentFiles = files
        pendingAttachmentTarget = target
        val opened = networkSession
        viewModelScope.launch {
            val max = runCatching { opened?.imageMaxDimension() }.getOrNull() ?: 1280
            _state.update {
                it.copy(
                    imageResizeDialog = true,
                    imageMaxDimension = max,
                    rememberImageDimension = false,
                )
            }
        }
    }

    fun setImageMaxDimension(value: Int) =
        _state.update { it.copy(imageMaxDimension = value) }

    fun setRememberImageDimension(value: Boolean) =
        _state.update { it.copy(rememberImageDimension = value) }

    fun cancelImageResize() {
        pendingAttachmentFiles = null
        _state.update { it.copy(imageResizeDialog = false) }
    }

    fun confirmImageResize() {
        val files = pendingAttachmentFiles ?: return
        val target = pendingAttachmentTarget
        val maxDimension = _state.value.imageMaxDimension
        val remember = _state.value.rememberImageDimension
        pendingAttachmentFiles = null
        _state.update { it.copy(imageResizeDialog = false) }
        val opened = networkSession
        viewModelScope.launch {
            if (remember && opened != null) {
                runCatching { opened.setImageMaxDimension(maxDimension) }
            }
            val processed = withContext(Dispatchers.IO) {
                files.map { (name, type, bytes) ->
                    if (type.startsWith("image/")) {
                        val image = ImageProcessor.process(bytes, name, type, maxDimension)
                        Triple(image.name, image.type, image.bytes)
                    } else {
                        Triple(name, type, bytes)
                    }
                }
            }
            addToTarget(processed, target)
        }
    }

    private fun withAttachmentRefs(body: String, refs: List<String>): String {
        if (refs.isEmpty()) return body
        val prefix = if (body.isBlank()) "" else body.trimEnd() + "\n\n"
        return prefix + refs.joinToString("\n")
    }

    private fun addToTarget(files: List<Triple<String, String, ByteArray>>, target: AttachmentTarget) {
        when (target) {
            AttachmentTarget.Echo -> addAttachments(files)
            AttachmentTarget.Netmail -> addNetmailAttachments(files)
            AttachmentTarget.Blog -> addBlogAttachments(files)
        }
    }

    /** Adds files (already read from the system picker) to the composer. */
    fun addAttachments(files: List<Triple<String, String, ByteArray>>) {
        val state = _state.value
        val limits = MessageLimits.fromPolicy(
            state.activeProfile?.policyJson.orEmpty(),
            state.selectedEcho?.rulesText,
        )
        val existing = state.compose.attachments
        val result = existing.toMutableList()
        val refs = mutableListOf<String>()
        for ((name, type, bytes) in files) {
            if (limits.maxAttachmentSize > 0 && bytes.size > limits.maxAttachmentSize) {
                _state.update {
                    it.copy(compose = it.compose.copy(error = UiError.Failed("Attachment $name is too large (max ${limits.maxAttachmentSize} bytes)")))
                }
                return
            }
            if (limits.maxAttachments > 0 && result.size >= limits.maxAttachments) {
                _state.update {
                    it.copy(compose = it.compose.copy(error = UiError.Failed("Too many attachments (max ${limits.maxAttachments})")))
                }
                return
            }
            val index = result.size
            result += AttachmentUi(
                name = name,
                type = type,
                size = bytes.size,
                dataUrl = "data:$type;base64," + android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP),
            )
            refs += if (type.startsWith("image/")) {
                "![$name](attachment:$index)"
            } else {
                "[$name](attachment:$index)"
            }
        }
        val body = withAttachmentRefs(state.compose.body, refs)
        _state.update {
            it.copy(compose = it.compose.copy(attachments = result, body = body, error = null))
        }
        revalidateCompose()
    }

    fun removeAttachment(index: Int) {
        _state.update {
            val list = it.compose.attachments.toMutableList()
            if (index in list.indices) list.removeAt(index)
            it.copy(compose = it.compose.copy(attachments = list))
        }
        revalidateCompose()
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
        if (current.compose.limitIssues.any { it.blocking }) {
            _state.update { it.copy(compose = it.compose.copy(error = UiError.Failed("Message exceeds a policy limit"))) }
            return
        }
        val rateLimitOverride = current.allowSendingWithoutRateLimit &&
            current.compose.limitIssues.any { it is MessageLimitIssue.RateLimitExceeded }
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
                    replyToMessageId = current.compose.replyToMessageId,
                    attachments = attachments,
                    announcement = current.compose.announcement,
                    tagline = current.compose.tagline,
                    tearline = if (opened.showTearline()) opened.clientTearline() else null,
                    rateLimitOverride = rateLimitOverride,
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

    fun setAddRememberPassword(value: Boolean) =
        _state.update { it.copy(add = it.add.copy(rememberPassword = value)) }

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
                if (add.rememberPassword) passwordStore.save(password) else passwordStore.clear()
                _state.update {
                    it.copy(add = AddNetworkState(), unlocked = true, rememberPassword = add.rememberPassword)
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
                    tagline = it.tagline,
                ),
            )
        }
        revalidateNetmailCompose()
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
                    tagline = it.tagline,
                ),
            )
        }
        revalidateNetmailCompose()
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
        if (blockRateLimit("vote")) return
        val override = rateLimitOverride("vote")
        viewModelScope.launch {
            runCatching {
                opened.postElectionVote(protocol.echoName, candidate, rateLimitOverride = override)
            }
            syncOnce(opened)
        }
    }

    /**
     * Answers a refill request with our own history of that conference.
     *
     * A thumbs-up reaction does nothing here: the node only delivers history for
     * a `refill` response that carries the messages themselves.
     */
    fun respondRefill(protocol: ProtocolUi) {
        val opened = networkSession ?: return
        viewModelScope.launch {
            _state.update { it.copy(refillRespondStatus = null) }
            val row = runCatching {
                opened.database.messageDao().getByEchoAndId(protocol.echoName, protocol.messageId)
            }.getOrNull()
            val request: Pair<String, String>? = row?.let { refillRequestOf(it.dataJson) }
            if (request == null) {
                _state.update { it.copy(sendErrors = (listOf("Refill request has no date") + it.sendErrors).take(MAX_SEND_ERRORS)) }
                return@launch
            }
            val sent = runCatching { opened.respondRefill(protocol.echoName, request.first, request.second) }
            sent.onSuccess { count ->
                if (count == 0) {
                    _state.update {
                        it.copy(sendErrors = (listOf("No stored messages to send for ${protocol.echoName}") + it.sendErrors).take(MAX_SEND_ERRORS))
                    }
                } else {
                    _state.update { it.copy(refillRespondStatus = "Sent $count message(s)") }
                }
            }.onFailure { err ->
                _state.update {
                    it.copy(sendErrors = (listOf(err.message ?: "Refill response failed") + it.sendErrors).take(MAX_SEND_ERRORS))
                }
            }
            syncOnce(opened)
        }
    }

    /** Starts a moderator election in the currently open echo (election poster). */
    fun startElection() {
        val echo = _state.value.selectedEcho ?: return
        val opened = networkSession ?: return
        if (blockRateLimit("poster")) return
        val override = rateLimitOverride("poster")
        viewModelScope.launch {
            runCatching { opened.postElectionPoster(echo.name, rateLimitOverride = override) }
            syncOnce(opened)
        }
    }

    fun castVote(protocol: ProtocolUi, variant: String) {
        val opened = networkSession ?: return
        if (blockRateLimit("vote")) return
        val override = rateLimitOverride("vote")
        viewModelScope.launch {
            // A vote refers to its poster by text (`poster_message`), not by id.
            runCatching {
                opened.postVote(protocol.echoName, protocol.title, variant, rateLimitOverride = override)
            }
            syncOnce(opened)
        }
    }

    /** Votes (or retracts with an empty variant) on a card in the feed. */
    fun castFeedVote(vote: VoteCardUi, variant: String) {
        val opened = networkSession ?: return
        if (blockRateLimit("vote")) return
        val override = rateLimitOverride("vote")
        viewModelScope.launch {
            runCatching {
                opened.postVote(vote.echoName, vote.posterMessage, variant, rateLimitOverride = override)
            }
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

    // ------------------------------------------------------- remote subscribe

    fun openRemoteSubscribeDialog() = _state.update {
        it.copy(
            remoteSubscribeDialog = true,
            remoteSubscribeEcho = "",
            remoteSubscribeHost = "",
            remoteSubscribeStatus = null,
            remoteSubscribeError = null,
        )
    }

    fun closeRemoteSubscribeDialog() =
        _state.update { it.copy(remoteSubscribeDialog = false) }

    fun setRemoteSubscribeEcho(value: String) =
        _state.update { it.copy(remoteSubscribeEcho = value, remoteSubscribeError = null) }

    fun setRemoteSubscribeHost(value: String) =
        _state.update { it.copy(remoteSubscribeHost = value, remoteSubscribeError = null) }

    fun sendRemoteSubscribe() {
        val echo = _state.value.remoteSubscribeEcho.trim()
        val host = _state.value.remoteSubscribeHost.trim()
        val opened = networkSession ?: return
        if (echo.isEmpty() || host.isEmpty()) {
            _state.update {
                it.copy(remoteSubscribeError = UiError.Failed("Channel and host node are required"))
            }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(remoteSubscribeSending = true, remoteSubscribeError = null) }
            try {
                opened.postRemoteSubscribe(echo, host)
                _state.update {
                    it.copy(
                        remoteSubscribeSending = false,
                        remoteSubscribeStatus = "Request sent to $host",
                    )
                }
                syncOnce(opened)
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        remoteSubscribeSending = false,
                        remoteSubscribeError = UiError.Failed(e.message ?: "Failed"),
                    )
                }
            }
        }
    }

    // ------------------------------------------------------------------ appeal

    fun openAppeal(message: MessageUi) {
        if (message.moderation == null) return
        _state.update {
            it.copy(
                appealTarget = message,
                appealReason = "",
                appealSending = false,
                appealStatus = null,
                appealError = null,
            )
        }
    }

    fun closeAppeal() = _state.update { it.copy(appealTarget = null) }

    fun setAppealReason(value: String) =
        _state.update { it.copy(appealReason = value, appealError = null) }

    fun submitAppeal() {
        val message = _state.value.appealTarget ?: return
        val moderation = message.moderation ?: return
        val opened = networkSession ?: return
        val policy = _state.value.activeProfile?.policyJson.orEmpty()
        val council = adminCouncilName(policy)
        val reason = _state.value.appealReason.trim().ifBlank { "Appeal: ${moderation.severity} in ${message.echoName}" }
        viewModelScope.launch {
            _state.update { it.copy(appealSending = true, appealError = null, appealStatus = null) }
            try {
                val bundle = buildJsonObject {
                    put("type", "fm-selected-messages")
                    put("version", 1)
                    put("generated_at", System.currentTimeMillis() / 1000)
                    put("messages", buildJsonArray {
                        add(buildJsonObject {
                            put("message_id", message.messageId)
                            put("type", "echomail")
                            put("sender_address", message.senderAddress)
                            put("timestamp", message.timestamp)
                            put("echo_name", message.echoName)
                            put("data", buildJsonObject {
                                put("subject", message.subject)
                                put("body", message.body)
                            }.toString())
                        })
                    })
                }
                val bundleJson = bundle.toString()
                val attachments = buildJsonArray {
                    add(buildJsonObject {
                        put("name", message.messageId + ".json")
                        put("type", "application/json")
                        put("size", bundleJson.length)
                        put(
                            "data",
                            "data:application/json;base64," +
                                Encoding.base64Encode(bundleJson.toByteArray(Charsets.UTF_8)),
                        )
                    })
                }
                opened.postAppeal(
                    echoName = council,
                    moderator = message.senderAddress,
                    punishmentMessageId = message.messageId,
                    punishmentEcho = message.echoName,
                    severity = moderation.severity,
                    targetAddress = moderation.targetAddress,
                    reason = reason,
                    attachments = attachments,
                )
                _state.update {
                    it.copy(appealSending = false, appealStatus = "Appeal submitted to $council")
                }
                syncOnce(opened)
            } catch (e: Exception) {
                _state.update {
                    it.copy(appealSending = false, appealError = UiError.Failed(e.message ?: "Failed"))
                }
            }
        }
    }

    // -------------------------------------------------------------- reactions

    fun toggleReaction(message: MessageUi, emoji: String) {
        val opened = networkSession ?: return
        val echo = _state.value.selectedEcho ?: return
        if (blockRateLimit("reaction")) return
        val override = rateLimitOverride("reaction")
        viewModelScope.launch {
            val next = if (message.myReaction == emoji) "" else emoji
            runCatching {
                opened.postReaction(message.messageId, next, echo.name, rateLimitOverride = override)
            }
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
        if (!subscribed) {
            viewModelScope.launch {
                applySubscription(opened, echoName, false)
            }
            return
        }
        // An echo with rules needs the user's acceptance first: show the rules
        // instead of subscribing silently (the node answers `next: accept_rules`).
        val rules = _state.value.allEchoes.firstOrNull { it.name == echoName }?.rulesText
            ?: _state.value.echoes.firstOrNull { it.name == echoName }?.rulesText
        if (!rules.isNullOrBlank()) {
            _state.update { it.copy(rulesPrompt = RulesPromptUi(echoName, formatEchoRules(rules))) }
            return
        }
        viewModelScope.launch { applySubscription(opened, echoName, true) }
    }

    /** Accepts the rules shown in the prompt and subscribes. */
    fun confirmRulesPrompt() {
        val prompt = _state.value.rulesPrompt ?: return
        val opened = networkSession ?: return
        _state.update { it.copy(rulesPrompt = null) }
        viewModelScope.launch { applySubscription(opened, prompt.echoName, true) }
    }

    fun cancelRulesPrompt() = _state.update { it.copy(rulesPrompt = null) }

    /** Subscribes and reports a rejection instead of swallowing it. */
    private suspend fun applySubscription(opened: NetworkSession, echoName: String, subscribed: Boolean) {
        try {
            opened.setSubscribed(echoName, subscribed)
            runCatching { opened.refreshEchoes() }
        } catch (e: Exception) {
            _state.update {
                it.copy(syncError = UiError.Failed("$echoName: ${e.message ?: "subscription failed"}"))
            }
        }
    }

    // ------------------------------------------------------------------ votes

    /** Opens the "new vote" dialog of the current conference. */
    fun openVoteCompose() {
        if (_state.value.selectedEcho == null) return
        _state.update { it.copy(voteCompose = VoteComposeState()) }
    }

    fun closeVoteCompose() = _state.update { it.copy(voteCompose = null) }

    fun setVoteQuestion(value: String) = _state.update { state ->
        val draft = state.voteCompose ?: return@update state
        state.copy(voteCompose = draft.copy(question = value, error = null))
    }

    fun setVoteVariant(index: Int, value: String) = _state.update { state ->
        val draft = state.voteCompose ?: return@update state
        if (index !in draft.variants.indices) return@update state
        val variants = draft.variants.toMutableList()
        variants[index] = value
        state.copy(voteCompose = draft.copy(variants = variants, error = null))
    }

    fun addVoteVariant() = _state.update { state ->
        val draft = state.voteCompose ?: return@update state
        state.copy(voteCompose = draft.copy(variants = draft.variants + ""))
    }

    fun removeVoteVariant(index: Int) = _state.update { state ->
        val draft = state.voteCompose ?: return@update state
        // Two variants are the minimum a vote can offer.
        if (draft.variants.size <= 2 || index !in draft.variants.indices) return@update state
        state.copy(voteCompose = draft.copy(variants = draft.variants.filterIndexed { i, _ -> i != index }))
    }

    fun submitVoteCompose() {
        val opened = networkSession ?: return
        val echo = _state.value.selectedEcho ?: return
        val draft = _state.value.voteCompose ?: return
        val question = draft.question.trim()
        val variants = draft.variants.map { it.trim() }.filter { it.isNotEmpty() }
        if (question.isEmpty()) {
            _state.update { it.copy(voteCompose = draft.copy(error = UiError.Failed("Question is required"))) }
            return
        }
        if (variants.size < 2) {
            _state.update { it.copy(voteCompose = draft.copy(error = UiError.Failed("At least two options are required"))) }
            return
        }
        if (blockRateLimit("poster")) return
        val rateOverride = rateLimitOverride("poster")
        viewModelScope.launch {
            _state.update { it.copy(voteCompose = draft.copy(sending = true, error = null)) }
            try {
                opened.postVotePoster(
                    echo.name,
                    question,
                    variants,
                    rateLimitOverride = rateOverride,
                )
                _state.update { it.copy(voteCompose = null) }
                syncOnce(opened)
            } catch (e: Exception) {
                _state.update {
                    it.copy(voteCompose = it.voteCompose?.copy(sending = false, error = UiError.Failed(e.message ?: "Failed")))
                }
            }
        }
    }

    // ------------------------------------------------------------- echo rules

    /** Opens the rules/rename proposal editor of the current conference. */
    fun openRulesEditor() {
        val echo = _state.value.selectedEcho ?: return
        if (echo.isBlog) return
        _state.update {
            it.copy(
                rulesEdit = RulesEditState(
                    echoName = echo.name,
                    rulesJson = prettyEchoRules(echo.rulesText),
                ),
            )
        }
    }

    fun closeRulesEditor() = _state.update { it.copy(rulesEdit = null) }

    fun setRulesEditJson(value: String) = _state.update {
        it.copy(rulesEdit = it.rulesEdit?.copy(rulesJson = value, error = null, status = null))
    }

    fun setRulesEditName(value: String) = _state.update {
        it.copy(rulesEdit = it.rulesEdit?.copy(newName = value, error = null, status = null))
    }

    fun setRulesEditAllChunks(value: Boolean) = _state.update {
        it.copy(rulesEdit = it.rulesEdit?.copy(allChunksOnly = value))
    }

    fun submitRulesEdit() {
        val edit = _state.value.rulesEdit ?: return
        val opened = networkSession ?: return
        val rules = edit.rulesJson.trim()
        if (runCatching { messageJson.parseToJsonElement(rules).jsonObject }.isFailure) {
            _state.update { it.copy(rulesEdit = edit.copy(error = UiError.Failed("Rules must be a JSON object"))) }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(rulesEdit = edit.copy(sending = true, error = null, status = null)) }
            try {
                opened.proposeChangeRules(
                    echoName = edit.echoName,
                    rulesJson = rules,
                    allChunksOnly = edit.allChunksOnly,
                    newName = edit.newName.trim().takeIf { it.isNotEmpty() },
                )
                _state.update {
                    it.copy(rulesEdit = it.rulesEdit?.copy(sending = false, status = "Proposal queued"))
                }
                syncOnce(opened)
            } catch (e: Exception) {
                _state.update {
                    it.copy(rulesEdit = it.rulesEdit?.copy(sending = false, error = UiError.Failed(e.message ?: "Failed")))
                }
            }
        }
    }

    // -------------------------------------------------------------- echo groups

    /** User-defined groups of conferences, stored per network profile (#366). */
    fun openEchoGroupsDialog() {
        _state.update { it.copy(echoGroupsDialog = true, echoGroupDraftName = "", echoGroupSelection = emptySet(), echoGroupError = null) }
    }

    fun closeEchoGroupsDialog() = _state.update { it.copy(echoGroupsDialog = false) }

    fun setEchoGroupDraftName(value: String) = _state.update { it.copy(echoGroupDraftName = value, echoGroupError = null) }

    fun createEchoGroup() {
        val name = _state.value.echoGroupDraftName.trim()
        if (name.isEmpty()) {
            _state.update { it.copy(echoGroupError = UiError.Failed("Group name is required")) }
            return
        }
        val groups = _state.value.echoGroups + EchoGroupUi(
            id = "g_${System.currentTimeMillis()}_${java.util.UUID.randomUUID().toString().take(6)}",
            name = name,
            echoes = emptyList(),
        )
        persistEchoGroups(groups) { it.copy(echoGroupDraftName = "") }
    }

    fun deleteEchoGroup(id: String) = persistEchoGroups(_state.value.echoGroups.filterNot { it.id == id })

    fun toggleEchoGroupSelection(echoName: String) {
        _state.update {
            val selection = it.echoGroupSelection.toMutableSet()
            if (!selection.add(echoName)) selection.remove(echoName)
            it.copy(echoGroupSelection = selection)
        }
    }

    /** Moves the selected conferences into [groupId]; null removes them from every group. */
    fun assignSelectionToGroup(groupId: String?) {
        val selected = _state.value.echoGroupSelection
        if (selected.isEmpty()) return
        val groups = _state.value.echoGroups.map { group ->
            group.copy(echoes = group.echoes.filterNot { it in selected })
        }.toMutableList()
        if (groupId != null) {
            val index = groups.indexOfFirst { it.id == groupId }
            if (index >= 0) {
                val target = groups[index]
                groups[index] = target.copy(echoes = (target.echoes + selected).distinct())
            }
        }
        persistEchoGroups(groups) { it.copy(echoGroupSelection = emptySet()) }
    }

    private fun persistEchoGroups(groups: List<EchoGroupUi>, extra: (UiState) -> UiState = { it }) {
        // Update the UI first: assignment must be visible immediately even while
        // the database write is in flight (#28).
        _state.update { extra(it.copy(echoGroups = groups)) }
        val opened = networkSession ?: return
        viewModelScope.launch {
            runCatching { opened.saveEchoGroups(groups.map { NetworkSession.EchoGroup(it.id, it.name, it.echoes) }) }
                .onFailure { error ->
                    _state.update {
                        it.copy(echoGroupError = UiError.Failed(error.message ?: "Failed to save groups"))
                    }
                }
        }
    }

    // ------------------------------------------------------------ display name

    fun openDisplayNameDialog() {
        _state.update {
            it.copy(
                displayNameDialog = true,
                displayNameDraft = it.activeProfile?.displayName.orEmpty(),
                displayNameError = null,
            )
        }
    }

    fun closeDisplayNameDialog() = _state.update { it.copy(displayNameDialog = false) }

    fun setDisplayNameDraft(value: String) = _state.update { it.copy(displayNameDraft = value, displayNameError = null) }

    fun submitDisplayName() {
        val opened = networkSession ?: return
        val name = _state.value.displayNameDraft.trim()
        if (name.isEmpty()) {
            _state.update { it.copy(displayNameError = UiError.Failed("Name is required")) }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(displayNameSaving = true, displayNameError = null) }
            try {
                opened.setName(name)
                manager.updateProfileDisplayName(opened.profile.id, name)
                refreshIdentity(opened)
                _state.update {
                    it.copy(
                        displayNameSaving = false,
                        displayNameDialog = false,
                        activeProfile = it.activeProfile?.copy(displayName = name),
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(displayNameSaving = false, displayNameError = UiError.Failed(e.message ?: "Failed"))
                }
            }
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
                // The profile now points at the recovery node; a cached session
                // would keep the old endpoint and keep hitting the dead node.
                forgetSession(profile.id)
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
                    val clients = (node["clients"] as? kotlinx.serialization.json.JsonArray)
                        ?.mapNotNull { element ->
                            val obj = element as? JsonObject ?: return@mapNotNull null
                            val clientAddress = (obj["address"] as? JsonPrimitive)?.contentOrNull
                                ?: return@mapNotNull null
                            NodeClientUi(clientAddress, (obj["name"] as? JsonPrimitive)?.contentOrNull.orEmpty())
                        }
                        ?: emptyList()
                    NodeUi(
                        address = address,
                        url = (node["url"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                        isRecoverNode = (node["is_recover_node"] as? JsonPrimitive)?.content == "true",
                        isOpenNode = (node["is_open_node"] as? JsonPrimitive)?.content == "true",
                        isEchomailRouter = (node["is_echomail_router"] as? JsonPrimitive)?.content == "true",
                        name = (node["name"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                        clients = clients,
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
        val council = adminCouncilName(_state.value.activeProfile?.policyJson.orEmpty())
        if (target.isEmpty()) {
            _state.update { it.copy(excommError = UiError.Failed("Target address is required")) }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(excommSending = true, excommError = null, excommStatus = null) }
            try {
                val data = buildJsonObject {
                    put("echo", council)
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
                opened.postProtocol(council, "excommunicate", data)
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
        val council = moderatorsCouncilName(_state.value.activeProfile?.policyJson.orEmpty())
        if (target.isEmpty()) {
            _state.update { it.copy(removeEchoError = UiError.Failed("Echo name is required")) }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(removeEchoSending = true, removeEchoError = null, removeEchoStatus = null) }
            try {
                val data = removeEchoPayload(
                    from = opened.profile.address,
                    echoName = target,
                    reason = _state.value.removeEchoReason.trim(),
                    council = council,
                    date = java.time.Instant.now().toString(),
                )
                opened.postProtocol(council, "remove_echo", data)
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

    /**
     * Opens the admin policy editor: policy text, a field per known parameter and
     * the nested rate limits. Unknown parameters are preserved untouched.
     */
    fun openPolicyEditDialog() {
        val payload = _state.value.activeProfile?.policyJson ?: return
        val parsed = runCatching { messageJson.parseToJsonElement(payload).jsonObject }.getOrNull()
        val policy = parsed?.get("policy") as? JsonObject
        val text = (policy?.get("text") as? JsonPrimitive)?.contentOrNull.orEmpty()
        val params = policy?.get("parameters") as? JsonObject
        val defs = PolicyParams.editable(params)
        val fields = defs.map { def ->
            val value = PolicyParams.value(params, def.key)
            PolicyParamFieldUi(
                key = def.key,
                kind = def.kind,
                value = value,
                original = value,
                options = def.options,
                maxLength = def.maxLength,
            )
        }
        val rateLimits = PolicyParams.RATE_LIMIT_KEYS.associateWith { key ->
            val nested = params?.get(PolicyParams.RATE_LIMIT) as? JsonObject
            PolicyParams.value(nested, key)
        }
        // Nested maps that are not edited here must survive the round trip.
        val preserved = buildJsonObject {
            (params?.get(PolicyParams.SPECIAL_ECHOGROUPS))?.let {
                put(PolicyParams.SPECIAL_ECHOGROUPS, it)
            }
        }
        _state.update {
            it.copy(
                policyEdit = PolicyEditUi(
                    text = text,
                    originalText = text,
                    fields = fields,
                    rateLimits = rateLimits,
                    originalRateLimits = rateLimits,
                    preserved = preserved,
                ),
            )
        }
    }

    fun closePolicyEditDialog() {
        _state.update { it.copy(policyEdit = null) }
    }

    fun setPolicyEditText(value: String) = _state.update {
        it.copy(policyEdit = it.policyEdit?.copy(text = value, error = null, status = null))
    }

    /** Updates one parameter field; unknown keys are ignored. */
    fun setPolicyEditField(key: String, value: String) = _state.update { state ->
        val edit = state.policyEdit ?: return@update state
        state.copy(
            policyEdit = edit.copy(
                fields = edit.fields.map { field ->
                    if (field.key == key) field.copy(value = value) else field
                },
                error = null,
                status = null,
            ),
        )
    }

    fun setPolicyEditRateLimit(key: String, value: String) = _state.update { state ->
        val edit = state.policyEdit ?: return@update state
        state.copy(
            policyEdit = edit.copy(
                rateLimits = edit.rateLimits + (key to value),
                error = null,
                status = null,
            ),
        )
    }

    fun setPolicyEditAllChunks(value: Boolean) = _state.update { state ->
        val edit = state.policyEdit ?: return@update state
        state.copy(policyEdit = edit.copy(allChunksOnly = value))
    }

    /** Recomputes the diff preview shown before sending the proposal. */
    fun previewPolicyDiff() = _state.update { state ->
        val edit = state.policyEdit ?: return@update state
        state.copy(policyEdit = edit.copy(showDiff = true))
    }

    fun submitPolicyEdit() {
        val opened = networkSession ?: return
        val edit = _state.value.policyEdit ?: return
        if (edit.hasInvalidFields) {
            _state.update {
                it.copy(policyEdit = edit.copy(error = UiError.Failed("Invalid duration value in the policy")))
            }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(policyEdit = edit.copy(sending = true, error = null, status = null)) }
            try {
                val parameters = buildJsonObject {
                    edit.fields.forEach { field ->
                        if (field.value.isNotBlank()) put(field.key, field.value.trim())
                    }
                    val limits = edit.rateLimits.filterValues { it.isNotBlank() }
                    if (limits.isNotEmpty()) {
                        put(
                            PolicyParams.RATE_LIMIT,
                            buildJsonObject { limits.forEach { (key, value) -> put(key, value.trim()) } },
                        )
                    }
                    edit.preserved.forEach { (key, value) -> put(key, value) }
                }
                val council = adminCouncilName(_state.value.activeProfile?.policyJson.orEmpty())
                val data = buildJsonObject {
                    put("proto", "change_policy")
                    put("from", opened.profile.address)
                    put("to", "all")
                    put("subject", "Change policy")
                    put("message", "Policy change proposal")
                    put("date", java.time.Instant.now().toString())
                    put("node1", opened.profile.address)
                    put("all_chunks_only", edit.allChunksOnly)
                    put(
                        "proposed_policy",
                        buildJsonObject {
                            put("text", edit.text)
                            put("parameters", parameters)
                        },
                    )
                }.toString()
                opened.postProtocol(council, "change_policy", data)
                _state.update {
                    it.copy(policyEdit = it.policyEdit?.copy(sending = false, status = "Proposal submitted"))
                }
                syncOnce(opened)
            } catch (e: Exception) {
                _state.update {
                    it.copy(policyEdit = it.policyEdit?.copy(sending = false, error = UiError.Failed(e.message ?: "Failed")))
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
                createEchoRules = DEFAULT_ECHO_RULES,
                createEchoNetworkChannel = false,
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

    fun setCreateEchoRules(value: String) =
        _state.update { it.copy(createEchoRules = value, createEchoError = null) }

    fun setCreateEchoNetworkChannel(value: Boolean) =
        _state.update { it.copy(createEchoNetworkChannel = value) }

    fun setCreateEchoWriters(value: String) =
        _state.update { it.copy(createEchoWriters = value, createEchoError = null) }

    fun setCreateEchoElectWritersFrom(value: String) =
        _state.update { it.copy(createEchoElectWritersFrom = value, createEchoError = null) }

    fun setCreateEchoRetractVote(value: Boolean) =
        _state.update { it.copy(createEchoRetractVote = value, createEchoError = null) }

    fun setCreateEchoRetractVoteDays(value: String) =
        _state.update { it.copy(createEchoRetractVoteDays = value, createEchoError = null) }

    fun createLocalEcho() {
        val opened = networkSession ?: return
        val state = _state.value
        val name = state.createEchoName.trim()
        if (name.isEmpty()) {
            _state.update { it.copy(createEchoError = UiError.Failed("Name is required")) }
            return
        }
        if (state.echoes.any { it.name.equals(name, ignoreCase = true) } ||
            state.blogs.any { it.name.equals(name, ignoreCase = true) } ||
            state.allEchoes.any { it.name.equals(name, ignoreCase = true) && !it.isBlog }
        ) {
            _state.update { it.copy(createEchoError = UiError.Failed("Name is already in use")) }
            return
        }
        if (state.createEchoType == CreateEchoType.Blog) {
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
        // Writers and the writer election are conference rules on the node: a
        // local echo sends them as separate fields, a rules-based one inside
        // `rules.parameters` (same shape fm-app sends).
        val writers = splitAddressList(state.createEchoWriters)
        val electFrom = splitAddressList(state.createEchoElectWritersFrom)
        val retractDays = state.createEchoRetractVoteDays.trim().toIntOrNull() ?: 1
        viewModelScope.launch {
            try {
                when (state.createEchoType) {
                    CreateEchoType.LocalEcho -> opened.createLocalEcho(
                        name = name,
                        description = state.createEchoDescription.trim(),
                        writers = writers,
                        electWritersFrom = electFrom,
                        retractVote = state.createEchoRetractVote,
                        retractVoteDays = retractDays,
                    )
                    CreateEchoType.WithRules, CreateEchoType.Channel -> {
                        val rules = echoRulesWithWriters(
                            rulesJson = if (state.createEchoType == CreateEchoType.Channel) {
                                DEFAULT_ECHO_RULES
                            } else {
                                state.createEchoRules.trim()
                            },
                            echoName = name,
                            writers = writers,
                            electWritersFrom = electFrom,
                            retractVote = state.createEchoRetractVote,
                            retractVoteDays = retractDays,
                        ) ?: run {
                            _state.update {
                                it.copy(createEchoError = UiError.Failed("Rules must be a JSON object"))
                            }
                            return@launch
                        }
                        opened.createEchoWithRules(
                            name = name,
                            description = state.createEchoDescription.trim(),
                            rulesJson = rules,
                            // A channel is announced network-wide; a plain
                            // rules-based echo follows the dialog switch.
                            networkChannel = state.createEchoType == CreateEchoType.WithRules &&
                                state.createEchoNetworkChannel,
                        )
                    }
                    CreateEchoType.Blog -> Unit
                }
                runCatching { opened.refreshEchoes() }
                _state.update { it.copy(createEchoDialog = false) }
            } catch (e: Exception) {
                _state.update { it.copy(createEchoError = UiError.Failed(e.message ?: "Failed")) }
            }
        }
    }

    /** `":0:1, :0:2"` → `[":0:1", ":0:2"]`; blanks are dropped. */
    private fun splitAddressList(raw: String): List<String> =
        raw.split(',', '\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    /**
     * Puts writers, the writer-election source and vote retraction into the
     * rules map, replacing `{echoname}` in the text (fm-app does the same).
     * Returns null when [rulesJson] is not a JSON object.
     */
    private fun echoRulesWithWriters(
        rulesJson: String,
        echoName: String,
        writers: List<String>,
        electWritersFrom: List<String>,
        retractVote: Boolean,
        retractVoteDays: Int,
    ): String? {
        val rules = runCatching { messageJson.parseToJsonElement(rulesJson).jsonObject }.getOrNull()
            ?: return null
        val parameters = (rules["parameters"] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
        if (writers.isNotEmpty()) {
            parameters["writers"] = buildJsonArray { writers.forEach { add(JsonPrimitive(it)) } }
        }
        if (electWritersFrom.isNotEmpty()) {
            parameters["elect_writers_from"] = buildJsonArray { electWritersFrom.forEach { add(JsonPrimitive(it)) } }
        }
        if (retractVote) {
            parameters["retract_vote"] = JsonPrimitive("enable")
            parameters["retract_vote_days"] = JsonPrimitive(retractVoteDays.toString())
        }
        val text = (rules["text"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            .replace("{echoname}", echoName)
        return buildJsonObject {
            put("text", text)
            put("parameters", JsonObject(parameters))
        }.toString()
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

    /** Creates a voucher of the selected type and TTL (see the voucher docs). */
    fun createVoucher() {
        val opened = networkSession ?: return
        val state = _state.value
        val type = state.voucherType
        val ttl = state.voucherTtl.trim().takeIf { it.isNotEmpty() }
        viewModelScope.launch {
            _state.update { it.copy(voucherLoading = true, voucherError = null) }
            try {
                val issued = opened.createVoucher(type = type, ttl = ttl)
                _state.update {
                    it.copy(
                        voucherLoading = false,
                        voucherCode = issued.code,
                        voucherReusable = issued.reusable,
                        voucherExpiresAt = issued.expiresAt,
                    )
                }
                loadVouchers()
            } catch (e: Exception) {
                _state.update {
                    it.copy(voucherLoading = false, voucherError = UiError.Failed(e.message ?: "Failed"))
                }
            }
        }
    }

    fun setVoucherType(type: String) = _state.update { it.copy(voucherType = type, voucherError = null) }

    fun setVoucherTtl(ttl: String) = _state.update { it.copy(voucherTtl = ttl, voucherError = null) }

    /** Lists the vouchers of this user (an admin sees every voucher of the node). */
    fun loadVouchers() {
        val opened = networkSession ?: return
        viewModelScope.launch {
            _state.update { it.copy(vouchersLoading = true, vouchersError = null) }
            try {
                val rows = opened.vouchers()
                _state.update {
                    it.copy(
                        vouchersLoading = false,
                        vouchers = rows.map { row ->
                            VoucherUi(row.code, row.type, row.status, row.createdBy, row.expiresAt)
                        },
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(vouchersLoading = false, vouchersError = UiError.Failed(e.message ?: "Failed"))
                }
            }
        }
    }

    fun revokeVoucher(code: String) {
        val opened = networkSession ?: return
        viewModelScope.launch {
            try {
                opened.revokeVoucher(code)
                loadVouchers()
            } catch (e: Exception) {
                _state.update { it.copy(vouchersError = UiError.Failed(e.message ?: "Failed")) }
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
                netmailCompose = NetmailComposeState(
                    recipient = recipient,
                    tagline = it.tagline,
                ),
            )
        }
        revalidateNetmailCompose()
    }

    /** Opens the netmail composer with a quoted message (reply action). */
    fun replyToNetmailMessage(message: MessageUi) {
        val current = _state.value
        val label = message.senderName.ifBlank { message.senderAddress }
        val quote = Quote.formatFidoQuote(label, message.body)
        _state.update {
            it.copy(
                screen = AppScreen.NetmailCompose,
                netmailCompose = NetmailComposeState(
                    recipient = message.senderAddress,
                    subject = Quote.replySubject(message.subject),
                    body = if (quote.isBlank()) "" else "$quote\n\n",
                    replyToMessageId = message.messageId,
                    replyToLabel = label,
                    replyQuote = quote,
                    tagline = current.tagline,
                ),
            )
        }
        revalidateNetmailCompose()
    }

    /** Removes the quoted reply and its subject prefix from a netmail. */
    fun clearNetmailReply() {
        _state.update {
            val compose = it.netmailCompose
            val body = if (compose.replyQuote.isBlank()) {
                compose.body
            } else {
                compose.body.replaceFirst(compose.replyQuote + "\n\n", "")
            }
            it.copy(
                netmailCompose = compose.copy(
                    subject = "",
                    body = body,
                    replyToMessageId = null,
                    replyToLabel = "",
                    replyQuote = "",
                ),
            )
        }
        revalidateNetmailCompose()
    }

    fun backFromNetmailCompose() {
        _state.update { it.copy(screen = AppScreen.Netmail, netmailCompose = NetmailComposeState()) }
    }

    fun setNetmailRecipient(value: String) =
        _state.update { it.copy(netmailCompose = it.netmailCompose.copy(recipient = value, error = null)) }

    fun setNetmailSubject(value: String) {
        _state.update { it.copy(netmailCompose = it.netmailCompose.copy(subject = value, error = null)) }
        revalidateNetmailCompose()
    }

    fun setNetmailBody(value: String) {
        _state.update { it.copy(netmailCompose = it.netmailCompose.copy(body = value, error = null)) }
        revalidateNetmailCompose()
    }

    fun setNetmailTagline(value: String) {
        _state.update { it.copy(netmailCompose = it.netmailCompose.copy(tagline = value)) }
        revalidateNetmailCompose()
    }

    /** Adds files (already read from the system picker) to a netmail. */
    fun addNetmailAttachments(files: List<Triple<String, String, ByteArray>>) {
        val state = _state.value
        val limits = MessageLimits.fromPolicy(state.activeProfile?.policyJson.orEmpty())
        val existing = state.netmailCompose.attachments
        val result = existing.toMutableList()
        val refs = mutableListOf<String>()
        for ((name, type, bytes) in files) {
            if (limits.maxAttachmentSize > 0 && bytes.size > limits.maxAttachmentSize) {
                _state.update {
                    it.copy(netmailCompose = it.netmailCompose.copy(error = UiError.Failed("Attachment $name is too large (max ${limits.maxAttachmentSize} bytes)")))
                }
                return
            }
            if (limits.maxAttachments > 0 && result.size >= limits.maxAttachments) {
                _state.update {
                    it.copy(netmailCompose = it.netmailCompose.copy(error = UiError.Failed("Too many attachments (max ${limits.maxAttachments})")))
                }
                return
            }
            val index = result.size
            result += AttachmentUi(
                name = name,
                type = type,
                size = bytes.size,
                dataUrl = "data:$type;base64," + android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP),
            )
            refs += if (type.startsWith("image/")) {
                "![$name](attachment:$index)"
            } else {
                "[$name](attachment:$index)"
            }
        }
        val body = withAttachmentRefs(state.netmailCompose.body, refs)
        _state.update {
            it.copy(netmailCompose = it.netmailCompose.copy(attachments = result, body = body, error = null))
        }
        revalidateNetmailCompose()
    }

    fun removeNetmailAttachment(index: Int) {
        _state.update {
            val list = it.netmailCompose.attachments.toMutableList()
            if (index in list.indices) list.removeAt(index)
            it.copy(netmailCompose = it.netmailCompose.copy(attachments = list))
        }
        revalidateNetmailCompose()
    }

    fun sendNetmailCompose() {
        val current = _state.value.netmailCompose
        val opened = networkSession ?: return
        if (current.recipient.isBlank()) {
            _state.update { it.copy(netmailCompose = it.netmailCompose.copy(error = UiError.Failed("Recipient is required"))) }
            return
        }
        if (!MessageMapping.canSend(current.body, current.attachments.size)) {
            _state.update { it.copy(netmailCompose = it.netmailCompose.copy(error = UiError.Failed("Message is empty"))) }
            return
        }
        if (current.limitIssues.any { it.blocking }) {
            _state.update { it.copy(netmailCompose = it.netmailCompose.copy(error = UiError.Failed("Message exceeds a policy limit"))) }
            return
        }
        val rateLimitOverride = _state.value.allowSendingWithoutRateLimit &&
            current.limitIssues.any { it is MessageLimitIssue.RateLimitExceeded }
        viewModelScope.launch {
            _state.update { it.copy(netmailCompose = it.netmailCompose.copy(sending = true, error = null)) }
            try {
                val attachments = if (current.attachments.isEmpty()) {
                    null
                } else {
                    kotlinx.serialization.json.buildJsonArray {
                        current.attachments.forEach { attachment ->
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
                val result = opened.sendNetmail(
                    recipient = current.recipient.trim(),
                    subject = MessageMapping.subjectFor(
                        current.subject,
                        current.attachments.map { it.name },
                    ),
                    body = current.body.trim(),
                    attachments = attachments,
                    replyToMessageId = current.replyToMessageId,
                    tagline = current.tagline,
                    tearline = if (opened.showTearline()) opened.clientTearline() else null,
                    rateLimitOverride = rateLimitOverride,
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
        val opened = networkSession ?: return
        viewModelScope.launch {
            val tagline = runCatching { opened.tagline() }.getOrDefault("")
            val showTearline = runCatching { opened.showTearline() }.getOrDefault(false)
            val showServiceMessages = runCatching {
                opened.showServiceMessages()
            }.getOrDefault(false)
            val allowRateOverride = runCatching {
                opened.allowSendingWithoutRateLimit()
            }.getOrDefault(false)
            _state.update {
                it.copy(
                    tagline = tagline,
                    showTearline = showTearline,
                    showServiceMessages = showServiceMessages,
                    allowSendingWithoutRateLimit = allowRateOverride,
                )
            }
        }
    }

    fun setTagline(value: String) {
        _state.update { it.copy(tagline = value) }
        val opened = networkSession ?: return
        viewModelScope.launch { runCatching { opened.setTagline(value) } }
    }

    fun setShowTearline(value: Boolean) {
        _state.update { it.copy(showTearline = value) }
        val opened = networkSession ?: return
        viewModelScope.launch { runCatching { opened.setShowTearline(value) } }
    }

    /** Shows or hides protocol/service messages in the echo feed (#46). */
    fun setShowServiceMessages(value: Boolean) {
        _state.update { it.copy(showServiceMessages = value) }
        val opened = networkSession ?: return
        viewModelScope.launch { runCatching { opened.setShowServiceMessages(value) } }
    }

    fun setAllowSendingWithoutRateLimit(value: Boolean) {
        _state.update { it.copy(allowSendingWithoutRateLimit = value) }
        revalidateCompose()
        revalidateNetmailCompose()
        val opened = networkSession ?: return
        viewModelScope.launch { runCatching { opened.setAllowSendingWithoutRateLimit(value) } }
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
                // The imported identity may use another password; a saved one
                // would only cause a failed auto-unlock on the next start.
                passwordStore.clear()
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

    private fun mapMessages(
        rows: List<MessageRow>,
        opened: NetworkSession,
        showServiceMessages: Boolean = false,
    ): List<MessageUi> {
        val me = opened.profile.address
        val myName = opened.profile.displayName
        // target message -> sender -> (emoji, timestamp); last reaction wins.
        val reactions = mutableMapOf<String, MutableMap<String, Pair<String, Long>>>()
        val votes = VoteMapping.collect(rows, messageJson)
        val results = VoteMapping.results(rows, messageJson)
        val moderationByMessage = mutableMapOf<String, ModerationUi>()
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
            // Votes and results are folded into the poster card; only the
            // poster itself is a feed entry.
            if (row.type == "vote") {
                val voteType = VoteMapping.innerType(data)
                if (voteType != "poster") continue
            }
            // Attachment-only uploads without a body/echo context are not
            // conversation messages: they are rendered as file bundles (#9).
            if (row.isAttachmentOnly()) continue
            // A moderation message names the punished message in
            // `originalMessage.message_id`; the badge and the appeal action
            // belong to that message, not to the moderation notice itself.
            moderationOf(data, row.senderAddress)?.let { moderation ->
                if (moderation.originalMessageId.isNotBlank()) {
                    moderationByMessage[moderation.originalMessageId] = moderation
                }
            }
            messageRows += row
        }
        val protocolTypes = setOf(
            "election", "excommunicate", "change_policy", "change_rules",
            "remove_echo", "refill", "merge", "conflict", "recover", "appeal",
            "remote_subscribe",
        )
        return messageRows
            .filter { showServiceMessages || it.type !in protocolTypes }
            .map { row ->
            val bySender = reactions[row.messageId]
            val counts = mutableMapOf<String, Int>()
            var mine: String? = null
            bySender?.forEach { (sender, pair) ->
                counts[pair.first] = (counts[pair.first] ?: 0) + 1
                if (sender == me) mine = pair.first
            }
            val ui = row.toUi(me, myName).copy(
                reactions = counts,
                myReaction = mine,
                moderation = moderationByMessage[row.messageId],
            )
            // Votes and results refer to the poster by its text.
            val posterKey = VoteMapping.posterKey(VoteMapping.data(row, messageJson))
            if (row.type == "vote" && posterKey.isNotEmpty()) {
                ui.copy(
                    vote = VoteMapping.card(
                        poster = row,
                        votes = votes[posterKey],
                        result = results[posterKey],
                        me = me,
                        json = messageJson,
                    ),
                )
            } else {
                ui
            }
        }
    }

    private fun MessageRow.toProtocolUi(
        votes: Map<String, Map<String, Pair<String, Long>>>? = null,
        me: String = "",
    ): ProtocolUi? {
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
        val posterVotes = votes?.get(messageId)
        val counts = mutableMapOf<String, Int>()
        posterVotes?.values?.forEach { (variant, _) ->
            counts[variant] = (counts[variant] ?: 0) + 1
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
            canRespondRefill = canRespondToRefill(type, innerType, senderAddress, me),
            refillBefore = if (type == "refill" && innerType == "request") {
                ((data["refill"] as? JsonObject)?.get("before") as? JsonPrimitive)?.contentOrNull
            } else {
                null
            },
            electionCandidate = if (type == "election" && innerType == "poster") {
                (data["from"] as? JsonPrimitive)?.contentOrNull ?: senderAddress
            } else {
                null
            },
            variantCounts = counts,
            myVote = posterVotes?.get(me)?.first,
            totalVotes = posterVotes?.size ?: 0,
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
        // Service messages carry their human-readable text in `message` (#46).
        val service = serviceMessageOf(type, dataJson)
        val subject = (data?.get("subject") as? JsonPrimitive)?.contentOrNull.orEmpty()
            .ifBlank { service?.title.orEmpty() }
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
        val tagline = MessageMapping.taglineOf(data)
        val tearline = MessageMapping.tearlineOf(data)
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
            echoName = echoName,
            tagline = tagline,
            tearline = tearline,
            protocolType = service?.let { type },
            refillBefore = service?.refillBefore,
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
        /** Starter rules for a conference created with rules. */
        private const val DEFAULT_ECHO_RULES = "{\n  \"text\": \"\",\n  \"parameters\": {}\n}"

        fun factory(
            manager: NetworkManager,
            session: Session,
            passwordStore: PasswordStore = NoopPasswordStore,
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    AppViewModel(manager, session, passwordStore) as T
            }
    }
}
