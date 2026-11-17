package lan.fmnode.android.ui

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import lan.fmnode.android.storage.MessageRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Requirement #21: votes are shown in the feed as one ballot — the poster, the
 * counts of every variant, the user's own choice and the result.
 *
 * fm-node identifies a poster by its text: a vote's `poster_message` is the
 * poster's `message` field, not a message id.
 */
class VoteMappingTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun row(
        id: String,
        sender: String,
        timestamp: Long,
        data: JsonObject,
        type: String = "vote",
    ) = MessageRow(
        messageId = id,
        echoName = "Newcomers",
        type = type,
        senderAddress = sender,
        recipientAddress = null,
        timestamp = timestamp,
        dataJson = data.toString(),
        jws = null,
        jwe = null,
        versionsJson = null,
        senderName = null,
        folderSlug = null,
    )

    private fun poster(question: String = "Tea or coffee?", variants: List<String> = listOf("tea", "coffee")) = row(
        id = "p-$question",
        sender = ":0:1",
        timestamp = 100,
        data = JsonObject(
            mapOf(
                "proto" to JsonPrimitive("vote"),
                "type" to JsonPrimitive("poster"),
                "message" to JsonPrimitive(question),
                "variants" to JsonArray(variants.map { JsonPrimitive(it) }),
            ),
        ),
    )

    private fun vote(sender: String, variant: String, timestamp: Long, question: String = "Tea or coffee?") = row(
        id = "v-$sender-$timestamp",
        sender = sender,
        timestamp = timestamp,
        data = JsonObject(
            mapOf(
                "proto" to JsonPrimitive("vote"),
                "type" to JsonPrimitive("vote"),
                "poster_message" to JsonPrimitive(question),
                "vote" to JsonPrimitive(variant),
            ),
        ),
    )

    private fun result(question: String = "Tea or coffee?", outcome: String = "success", winner: String? = "tea") = row(
        id = "r-$question",
        sender = ":0:0",
        timestamp = 900,
        data = JsonObject(
            buildMap {
                put("proto", JsonPrimitive("vote"))
                put("type", JsonPrimitive("result"))
                put("poster_message", JsonPrimitive(question))
                put("result", JsonPrimitive(outcome))
                if (winner != null) put("variant", JsonPrimitive(winner))
                put("message", JsonPrimitive("Variant 'tea' won"))
            },
        ),
    )

    @Test
    fun `poster is identified by its text`() {
        val data = VoteMapping.data(poster(), json)

        assertEquals("poster", VoteMapping.innerType(data))
        assertEquals("Tea or coffee?", VoteMapping.posterKey(data))
    }

    @Test
    fun `votes are counted per variant`() {
        val rows = listOf(
            poster(),
            vote(":0:2", "tea", 200),
            vote(":0:3", "tea", 210),
            vote(":0:4", "coffee", 220),
        )

        val card = VoteMapping.card(poster(), VoteMapping.collect(rows, json)["Tea or coffee?"], null, ":0:2", json)

        assertEquals(3, card.totalVotes)
        assertEquals(2, card.counts["tea"])
        assertEquals(1, card.counts["coffee"])
        assertEquals("tea", card.myVote)
        assertFalse(card.finished)
    }

    @Test
    fun `last vote of one sender wins`() {
        val rows = listOf(
            poster(),
            vote(":0:2", "tea", 200),
            vote(":0:2", "coffee", 300),
        )

        val card = VoteMapping.card(poster(), VoteMapping.collect(rows, json)["Tea or coffee?"], null, ":0:2", json)

        assertEquals(1, card.totalVotes)
        assertEquals(0, card.counts["tea"] ?: 0)
        assertEquals(1, card.counts["coffee"])
        assertEquals("coffee", card.myVote)
    }

    @Test
    fun `empty variant retracts a vote`() {
        val rows = listOf(
            poster(),
            vote(":0:2", "tea", 200),
            vote(":0:2", "", 300),
        )

        val card = VoteMapping.card(poster(), VoteMapping.collect(rows, json)["Tea or coffee?"], null, ":0:2", json)

        assertEquals(0, card.totalVotes)
        assertNull(card.myVote)
    }

    @Test
    fun `result closes the ballot with the winner`() {
        val rows = listOf(poster(), vote(":0:2", "tea", 200), result())

        val card = VoteMapping.card(
            poster = poster(),
            votes = VoteMapping.collect(rows, json)["Tea or coffee?"],
            result = VoteMapping.results(rows, json)["Tea or coffee?"],
            me = ":0:3",
            json = json,
        )

        assertTrue(card.finished)
        assertTrue(card.success)
        assertEquals("tea", card.winner)
        assertEquals("Variant 'tea' won", card.resultText)
        assertNull(card.myVote)
    }

    @Test
    fun `failed result is not a success`() {
        val rows = listOf(poster(), result(outcome = "fail", winner = null))

        val card = VoteMapping.card(poster(), emptyMap(), VoteMapping.results(rows, json)["Tea or coffee?"], ":0:1", json)

        assertTrue(card.finished)
        assertFalse(card.success)
        assertNull(card.winner)
    }

    @Test
    fun `votes of another poster are not mixed in`() {
        val rows = listOf(
            poster(),
            poster(question = "Pizza or pasta?"),
            vote(":0:2", "tea", 200, question = "Pizza or pasta?"),
        )

        val votes = VoteMapping.collect(rows, json)

        assertNull(votes["Tea or coffee?"])
        assertEquals(1, votes["Pizza or pasta?"]?.size)
    }
}
