package lan.fmnode.android.ui

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Requirement #21: an admin edits the policy through a form, not a raw JSON
 * blob; a parameter the node adds later must still be editable.
 */
class PolicyParamsTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun params(raw: String) = json.parseToJsonElement(raw).jsonObject

    @Test
    fun `known parameters are offered with their kinds`() {
        val fields = PolicyParams.editable(params("""{"voting_period":"30m"}"""))

        val voting = fields.first { it.key == "voting_period" }
        assertEquals(PolicyParamKind.Duration, voting.kind)
        assertEquals(PolicyParamKind.Number, fields.first { it.key == "min_candidates" }.kind)
        assertEquals(
            listOf("council", "node", "any"),
            fields.first { it.key == "create_echo_with_rules" }.options,
        )
    }

    @Test
    fun `unknown parameters get a plain field`() {
        val fields = PolicyParams.editable(params("""{"new_node_flag":"on"}"""))

        val unknown = fields.first { it.key == "new_node_flag" }
        assertEquals(PolicyParamKind.Text, unknown.kind)
    }

    /**
     * Parameters a real node stores (checked against fmnode.db) must be edited
     * with the right widget, not as a fallback text field.
     */
    @Test
    fun `parameters of a running node are all known`() {
        val live = listOf(
            "appeal_vote_period", "candidate_collection_period", "election_result_days",
            "encrypted_policy_flow", "fast_network_votes", "max_announcements_per_address",
            "max_attachment_size", "max_attachments_per_message", "max_message_size",
            "max_moderators", "merge_connect_timeout", "merge_phase0_vote_period",
            "merge_phase1_vote_period", "merge_phase2_vote_period", "message_id_depth_days",
            "min_candidates", "min_moderators", "min_percent_for_network_decisions",
            "min_votes_for_success", "moderator_punishments_per_demotion", "network_news_history",
            "read_only_days", "read_only_echo_limit", "refill_days", "time_since_last_vote",
            "voting_period", "voucher_autologin", "voucher_enable",
        )
        val known = PolicyParams.DEFS.map { it.key }.toSet()

        assertEquals(emptyList<String>(), live.filter { it !in known })
    }

    @Test
    fun `punishment and history settings are numeric`() {
        val fields = PolicyParams.editable(JsonObject(emptyMap()))

        listOf(
            "moderator_punishments_per_demotion",
            "network_news_history",
            "max_announcements_per_address",
            "min_votes_for_success",
        ).forEach { key ->
            assertEquals(key, PolicyParamKind.Number, fields.first { it.key == key }.kind)
        }
    }

    @Test
    fun `nested maps are not turned into flat fields`() {
        val fields = PolicyParams.editable(
            params("""{"rate_limit":{"vote":"20"},"special_echogroups":{"admin":"Admin council"}}"""),
        )

        assertTrue(fields.none { it.key == "rate_limit" })
        assertTrue(fields.none { it.key == "special_echogroups" })
    }

    @Test
    fun `value reads the string form of a parameter`() {
        val params = params("""{"voting_period":"30m","fast_network_votes":"true"}""")

        assertEquals("30m", PolicyParams.value(params, "voting_period"))
        assertEquals("", PolicyParams.value(params, "absent"))
    }

    @Test
    fun `changes of the editor mention only edited fields`() {
        val edit = PolicyEditUi(
            text = "new text",
            originalText = "old text",
            fields = listOf(
                PolicyParamFieldUi("voting_period", PolicyParamKind.Duration, "30m", "30m"),
                PolicyParamFieldUi("min_candidates", PolicyParamKind.Number, "3", "2"),
            ),
            rateLimits = mapOf("vote" to "20"),
            originalRateLimits = mapOf("vote" to "50"),
            preserved = JsonObject(emptyMap()),
        )

        val changes = edit.changes

        assertTrue(changes.any { it == "text" })
        assertTrue(changes.any { it.startsWith("min_candidates") })
        assertTrue(changes.any { it.startsWith("rate_limit.vote") })
        assertTrue(changes.none { it.startsWith("voting_period") })
    }

    @Test
    fun `unchanged policy has no diff`() {
        val edit = PolicyEditUi(
            text = "same",
            originalText = "same",
            fields = listOf(PolicyParamFieldUi("voting_period", PolicyParamKind.Duration, "1m", "1m")),
            rateLimits = mapOf("vote" to ""),
            originalRateLimits = mapOf("vote" to ""),
            preserved = JsonObject(emptyMap()),
        )

        assertTrue(edit.changes.isEmpty())
    }

    /** The editor refuses malformed node durations instead of letting the node reject them (#29). */
    @Test
    fun `duration values are validated with the node syntax`() {
        val valid = listOf("3", "0.5", "0.5d", "12h", "30m", "45s", "1d12h", "1.5h30m", "0")
        valid.forEach { assertTrue("$it must be valid", PolicyDuration.isValid(it)) }

        val invalid = listOf("foo", "-1h", "1x", "12h-", "1..2h", "1h 2m", ".", "1h30")
        invalid.forEach { assertFalse("$it must be invalid", PolicyDuration.isValid(it)) }

        // Blank means "do not send this parameter" and is allowed by the editor.
        assertTrue(PolicyDuration.isValid(""))
        assertTrue(PolicyDuration.isValid("   "))
    }

    @Test
    fun `an invalid duration blocks the policy submission`() {
        val edit = PolicyEditUi(
            text = "text",
            originalText = "text",
            fields = listOf(
                PolicyParamFieldUi("voting_period", PolicyParamKind.Duration, "1x", "1h"),
                PolicyParamFieldUi("min_candidates", PolicyParamKind.Number, "2", "2"),
            ),
            rateLimits = emptyMap(),
            originalRateLimits = emptyMap(),
            preserved = JsonObject(emptyMap()),
        )

        assertTrue(edit.hasInvalidFields)
        edit.fields.first().copy(value = "2h").let { fixed ->
            assertFalse(fixed.invalid)
        }
    }
}
