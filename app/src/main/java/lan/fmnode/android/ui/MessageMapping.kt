package lan.fmnode.android.ui

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Pure mapping rules shared by echomail rendering.
 *
 * Messages that only carry files (#9) must be listed as their own entry and get
 * a readable title, otherwise they end up as empty cards in the conversation.
 */
internal object MessageMapping {
    private const val PAPERCLIP = "\uD83D\uDCCE"

    /**
     * A message is sendable when it has text or at least one file: a bundle of
     * files without a letter is a valid message (#9).
     */
    fun canSend(body: String, attachmentCount: Int): Boolean =
        body.isNotBlank() || attachmentCount > 0

    /**
     * Echo mail the node accepts must carry a subject: for a file bundle without
     * a typed subject the file names become the subject (#9).
     */
    fun subjectFor(subject: String, attachmentNames: List<String>): String {
        val trimmed = subject.trim()
        if (trimmed.isNotEmpty()) return trimmed
        return if (attachmentNames.isEmpty()) "" else attachmentTitle(attachmentNames)
    }

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

    /**
     * Body to render: the letter text, or a file summary when there is none.
     * A file bundle whose subject already lists the files gets no duplicate body.
     */
    fun renderBody(data: JsonObject?): String {
        val body = (data?.get("body") as? JsonPrimitive)?.contentOrNull.orEmpty()
        if (body.isNotBlank()) return body
        val subject = (data?.get("subject") as? JsonPrimitive)?.contentOrNull.orEmpty()
        if (subject.isNotBlank()) return ""
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

/**
 * Moderation fields of a warning/punishment echomail, if it is one.
 *
 * [senderAddress] is the author of the notice — the moderator. It cannot be read
 * from the payload, which only names the punished user in `to`.
 */
internal fun moderationOf(data: JsonObject?, senderAddress: String = ""): ModerationUi? {
    if (data == null || data.containsKey("event")) return null
    val severity = (data["severity"] as? JsonPrimitive)?.contentOrNull.orEmpty()
    if (severity != "warning" && severity != "punishment") return null
    val punished = (data["to"] as? JsonPrimitive)?.contentOrNull.orEmpty()
    if (punished.isEmpty()) return null
    val original = data["originalMessage"]
    val originalId = when (original) {
        is JsonObject -> (original["message_id"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        is JsonPrimitive -> runCatching {
            val parsed = Json.parseToJsonElement(original.content).jsonObject
            (parsed["message_id"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        }.getOrDefault("")
        else -> ""
    }
    return ModerationUi(severity, punished, originalId, senderAddress)
}
