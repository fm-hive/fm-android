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
    fun `an absent net_name yields an empty name`() {
        assertEquals("", networkNameFromPolicyJson("""{"policy":{"parameters":{}}}"""))
        assertEquals("", networkNameFromPolicyJson("""{"policy":{"parameters":{"net_name":"   "}}}"""))
        assertEquals("", networkNameFromPolicyJson("not json"))
    }

    /**
     * A network really named "FM" (the test stand's node1) must keep that name;
     * it is not the "no name" case.
     */
    @Test
    fun `a network named FM is a real name`() {
        val policy = """{"policy":{"parameters":{"net_name":"FM"}}}"""

        assertEquals("FM", networkNameFromPolicyJson(policy))
        assertEquals("FM", group(networkNameFromPolicyJson(policy)).displayName)
    }

    @Test
    fun `network news name comes from special echogroups`() {
        val policy = """{"policy":{"parameters":{"special_echogroups":{"network_news":"News"}}}}"""
        assertEquals("News", networkNewsEchoName(policy))
        assertEquals("Network news", networkNewsEchoName("""{"policy":{"parameters":{}}}"""))
        assertEquals("Network news", networkNewsEchoName("not json"))
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
    fun `an unnamed network keeps its profile title`() {
        assertEquals("https://localhost:8443", group("").displayName)
        assertEquals("https://localhost:8443", group("   ").displayName)
    }
}
