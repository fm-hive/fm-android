package lan.fmnode.android.ui

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `remove_echo` proposal seen on the stand: the node only starts the
 * `remove`/`keep` vote when the proposal arrives **without** `variants`
 * (`len(Variants) == 0` in fm-go). With variants it took the vote-chunk branch
 * and did nothing, so this payload shape is a contract, not a detail.
 */
class RemoveEchoPayloadTest {
    private val payload = Json.parseToJsonElement(
        removeEchoPayload(
            from = ":0:2",
            echoName = "dup",
            reason = "e2e remove test",
            council = "Moderators council",
            date = "2026-11-18T16:01:42Z",
        ),
    ).jsonObject

    @Test
    fun `proposal carries no variants`() {
        assertNull(payload["variants"])
    }

    @Test
    fun `proposal is not typed as a vote or a chunk`() {
        assertNull(payload["type"])
        assertNull(payload["chunk_index"])
        assertNull(payload["total_chunks"])
    }

    @Test
    fun `proposal names the target echo and the proposer`() {
        assertEquals("remove_echo", payload["proto"]!!.jsonPrimitive.content)
        assertEquals("dup", payload["echo_name"]!!.jsonPrimitive.content)
        assertEquals(":0:2", payload["node1"]!!.jsonPrimitive.content)
        assertEquals("Moderators council", payload["echo"]!!.jsonPrimitive.content)
        assertEquals("Remove echo: dup", payload["subject"]!!.jsonPrimitive.content)
    }

    @Test
    fun `an empty reason still gets a readable message`() {
        val noReason = Json.parseToJsonElement(
            removeEchoPayload(":0:2", "dup", "", "Moderators council", "now"),
        ).jsonObject

        assertEquals("Remove echo: dup", noReason["message"]!!.jsonPrimitive.content)
        assertFalse(noReason.toString().contains("variants"))
    }
}
