package lan.fmnode.android.ui

import lan.fmnode.android.network.RateLimitUsage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageLimitsTest {
    private val policy = """
        {"policy":{"parameters":{
          "max_message_size":"1000",
          "max_attachment_size":"100",
          "max_attachments_per_message":"2"
        }}}
    """.trimIndent()

    private fun attachment(name: String, size: Int) = AttachmentUi(
        name = name,
        type = "application/octet-stream",
        size = size,
        dataUrl = "data:application/octet-stream;base64,${"A".repeat(size)}",
    )

    @Test
    fun `policy limits are parsed with defaults`() {
        val limits = MessageLimits.fromPolicy(policy)
        assertEquals(1000L, limits.maxMessageSize)
        assertEquals(100L, limits.maxAttachmentSize)
        assertEquals(2, limits.maxAttachments)

        val defaults = MessageLimits.fromPolicy("{}")
        assertEquals(MessageLimits.DEFAULT_MAX_MESSAGE_SIZE, defaults.maxMessageSize)
        assertEquals(MessageLimits.DEFAULT_MAX_ATTACHMENT_SIZE, defaults.maxAttachmentSize)
        assertEquals(MessageLimits.DEFAULT_MAX_ATTACHMENTS, defaults.maxAttachments)
    }

    @Test
    fun `echo rules override policy limits`() {
        val limits = MessageLimits.fromPolicy(
            policy,
            """{"parameters":{"max_message_size":"2000","max_attachment_size":"200"}}""",
        )
        assertEquals(2000L, limits.maxMessageSize)
        assertEquals(200L, limits.maxAttachmentSize)
        assertEquals(2, limits.maxAttachments)
    }

    @Test
    fun `oversized attachment and count block sending`() {
        val limits = MessageLimits.fromPolicy(policy)
        val issues = limits.validate(
            subject = "s",
            body = "b",
            tagline = "",
            tearline = null,
            attachments = listOf(attachment("big", 101), attachment("one", 1), attachment("two", 1)),
            replyToMessageId = null,
            senderName = "",
            rateLimit = null,
            rateLimitKind = null,
            allowRateLimitOverride = false,
        )
        assertTrue(issues.any { it is MessageLimitIssue.AttachmentTooLarge })
        assertTrue(issues.any { it is MessageLimitIssue.TooManyAttachments })
    }

    @Test
    fun `message size includes base64 attachments`() {
        val limits = MessageLimits.fromPolicy(policy)
        val issues = limits.validate(
            subject = "s",
            body = "b",
            tagline = "",
            tearline = null,
            attachments = listOf(attachment("small", 50)),
            replyToMessageId = null,
            senderName = "",
            rateLimit = null,
            rateLimitKind = null,
            allowRateLimitOverride = false,
        )
        // 50 bytes of payload become >50 bytes as base64; together with the
        // JSON envelope this is still under 1000, but a much larger payload
        // must be reported.
        assertFalse(issues.any { it is MessageLimitIssue.MessageTooLarge })

        val big = limits.validate(
            subject = "s",
            body = "b",
            tagline = "",
            tearline = null,
            attachments = listOf(attachment("big", 900)),
            replyToMessageId = null,
            senderName = "",
            rateLimit = null,
            rateLimitKind = null,
            allowRateLimitOverride = false,
        )
        assertTrue(big.any { it is MessageLimitIssue.MessageTooLarge })
    }

    @Test
    fun `rate limit blocks unless the override is enabled`() {
        val limits = MessageLimits.fromPolicy(policy)
        val rate = RateLimitUsage(used = 5, limit = 5)
        val blocked = limits.validate(
            subject = "s", body = "b", tagline = "", tearline = null,
            attachments = emptyList(), replyToMessageId = null, senderName = "",
            rateLimit = rate, rateLimitKind = "echomail", allowRateLimitOverride = false,
        )
        val issue = blocked.single { it is MessageLimitIssue.RateLimitExceeded }
        assertTrue(issue.blocking)

        val allowed = limits.validate(
            subject = "s", body = "b", tagline = "", tearline = null,
            attachments = emptyList(), replyToMessageId = null, senderName = "",
            rateLimit = rate, rateLimitKind = "echomail", allowRateLimitOverride = true,
        )
        val override = allowed.single { it is MessageLimitIssue.RateLimitExceeded }
        assertFalse(override.blocking)
    }
}
