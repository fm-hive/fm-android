package lan.fmnode.android.network

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Daily usage of one `rate_limit` kind, as reported by the node. */
data class RateLimitUsage(val used: Int, val limit: Int)

/** Reads the `rate_limit` map returned by send_receive. */
internal fun parseRateLimits(element: JsonElement?): Map<String, RateLimitUsage> {
    val obj = element as? JsonObject ?: return emptyMap()
    return obj.mapNotNull { (kind, raw) ->
        val value = raw as? JsonObject ?: return@mapNotNull null
        val used = (value["used"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0
        val limit = (value["limit"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0
        kind to RateLimitUsage(used, limit)
    }.toMap()
}
