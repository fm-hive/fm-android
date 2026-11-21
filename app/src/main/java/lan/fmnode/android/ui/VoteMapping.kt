package lan.fmnode.android.ui

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import lan.fmnode.android.storage.MessageRow

/**
 * Vote protocol of fm-node as the feed shows it: a poster message is one entry,
 * the votes on it are folded into counts and a result message closes it.
 *
 * A vote refers to its poster by the poster's **text**, not by a message id
 * (`poster_message` in fm-node's vote payload is `data.message`), so the key of
 * every map here is the question text.
 */
internal object VoteMapping {
    /** Parsed payload of a stored message. */
    fun data(row: MessageRow, json: Json): JsonObject? =
        runCatching { json.parseToJsonElement(row.dataJson).jsonObject }.getOrNull()

    /** Inner protocol type of the payload (`poster`, `vote`, `result`). */
    fun innerType(data: JsonObject?): String =
        (data?.get("type") as? JsonPrimitive)?.contentOrNull.orEmpty()

    /** Text of a vote poster: the key votes refer to. */
    fun posterKey(data: JsonObject?): String =
        (data?.get("message") as? JsonPrimitive)?.contentOrNull.orEmpty()

    /** question -> sender -> (variant, timestamp); the last vote of a sender wins. */
    fun collect(rows: List<MessageRow>, json: Json): Map<String, Map<String, Pair<String, Long>>> {
        val votes = mutableMapOf<String, MutableMap<String, Pair<String, Long>>>()
        for (row in rows) {
            if (row.type != "vote") continue
            val data = data(row, json) ?: continue
            if (innerType(data) != "vote") continue
            val poster = (data["poster_message"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            if (poster.isEmpty()) continue
            val bySender = votes.getOrPut(poster) { mutableMapOf() }
            val variant = (data["vote"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            val previous = bySender[row.senderAddress]
            if (previous == null || row.timestamp >= previous.second) {
                // An empty variant is a retraction.
                if (variant.isEmpty()) bySender.remove(row.senderAddress)
                else bySender[row.senderAddress] = variant to row.timestamp
            }
        }
        return votes
    }

    /** poster text -> result message. */
    fun results(rows: List<MessageRow>, json: Json): Map<String, MessageRow> {
        val results = mutableMapOf<String, MessageRow>()
        for (row in rows) {
            if (row.type != "vote") continue
            val data = data(row, json) ?: continue
            if (innerType(data) != "result") continue
            val poster = (data["poster_message"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            if (poster.isNotEmpty()) results[poster] = row
        }
        return results
    }

    /** Folds a poster, its votes and its result into one ballot. */
    fun card(
        poster: MessageRow,
        votes: Map<String, Pair<String, Long>>?,
        result: MessageRow?,
        me: String,
        json: Json,
    ): VoteCardUi {
        val data = data(poster, json)
        val question = posterKey(data)
        val variants = (data?.get("variants") as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            ?: emptyList()
        val counts = mutableMapOf<String, Int>()
        votes?.values?.forEach { (variant, _) ->
            counts[variant] = (counts[variant] ?: 0) + 1
        }
        val resultData = result?.let { data(it, json) }
        val outcome = (resultData?.get("result") as? JsonPrimitive)?.contentOrNull.orEmpty()
        return VoteCardUi(
            posterMessage = question,
            echoName = poster.echoName,
            question = question,
            variants = variants,
            counts = counts,
            myVote = votes?.get(me)?.first,
            totalVotes = votes?.size ?: 0,
            finished = result != null,
            winner = (resultData?.get("variant") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() },
            success = outcome != "fail",
            resultText = (resultData?.get("message") as? JsonPrimitive)?.contentOrNull.orEmpty(),
        )
    }
}
