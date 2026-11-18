package lan.fmnode.android.ui

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Warning/punishment payloads become appealable moderation info (#30, appeal). */
class ModerationMappingTest {
    private fun data(raw: String) = Json.parseToJsonElement(raw).jsonObject

    @Test
    fun `a punishment carries its severity and target`() {
        val moderation = moderationOf(
            data(
                """{"severity":"punishment","to":":0:7","originalMessage":{"message_id":"m1"}}""",
            ),
        )
        assertEquals("punishment", moderation?.severity)
        assertEquals(":0:7", moderation?.targetAddress)
        assertEquals("m1", moderation?.originalMessageId)
    }

    /**
     * The payload only names the victim (`to`); the moderator is whoever sent the
     * notice. The appeal dialog used to print the punished message's sender, which
     * labelled the victim as the moderator.
     */
    @Test
    fun `the moderator is the author of the notice`() {
        val moderation = moderationOf(
            data("""{"severity":"punishment","to":":0:7","originalMessage":{"message_id":"m1"}}"""),
            senderAddress = ":0:2",
        )

        assertEquals(":0:2", moderation?.moderatorAddress)
        assertEquals(":0:7", moderation?.targetAddress)
    }

    @Test
    fun `a legacy string originalMessage is parsed`() {
        val moderation = moderationOf(
            data(
                """{"severity":"warning","to":":0:7","originalMessage":"{\"message_id\":\"old\"}"}""",
            ),
        )
        assertEquals("old", moderation?.originalMessageId)
    }

    /**
     * The badge and the appeal button belong to the punished message: the feed
     * keys the mapping by `originalMessageId`, not by the notice's own id.
     */
    @Test
    fun `the mapping key is the punished message id`() {
        val moderation = moderationOf(
            data("""{"severity":"punishment","to":":0:2","originalMessage":{"message_id":"ACfkys"}}"""),
        )
        val byMessage = mutableMapOf<String, ModerationUi>()
        moderation?.let { if (it.originalMessageId.isNotBlank()) byMessage[it.originalMessageId] = it }

        assertEquals(moderation, byMessage["ACfkys"])
        assertNull(byMessage["the-notice-itself"])
    }

    @Test
    fun `network news chronicles and ordinary messages are not appeals`() {
        assertNull(moderationOf(data("""{"severity":"warning","event":"merge","to":":0:7"}""")))
        assertNull(moderationOf(data("""{"severity":"none","to":":0:7"}""")))
        assertNull(moderationOf(data("""{"body":"hello"}""")))
    }
}
