package lan.fmnode.android.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import lan.fmnode.android.crypto.CryptoService
import lan.fmnode.android.crypto.Encoding

class InviteUrlTest {
    @Test
    fun `parses a prefixed invite with pubkey, voucher and address`() {
        val seed = CryptoService.generateSeed()
        val pubkey = CryptoService.publicKeyFromSeed(seed)
        val invite = InviteParser.parse(
            "https://fm.example.com/pre/app?pubkey=${Encoding.base64UrlEncode(pubkey)}&voucher=abc&address=:0:1:0"
        )!!
        assertEquals("https://fm.example.com/pre/app", invite.appUrl)
        assertEquals("https://fm.example.com/pre/api", invite.apiUrl)
        assertTrue(invite.nodePublicKey!!.contentEquals(pubkey))
        assertEquals("abc", invite.voucher)
        assertEquals(":0:1:0", invite.address)
    }

    @Test
    fun `accepts an api url and a bare node url`() {
        val api = InviteParser.parse("https://fm.example.com/pre/api")!!
        assertEquals("https://fm.example.com/pre/api", api.apiUrl)

        val root = InviteParser.parse("https://fm.example.com")!!
        assertEquals("https://fm.example.com/api", root.apiUrl)
    }

    @Test
    fun `app url to api url conversion matches fm-app`() {
        val cases = mapOf(
            "https://h/app" to "https://h/api",
            "https://h/pre/app" to "https://h/pre/api",
            "https://h/pre/app/" to "https://h/pre/api",
            "https://h/pre/api" to "https://h/pre/api",
            "https://h" to "https://h/api",
            "https://h/pre/" to "https://h/pre/api",
        )
        for ((input, expected) in cases) {
            assertEquals(input, expected, InviteParser.appUrlToApiUrl(input))
        }
    }

    @Test
    fun `rejects invalid invites`() {
        for (raw in listOf("", "/", "not a URL", "fm.example.com", "://bad")) {
            assertNull("expected null for \"$raw\"", InviteParser.parse(raw))
        }
    }
}
