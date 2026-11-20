package lan.fmnode.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Service/protocol messages in the echo feed (#46). */
class ServiceMessageTest {
    private val request = """
        {"proto":"refill","type":"request","from":":0:3","to":"all",
         "message":"Refill request for Newcomers","echo":"Newcomers",
         "refill":{"from":":0:3","echo":"Newcomers","before":"2026-11-18",
                   "messageId":"refill_Newcomers_:0:3_1_abcd"}}
    """.trimIndent()

    @Test
    fun `a refill request has a title and a cutoff date`() {
        val info = serviceMessageOf("refill", request)
        assertEquals("Refill request for Newcomers", info?.title)
        assertEquals("2026-11-18", info?.refillBefore)
    }

    @Test
    fun `a refill response has no cutoff date`() {
        val response = """
            {"proto":"refill","type":"response","echo":"Newcomers",
             "refill":{"echo":"Newcomers","to":":0:3","messages":[]}}
        """.trimIndent()
        val info = serviceMessageOf("refill", response)
        assertEquals("refill", info?.title)
        assertNull(info?.refillBefore)
    }

    @Test
    fun `ordinary echomail is not a service message`() {
        assertNull(serviceMessageOf("echomail", """{"subject":"Hi","body":"text"}"""))
        assertNull(serviceMessageOf("netmail", """{"subject":"Hi"}"""))
    }

    @Test
    fun `protocol types include refill but not echomail`() {
        assertTrue(isProtocolMessageType("refill"))
        assertTrue(isProtocolMessageType("election"))
        assertFalse(isProtocolMessageType("echomail"))
    }

    @Test
    fun `only another user can answer a refill request`() {
        assertFalse(canRespondToRefill("refill", "request", ":0:3", ":0:3"))
        assertTrue(canRespondToRefill("refill", "request", ":0:3", ":0:1"))
        assertFalse(canRespondToRefill("refill", "response", ":0:3", ":0:1"))
        assertFalse(canRespondToRefill("vote", "request", ":0:3", ":0:1"))
    }
}
