package lan.fmnode.android.storage

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray

/** Default reaction emoji list shared with fm-app; exports omit it while unchanged (#373). */
object EmojiDefaults {
    val LIST = listOf(
        "👍", "👎", "❤️", "💯", "😄", "😊", "😍", "🎉", "🎊", "👏", "💪", "🚀",
        "🔥", "😁", "😂", "🤣", "🥰", "😘", "🤗", "🎈", "🎁", "🏆", "⭐", "😎",
        "🤩", "🥳", "😤", "😭", "🫂", "🤝", "😢", "😞", "😡", "🤔", "👀", "🙏",
    )

    fun isDefault(emojis: JsonArray?): Boolean {
        if (emojis == null) return true
        if (emojis.size != LIST.size) return false
        return emojis.withIndex().all { (index, value) ->
            (value as? JsonPrimitive)?.content == LIST[index]
        }
    }

    /**
     * Settings without `ui.customEmojis` when the list is unchanged. A changed
     * list stays in the settings for the fm_settings export (fm-app #373).
     */
    fun settingsWithoutDefaultEmojis(settings: JsonObject): JsonObject {
        val ui = settings["ui"] as? JsonObject ?: return settings
        val emojis = ui["customEmojis"] as? JsonArray ?: return settings
        if (!isDefault(emojis)) return settings
        val cleanedUI = JsonObject(ui.toMutableMap().apply { remove("customEmojis") })
        return JsonObject(settings.toMutableMap().apply { put("ui", cleanedUI) })
    }

    /**
     * Full-export settings: `ui.customEmojis` always moves to the top-level
     * `emojis` array (only when changed), matching fm-app's v2.0 export.
     */
    fun settingsForFullExport(settings: JsonObject): Pair<JsonObject, JsonArray?> {
        val ui = settings["ui"] as? JsonObject ?: return settings to null
        val emojis = ui["customEmojis"] as? JsonArray ?: return settings to null
        val cleanedUI = JsonObject(ui.toMutableMap().apply { remove("customEmojis") })
        val cleanedSettings = JsonObject(settings.toMutableMap().apply { put("ui", cleanedUI) })
        return cleanedSettings to if (isDefault(emojis)) null else emojis
    }

    /** Applies a top-level `emojis` array from an fm-app export to the settings. */
    fun withEmojis(settings: JsonObject, emojis: JsonArray): JsonObject {
        val ui = settings["ui"] as? JsonObject ?: JsonObject(emptyMap())
        val updatedUI = JsonObject(ui.toMutableMap().apply { put("customEmojis", emojis) })
        return JsonObject(settings.toMutableMap().apply { put("ui", updatedUI) })
    }

    fun array(emojis: List<String>): JsonArray = buildJsonArray {
        emojis.forEach { add(JsonPrimitive(it)) }
    }
}
