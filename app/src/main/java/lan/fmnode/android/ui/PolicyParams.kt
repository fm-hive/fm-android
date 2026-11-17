package lan.fmnode.android.ui

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Kind of input a policy parameter needs in the editor. */
enum class PolicyParamKind { Text, Number, Duration, Select }

/**
 * The duration syntax the node accepts in policy parameters (#29).
 *
 * Mirrors `parsePolicyDuration` in fm-go: a bare number means days (fractions
 * allowed), otherwise one or more `<number><unit>` groups with `d`, `h`, `m`
 * or `s` (`12h`, `1d12h`, `1.5h30m`). Empty is allowed by the editor and means
 * "do not send this parameter"; the node itself rejects empty values.
 */
object PolicyDuration {
    fun isValid(raw: String): Boolean {
        val value = raw.trim()
        if (value.isEmpty()) return true
        // A bare number is days, exactly like the node's parser.
        if (!value.any { it in "dhms" }) {
            val days = value.toDoubleOrNull() ?: return false
            return days >= 0 && days.isFinite()
        }
        var pos = 0
        while (pos < value.length) {
            val start = pos
            while (pos < value.length && (value[pos].isDigit() || value[pos] == '.')) pos++
            if (start == pos || pos >= value.length) return false
            val number = value.substring(start, pos).toDoubleOrNull() ?: return false
            if (number < 0 || !number.isFinite()) return false
            val unit = value[pos]
            pos++
            if (unit !in "dhms") return false
        }
        return true
    }
}

/** One editable policy parameter. */
data class PolicyParamDef(
    val key: String,
    val kind: PolicyParamKind,
    val options: List<String> = emptyList(),
    val maxLength: Int? = null,
)

/**
 * Policy parameters of the network, edited by an admin through `change_policy`
 * (the same list fm-app shows). Parameters the node adds later still get a
 * plain text field, so a new setting never becomes invisible in the UI.
 */
internal object PolicyParams {
    /** Nested maps edited separately (rate limits) or not edited at all. */
    const val RATE_LIMIT = "rate_limit"
    const val SPECIAL_ECHOGROUPS = "special_echogroups"

    val RATE_LIMIT_KEYS = listOf("vote", "echomail", "reaction", "netmail", "poster")

    val DEFS: List<PolicyParamDef> = listOf(
        PolicyParamDef("net_name", PolicyParamKind.Text, maxLength = 64),
        PolicyParamDef("voting_period", PolicyParamKind.Duration),
        PolicyParamDef("candidate_collection_period", PolicyParamKind.Duration),
        PolicyParamDef("min_candidates", PolicyParamKind.Number),
        PolicyParamDef("min_moderators", PolicyParamKind.Number),
        PolicyParamDef("max_moderators", PolicyParamKind.Number),
        PolicyParamDef("read_only_days", PolicyParamKind.Number),
        PolicyParamDef("read_only_echo_limit", PolicyParamKind.Number),
        PolicyParamDef("election_result_days", PolicyParamKind.Number),
        PolicyParamDef("refill_days", PolicyParamKind.Number),
        PolicyParamDef("time_since_last_vote", PolicyParamKind.Number),
        PolicyParamDef("min_percent_for_network_decisions", PolicyParamKind.Number),
        PolicyParamDef("change_policy_vote_period", PolicyParamKind.Duration),
        PolicyParamDef("merge_phase0_vote_period", PolicyParamKind.Duration),
        PolicyParamDef("merge_phase1_vote_period", PolicyParamKind.Duration),
        PolicyParamDef("merge_phase2_vote_period", PolicyParamKind.Duration),
        PolicyParamDef("merge_connect_timeout", PolicyParamKind.Duration),
        PolicyParamDef("appeal_vote_period", PolicyParamKind.Duration),
        PolicyParamDef("fast_network_votes", PolicyParamKind.Select, options = listOf("true", "false")),
        PolicyParamDef("excommunicate_cooldown_days", PolicyParamKind.Number),
        PolicyParamDef("warnings_per_punishment", PolicyParamKind.Number),
        PolicyParamDef("punishments_per_read_only", PolicyParamKind.Number),
        PolicyParamDef("moderator_punishments_per_demotion", PolicyParamKind.Number),
        PolicyParamDef("network_news_history", PolicyParamKind.Number),
        PolicyParamDef("max_announcements_per_address", PolicyParamKind.Number),
        PolicyParamDef("min_votes_for_success", PolicyParamKind.Number),
        PolicyParamDef("max_message_size", PolicyParamKind.Number),
        PolicyParamDef("max_attachments_per_message", PolicyParamKind.Number),
        PolicyParamDef("max_attachment_size", PolicyParamKind.Number),
        PolicyParamDef("max_create_echo_requests", PolicyParamKind.Number),
        PolicyParamDef("message_id_depth_days", PolicyParamKind.Number),
        PolicyParamDef("create_echo_with_rules", PolicyParamKind.Select, options = listOf("council", "node", "any")),
        PolicyParamDef("create_echo_with_rules_council_votes", PolicyParamKind.Text),
        PolicyParamDef("create_echo_with_rules_council_vote_period", PolicyParamKind.Duration),
        PolicyParamDef("voucher_enable", PolicyParamKind.Select, options = listOf("true", "false")),
        PolicyParamDef("voucher_autologin", PolicyParamKind.Select, options = listOf("true", "false")),
        PolicyParamDef("encrypted_policy_flow", PolicyParamKind.Select, options = listOf("true", "false")),
    )

    /** Known definitions plus a plain field for every unknown parameter. */
    fun editable(params: JsonObject?): List<PolicyParamDef> {
        val known = DEFS.map { it.key }.toSet()
        val extra = params?.keys
            ?.filter { it !in known && it != RATE_LIMIT && it != SPECIAL_ECHOGROUPS }
            ?.sorted()
            ?.map { PolicyParamDef(it, PolicyParamKind.Text) }
            ?: emptyList()
        return DEFS + extra
    }

    /** String form of a parameter value (`""` when absent). */
    fun value(params: JsonObject?, key: String): String {
        val element = params?.get(key) as? JsonPrimitive ?: return ""
        return element.contentOrNull.orEmpty()
    }
}
