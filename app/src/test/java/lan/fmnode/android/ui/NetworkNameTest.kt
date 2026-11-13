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
        assertEquals("FM", networkNameFromPolicyJson("""{"policy":{"parameters":{}}}"""))
        assertEquals("FM", networkNameFromPolicyJson("""{"policy":{"parameters":{"net_name":"   "}}}"""))
        assertEquals("FM", networkNameFromPolicyJson("not json"))
    }
}
