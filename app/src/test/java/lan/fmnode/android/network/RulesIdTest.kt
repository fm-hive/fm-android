package lan.fmnode.android.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `accept_rules` is only accepted when the rules id matches the node's own
 * hash, so the canonical form and the normalization must mirror fm-go.
 */
class RulesIdTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `canonical form sorts object keys`() {
        val element = json.parseToJsonElement("""{"b":2,"a":1}""")

        assertEquals("""{"a":1,"b":2}""", RulesId.canonical(element))
    }

    @Test
    fun `canonical form sorts nested objects and keeps array order`() {
        val element = json.parseToJsonElement("""{"z":[{"b":1,"a":2}],"a":{"d":1,"c":2}}""")

        assertEquals("""{"a":{"c":2,"d":1},"z":[{"a":2,"b":1}]}""", RulesId.canonical(element))
    }

    @Test
    fun `rules with parameters are kept as is`() {
        val rules = """{"text":"hello","parameters":{"moderators":[":0:1"]}}"""

        val map = RulesId.rulesMap(rules)

        assertNotNull(map)
        assertEquals(setOf("text", "parameters"), map!!.keys)
    }

    @Test
    fun `legacy flat rules are wrapped into parameters`() {
        val rules = """{"text":"hello","moderators":[":0:1"]}"""

        val map = RulesId.rulesMap(rules)!!

        assertEquals("hello", map["text"].toString().trim('"'))
        val parameters = map["parameters"].toString()
        assertEquals("""{"moderators":[":0:1"]}""", parameters)
    }

    @Test
    fun `id is a stable hex sha256`() {
        val rules = """{"text":"hello","parameters":{}}"""

        val id = RulesId.compute(rules)

        assertNotNull(id)
        assertEquals(64, id!!.length)
        // Same rules written in another key order must hash the same.
        assertEquals(id, RulesId.compute("""{"parameters":{},"text":"hello"}"""))
        assertNotEquals(id, RulesId.compute("""{"text":"other","parameters":{}}"""))
    }

    @Test
    fun `echo without rules has no id`() {
        assertNull(RulesId.compute(""))
        assertNull(RulesId.compute("{}"))
        assertNull(RulesId.compute("not json"))
    }
}
