package lan.fmnode.android.ui

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Pure mapping rules shared by echomail rendering.
 *
 * Messages that only carry files (#9) must be listed as their own entry and get
 * a readable title, otherwise they end up as empty cards in the conversation.
 */
internal object MessageMapping {
    private const val PAPERCLIP = "\uD83D\uDCCE"

    /** True when a message consists of files and nothing else. */
    fun isAttachmentOnly(data: JsonObject?): Boolean {
        val attachments = data?.get("attachments") as? JsonArray ?: return false
        if (attachments.isEmpty()) return false
        val body = (data["body"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val subject = (data["subject"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        return body.isBlank() && subject.isBlank()
    }

    /** Title shown for a file bundle: a paperclip followed by the file names. */
    fun attachmentTitle(names: List<String>): String =
        "$PAPERCLIP ${names.joinToString(", ")}"

    /** Body to render: the letter text, or a file summary when there is none. */
    fun renderBody(data: JsonObject?): String {
        val body = (data?.get("body") as? JsonPrimitive)?.contentOrNull.orEmpty()
        if (body.isNotBlank()) return body
        val names = attachmentNames(data)
        return if (names.isEmpty()) body else attachmentTitle(names)
    }

    /** Attachment file names of a message, in payload order. */
    fun attachmentNames(data: JsonObject?): List<String> {
        val attachments = data?.get("attachments") as? JsonArray ?: return emptyList()
        return attachments.mapNotNull { element ->
            val att = element as? JsonObject ?: return@mapNotNull null
            (att["name"] as? JsonPrimitive)?.contentOrNull.orEmpty().ifBlank { null }
        }
    }
}
