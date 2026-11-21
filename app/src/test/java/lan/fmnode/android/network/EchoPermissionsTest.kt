package lan.fmnode.android.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reactions are hidden where the user cannot post (#23). */
class EchoPermissionsTest {
    private val user = ":0:1:1"

    @Test
    fun `normal echoes and writers all are open`() {
        assertTrue(EchoPermissions.canWriteToEcho("", user, networkNews = false))
        assertTrue(EchoPermissions.canWriteToEcho("{}", user, networkNews = false))
        assertTrue(
            EchoPermissions.canWriteToEcho(
                """{"parameters":{"writers":["all"]}}""",
                user,
                networkNews = false,
            ),
        )
    }

    @Test
    fun `restricted writers require the address to be listed`() {
        val rules = """{"parameters":{"writers":[":0:1:1",":0:1:2"]}}"""
        assertTrue(EchoPermissions.canWriteToEcho(rules, user, networkNews = false))
        assertFalse(EchoPermissions.canWriteToEcho(rules, ":0:1:3", networkNews = false))
        assertFalse(EchoPermissions.canWriteToEcho(rules, "", networkNews = false))
    }

    @Test
    fun `legacy flat rules are handled`() {
        assertTrue(
            EchoPermissions.canWriteToEcho(
                """{"text":"rules","writers":[":0:1:1"]}""",
                user,
                networkNews = false,
            ),
        )
        assertFalse(
            EchoPermissions.canWriteToEcho(
                """{"text":"rules","writers":[":0:1:1"]}""",
                ":0:1:3",
                networkNews = false,
            ),
        )
    }

    @Test
    fun `network news is never writable`() {
        assertFalse(EchoPermissions.canWriteToEcho("", user, networkNews = true))
        assertFalse(
            EchoPermissions.canWriteToEcho(
                """{"parameters":{"writers":[":0:1:1"]}}""",
                user,
                networkNews = true,
            ),
        )
    }

    @Test
    fun `announcements are enabled only by the echo rule`() {
        assertFalse(EchoPermissions.announcementsEnabled(""))
        assertFalse(EchoPermissions.announcementsEnabled("""{"parameters":{}}"""))
        assertTrue(
            EchoPermissions.announcementsEnabled(
                """{"parameters":{"announcements_enable":true}}""",
            ),
        )
        assertTrue(
            EchoPermissions.announcementsEnabled(
                """{"parameters":{"announcements_enable":"true"}}""",
            ),
        )
    }

}
