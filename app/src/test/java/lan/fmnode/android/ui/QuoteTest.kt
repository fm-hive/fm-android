package lan.fmnode.android.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class QuoteTest {
    @Test
    fun `address short id uses the last two digits`() {
        assertEquals("01", Quote.addressToShortId(":0:1"))
        assertEquals("12", Quote.addressToShortId(":0:1:12"))
    }

    @Test
    fun `initials use the first two words`() {
        assertEquals("ПР", Quote.initialsFromName("Павел Романов"))
        assertEquals("А", Quote.initialsFromName("Александр"))
        assertEquals("", Quote.initialsFromName("   "))
    }

    @Test
    fun `fido quote prefixes every line`() {
        assertEquals(
            "ПР> first\nПР> second",
            Quote.formatFidoQuote("Павел Романов", "first\nsecond"),
        )
        assertEquals("01> text", Quote.formatFidoQuote(":0:1", "text"))
    }

    @Test
    fun `reply subject is not prefixed twice`() {
        assertEquals("Re: Hello", Quote.replySubject("Hello"))
        assertEquals("Re: Hello", Quote.replySubject("Re: Hello"))
        assertEquals("Re: Hello", Quote.replySubject("RE: Hello"))
        assertEquals("", Quote.replySubject("   "))
    }
}
