package lan.fmnode.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A refill response needs the requester and the cutoff date, both taken from the
 * request payload (`refill.from`, `refill.before`). Without them the responder
 * cannot know what to send or where.
 */
class RefillRequestTest {
    @Test
    fun `a request yields the requester and the cutoff`() {
        val data = """
            {"proto":"refill","type":"request","from":":0:3","to":"all",
             "echo":"Newcomers",
             "refill":{"from":":0:3","echo":"Newcomers","before":"2026-11-18",
                       "messageId":"refill_Newcomers_:0:3_1_abcd"}}
        """.trimIndent()

        assertEquals(":0:3" to "2026-11-18", refillRequestOf(data))
    }

    @Test
    fun `an ISO cutoff is passed through unchanged`() {
        val data = """{"proto":"refill","refill":{"from":":0:3","before":"2026-11-18T10:00:00Z"}}"""

        assertEquals(":0:3" to "2026-11-18T10:00:00Z", refillRequestOf(data))
    }

    @Test
    fun `other messages are not refill requests`() {
        assertNull(refillRequestOf("""{"proto":"refill","refill":{"before":"2026-11-18"}}"""))
        assertNull(refillRequestOf("""{"proto":"vote","refill":{"from":":0:3","before":"2026-11-18"}}"""))
        assertNull(refillRequestOf("""{"body":"hello"}"""))
        assertNull(refillRequestOf("not json"))
    }
}
