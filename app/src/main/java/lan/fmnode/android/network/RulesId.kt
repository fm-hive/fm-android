package lan.fmnode.android.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import lan.fmnode.android.crypto.Encoding

/**
 * Echo rules as the node hashes them: `accept_rules` must carry a signature over
 * `{"id": <rules id>, "rules": <rules map>}`, where the id is the SHA-256 of the
 * rules map in Go's canonical JSON form (map keys sorted, nested maps sorted too).
 *
 * Mirroring the normalization matters: the node stores rules in the canonical
 * `{text, parameters}` form, but older echoes keep the flat form (`text` plus
 * parameters at the top level), which the node wraps before hashing.
 */
internal object RulesId {
    private val json = Json { ignoreUnknownKeys = true }

    private const val HEX = "0123456789abcdef"

    /** Rules parsed into the canonical `{text, parameters}` map, or null when empty. */
    fun rulesMap(rulesJson: String): JsonObject? {
        if (rulesJson.isBlank()) return null
        val parsed = runCatching { json.parseToJsonElement(rulesJson).jsonObject }.getOrNull()
            ?: return null
        if (parsed.isEmpty()) return null
        if (parsed.containsKey("parameters")) return parsed
        val text = (parsed["text"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val parameters = JsonObject(
            parsed.toMutableMap().apply { remove("text") },
        )
        return JsonObject(linkedMapOf("text" to JsonPrimitive(text), "parameters" to parameters))
    }

    /** Rules id (hex SHA-256) the node compares against, or null when there are no rules. */
    fun compute(rulesJson: String): String? {
        val rules = rulesMap(rulesJson) ?: return null
        val canonical = canonical(rules)
        return toHex(Encoding.sha256(canonical.toByteArray(Charsets.UTF_8)))
    }

    /** Go-compatible JSON: object keys sorted, arrays keep their order. */
    fun canonical(element: JsonElement): String = when (element) {
        is JsonObject -> element.entries
            .sortedBy { it.key }
            .joinToString(separator = ",", prefix = "{", postfix = "}") { (key, value) ->
                "${JsonPrimitive(key)}:${canonical(value)}"
            }
        is JsonArray -> element.joinToString(separator = ",", prefix = "[", postfix = "]") { canonical(it) }
        else -> element.toString()
    }

    private fun toHex(bytes: ByteArray): String {
        val out = StringBuilder(bytes.size * 2)
        for (byte in bytes) {
            val value = byte.toInt() and 0xFF
            out.append(HEX[value ushr 4]).append(HEX[value and 0x0F])
        }
        return out.toString()
    }
}
