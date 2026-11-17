package lan.fmnode.android.network

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Whether the user may post to a conference, which is also the condition for
 * showing reactions (#23, mirroring fm-app's `canWriteToEcho`).
 *
 * Network news is node-only. A conference whose `writers` list is non-empty and
 * not exactly `["all"]` is a channel: only the listed addresses may post.
 * Normal conferences and `writers: ["all"]` are open to everybody.
 */
object EchoPermissions {
    fun canWriteToEcho(rulesJson: String, address: String, networkNews: Boolean): Boolean {
        if (networkNews) return false
        val rules = RulesId.rulesMap(rulesJson) ?: return true
        val parameters = rules["parameters"] as? JsonObject ?: return true
        val writers = parameters["writers"] as? JsonArray ?: return true
        if (writers.isEmpty()) return true
        val names = writers.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        if (names.size == 1 && names[0] == "all") return true
        return address.isNotEmpty() && address in names
    }
}
