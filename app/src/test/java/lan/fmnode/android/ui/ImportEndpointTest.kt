package lan.fmnode.android.ui

import lan.fmnode.android.crypto.CryptoService
import lan.fmnode.android.crypto.Encoding
import lan.fmnode.android.network.InviteParser
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A keys export carries no node endpoint, so the import takes it from the invite
 * field of the add-network screen: both the app and the API URLs plus the node
 * pinning key come from that one string.
 */
class ImportEndpointTest {
    @Test
    fun `an invite gives the app and api urls of the imported network`() {
        val nodeKey = CryptoService.publicKeyFromSeed(ByteArray(32) { (it + 17).toByte() })
        val invite = InviteParser.parse(
            "https://2.28.13.140/msoijwer43asdf/?pubkey=${Encoding.base64UrlEncode(nodeKey)}",
        )!!

        assertEquals("https://2.28.13.140/msoijwer43asdf", invite.appUrl)
        assertEquals("https://2.28.13.140/msoijwer43asdf/api", invite.apiUrl)
        assertArrayEquals(nodeKey, invite.nodePublicKey)
        assertEquals(
            Encoding.base64Encode(nodeKey),
            invite.nodePublicKey?.let { Encoding.base64Encode(it) },
        )
    }

    @Test
    fun `a bare node url still yields an api endpoint`() {
        val invite = InviteParser.parse("http://2.28.13.140/msoijwer43asdf/api")!!
        assertEquals("http://2.28.13.140/msoijwer43asdf/api", invite.apiUrl)
        assertNull(invite.nodePublicKey)
    }

    @Test
    fun `unusable text is rejected before the password is asked`() {
        for (raw in listOf("", "   ", "2.28.13.140", "not a url")) {
            assertNull("expected null for \"$raw\"", InviteParser.parse(raw))
        }
    }
}
