package lan.fmnode.android.ui

import lan.fmnode.android.network.Invite
import lan.fmnode.android.network.InviteParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Resolving the endpoint of an import without a ViewModel: the field wins, a
 * full export provides its own `appUrl`, and only a keys export needs input.
 *
 * The bug this pins down: the password dialog asked for the password and then
 * complained "Enter the network URL" even though the export carried `appUrl`.
 */
class ImportEndpointResolutionTest {
    /** Same rule as [AppViewModel.parsedInviteFor]. */
    private fun resolve(fieldValue: String, exportUrl: String?): Invite? =
        InviteParser.parse(fieldValue)
            ?: exportUrl?.let { InviteParser.parse(it) }

    @Test
    fun `a full export supplies the endpoint when the field is empty`() {
        val invite = resolve("", "https://fm.plastpenza.ru/sdfglj54t05jslkfls/app")

        assertNotNull(invite)
        assertEquals("https://fm.plastpenza.ru/sdfglj54t05jslkfls/app", invite!!.appUrl)
        assertEquals("https://fm.plastpenza.ru/sdfglj54t05jslkfls/api", invite.apiUrl)
    }

    @Test
    fun `the field overrides the endpoint of the export`() {
        val invite = resolve("https://other.example/app", "https://fm.plastpenza.ru/sdfglj54t05jslkfls/app")

        assertEquals("https://other.example/app", invite!!.appUrl)
        assertEquals("https://other.example/api", invite.apiUrl)
    }

    @Test
    fun `a keys export with no endpoint still asks for the url`() {
        assertNull(resolve("", null))
        assertNull(resolve("   ", null))
        assertNull(resolve("fm.plastpenza.ru", null))
    }

    @Test
    fun `an unusable export url does not stand in for a missing field`() {
        // A relative or scheme-less value must not silently become the endpoint.
        assertNull(resolve("", "fm.plastpenza.ru/x"))
    }
}
