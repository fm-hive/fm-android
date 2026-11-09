package lan.fmnode.android.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Queued messages carry the sender address inside their signed payload. A
 * network merge renumbers users, so every queued payload keeps pointing at the
 * old address and the node rejects it as a sender mismatch (#11).
 *
 * [rewrite] replaces the address fields and returns the payload to store, or
 * null when nothing had to change.
 */
internal object OutboxAddress {
    private val json = Json { ignoreUnknownKeys = true }

    private val ADDRESS_FIELDS = listOf("from", "sender_address", "node1")

    fun rewrite(dataJson: String, oldAddress: String, newAddress: String): String? {
        if (oldAddress.isEmpty() || newAddress.isEmpty() || oldAddress == newAddress) return null
        val obj = runCatching { json.parseToJsonElement(dataJson).jsonObject }.getOrNull() ?: return null
        var updated: JsonObject? = null
        for (field in ADDRESS_FIELDS) {
            val current = (obj[field] as? JsonPrimitive)?.contentOrNull
            if (current != oldAddress) continue
            val target = updated ?: obj
            updated = JsonObject(target.toMutableMap().apply { put(field, JsonPrimitive(newAddress)) })
        }
        return updated?.toString()
    }
}
