package lan.fmnode.android.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class NetworkNameTest {
    @Test
    fun `uses net_name from the policy`() {
        val policy = """{"id":"p","policy":{"text":"rules","parameters":{"net_name":"MyNet"}}}"""
        assertEquals("MyNet", networkNameFromPolicyJson(policy))
    }

    @Test
    fun `trims whitespace`() {
        val policy = """{"policy":{"parameters":{"net_name":"  MyNet  "}}}"""
        assertEquals("MyNet", networkNameFromPolicyJson(policy))
    }

    @Test
    fun `falls back to FM for missing, blank or broken policies`() {
        assertEquals(UNNAMED_NETWORK, networkNameFromPolicyJson("""{"policy":{"parameters":{}}}"""))
        assertEquals(UNNAMED_NETWORK, networkNameFromPolicyJson("""{"policy":{"parameters":{"net_name":"   "}}}"""))
        assertEquals(UNNAMED_NETWORK, networkNameFromPolicyJson("not json"))
    }

    private fun group(name: String) = NetworkGroupUi(
        profileId = "p",
        networkName = name,
        title = "https://localhost:8443",
        address = ":0:1",
        endpoint = "localhost:8443",
        isActive = true,
        echoes = emptyList(),
    )

    /** #20: the header shows the policy name, never the HTTP endpoint. */
    @Test
    fun `a named network shows its policy name`() {
        assertEquals("MyNet", group("MyNet").displayName)
    }

    @Test
    fun `an unnamed network keeps its profile title and not a bare FM`() {
        assertEquals("https://localhost:8443", group(UNNAMED_NETWORK).displayName)
        assertEquals("https://localhost:8443", group("").displayName)
        assertEquals("https://localhost:8443", group("   ").displayName)
    }
}
