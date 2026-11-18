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

    @Test
    fun `a legacy string originalMessage is parsed`() {
        val moderation = moderationOf(
            data(
                """{"severity":"warning","to":":0:7","originalMessage":"{\"message_id\":\"old\"}"}""",
            ),
        )
        assertEquals("old", moderation?.originalMessageId)
    }

    @Test
    fun `network news chronicles and ordinary messages are not appeals`() {
        assertNull(moderationOf(data("""{"severity":"warning","event":"merge","to":":0:7"}""")))
        assertNull(moderationOf(data("""{"severity":"none","to":":0:7"}""")))
        assertNull(moderationOf(data("""{"body":"hello"}""")))
    }
}
