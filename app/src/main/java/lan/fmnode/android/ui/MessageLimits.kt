package lan.fmnode.android.ui

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import lan.fmnode.android.network.RateLimitUsage

/** A limit problem shown in the composer; [blocking] disables the send button. */
sealed interface MessageLimitIssue {
    val blocking: Boolean

    data class AttachmentTooLarge(
        val name: String,
        val size: Long,
        val limit: Long,
    ) : MessageLimitIssue {
        override val blocking: Boolean = true
    }

    data class TooManyAttachments(val count: Int, val limit: Int) : MessageLimitIssue {
        override val blocking: Boolean = true
    }

    data class MessageTooLarge(val size: Long, val limit: Long) : MessageLimitIssue {
        override val blocking: Boolean = true
    }

    data class RateLimitExceeded(
        val kind: String,
        val used: Int,
        val limit: Int,
        override val blocking: Boolean,
    ) : MessageLimitIssue
}

/**
 * Effective message limits for one composer. Policy values may be overridden by
 * the echo rules (`max_message_size`, `max_attachment_size`,
 * `max_attachments_per_message`), as documented for `create_echo_with_rules`.
 */
data class MessageLimits(
    val maxMessageSize: Long,
    val maxAttachmentSize: Long,
    val maxAttachments: Int,
) {
    fun validate(
        subject: String,
        body: String,
        tagline: String,
        tearline: String?,
        attachments: List<AttachmentUi>,
        replyToMessageId: String?,
        senderName: String,
        rateLimit: RateLimitUsage?,
        rateLimitKind: String?,
        allowRateLimitOverride: Boolean,
    ): List<MessageLimitIssue> {
        val issues = mutableListOf<MessageLimitIssue>()
        if (maxAttachmentSize > 0) {
            attachments.forEach { attachment ->
                if (attachment.size > maxAttachmentSize) {
                    issues += MessageLimitIssue.AttachmentTooLarge(
                        attachment.name,
                        attachment.size.toLong(),
                        maxAttachmentSize,
                    )
                }
            }
        }
        if (maxAttachments > 0 && attachments.size > maxAttachments) {
            issues += MessageLimitIssue.TooManyAttachments(attachments.size, maxAttachments)
        }
        val size = estimatedDataSize(
            subject = subject,
            body = body,
            tagline = tagline,
            tearline = tearline,
            attachments = attachments,
            replyToMessageId = replyToMessageId,
            senderName = senderName,
        )
        if (maxMessageSize > 0 && size > maxMessageSize) {
            issues += MessageLimitIssue.MessageTooLarge(size, maxMessageSize)
        }
        if (rateLimit != null && rateLimit.limit > 0 &&
            rateLimit.used + 1 > rateLimit.limit && !allowRateLimitOverride &&
            rateLimitKind != null
        ) {
            issues += MessageLimitIssue.RateLimitExceeded(
                kind = rateLimitKind,
                used = rateLimit.used,
                limit = rateLimit.limit,
                blocking = true,
            )
        } else if (rateLimit != null && rateLimit.limit > 0 &&
            rateLimit.used + 1 > rateLimit.limit && allowRateLimitOverride &&
            rateLimitKind != null
        ) {
            issues += MessageLimitIssue.RateLimitExceeded(
                kind = rateLimitKind,
                used = rateLimit.used,
                limit = rateLimit.limit,
                blocking = false,
            )
        }
        return issues
    }

    companion object {
        const val DEFAULT_MAX_MESSAGE_SIZE = 1_048_576L
        const val DEFAULT_MAX_ATTACHMENT_SIZE = 5_242_880L
        const val DEFAULT_MAX_ATTACHMENTS = 5

        fun fromPolicy(policyJson: String, echoRules: String? = null): MessageLimits {
            val policy = policyParameters(policyJson)
            val echo = echoParameters(echoRules)
            return MessageLimits(
                maxMessageSize = echo.long("max_message_size")
                    ?: policy.long("max_message_size")
                    ?: DEFAULT_MAX_MESSAGE_SIZE,
                maxAttachmentSize = echo.long("max_attachment_size")
                    ?: policy.long("max_attachment_size")
                    ?: DEFAULT_MAX_ATTACHMENT_SIZE,
                maxAttachments = (echo.long("max_attachments_per_message")
                    ?: policy.long("max_attachments_per_message")
                    ?: DEFAULT_MAX_ATTACHMENTS.toLong()).toInt(),
            )
        }

        /**
         * Size of the string the node checks in `max_message_size`: the signed
         * data JSON, including base64 attachment payloads and user lines. This
         * mirrors the fields `postEcho`/`sendNetmail` put into [data].
         */
        fun estimatedDataSize(
            subject: String,
            body: String,
            tagline: String,
            tearline: String?,
            attachments: List<AttachmentUi>,
            replyToMessageId: String?,
            senderName: String,
        ): Long {
            val data = buildJsonObject {
                put("subject", subject)
                put("body", body)
                put("timestamp", System.currentTimeMillis() / 1000)
                put("sender_name", senderName)
                if (replyToMessageId != null) put("reply_to_message_id", replyToMessageId)
                if (attachments.isNotEmpty()) {
                    put(
                        "attachments",
                        buildJsonArray {
                            attachments.forEach { attachment ->
                                add(
                                    buildJsonObject {
                                        put("name", attachment.name)
                                        put("type", attachment.type)
                                        put("size", attachment.size)
                                        put("data", attachment.dataUrl)
                                    },
                                )
                            }
                        },
                    )
                }
                put("tagline", tagline)
                if (tearline != null) put("tearline", tearline)
            }
            return data.toString().toByteArray(Charsets.UTF_8).size.toLong()
        }

        private fun policyParameters(policyJson: String): JsonObject? {
            val root = runCatching { Json.parseToJsonElement(policyJson).jsonObject }.getOrNull()
                ?: return null
            return (root["policy"] as? JsonObject)?.get("parameters") as? JsonObject
                ?: root["parameters"] as? JsonObject
        }

        private fun echoParameters(rulesJson: String?): JsonObject? {
            if (rulesJson.isNullOrBlank()) return null
            val root = runCatching { Json.parseToJsonElement(rulesJson).jsonObject }.getOrNull()
                ?: return null
            return root["parameters"] as? JsonObject ?: root
        }

        private fun JsonObject?.long(key: String): Long? {
            val value = this?.get(key) as? JsonPrimitive ?: return null
            return value.contentOrNull?.trim()?.toLongOrNull()
        }
    }
}

/** Rate-limit kind for an echomail message; announcements are not counted. */
internal fun echoRateLimitKind(announcement: Boolean): String? =
    if (announcement) null else "echomail"

internal const val NETMAIL_RATE_LIMIT_KIND = "netmail"

/** Human-readable key for a rate-limit kind, localized later in the UI. */
internal fun rateLimitKindLabel(kind: String): String = when (kind) {
    "echomail" -> "echomail"
    "netmail" -> "netmail"
    "vote" -> "vote"
    "poster" -> "poster"
    "reaction" -> "reaction"
    else -> kind
}
