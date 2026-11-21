package lan.fmnode.android.ui

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Requirement #9: a message that only carries files must still be shown as a
 * separate message with a readable title instead of an empty card.
 */
class AttachmentMessageTest {
    private val imageDataUrl = "data:image/png;base64,iVBORw0KGgo="

    private fun data(
        subject: String = "",
        body: String = "",
        attachments: JsonArray? = null,
    ): JsonObject {
        val map = linkedMapOf<String, JsonElement>()
        map["subject"] = JsonPrimitive(subject)
        map["body"] = JsonPrimitive(body)
        map["timestamp"] = JsonPrimitive(1700000000L)
        if (attachments != null) map["attachments"] = attachments
        return JsonObject(map)
    }

    private fun attachment(name: String) = JsonObject(
        mapOf(
            "name" to JsonPrimitive(name),
            "type" to JsonPrimitive("image/png"),
            "size" to JsonPrimitive(1024),
            "data" to JsonPrimitive(imageDataUrl),
        ),
    )

    @Test
    fun fileBundleWithoutLetterGetsReadableBody() {
        val payload = data(attachments = JsonArray(listOf(attachment("cat.png"), attachment("dog.png"))))

        assertEquals("📎 cat.png, dog.png", MessageMapping.renderBody(payload))
    }

    @Test
    fun regularMessageKeepsItsBody() {
        val payload = data(
            subject = "Hi",
            body = "Hello world",
            attachments = JsonArray(listOf(attachment("cat.png"))),
        )

        assertEquals("Hello world", MessageMapping.renderBody(payload))
    }

    @Test
    fun messageWithoutAttachmentsKeepsEmptyBody() {
        assertTrue(MessageMapping.renderBody(data(subject = "Hi")).isEmpty())
    }

    @Test
    fun attachmentOnlyIsDetected() {
        val attachmentsOnly = data(attachments = JsonArray(listOf(attachment("cat.png"))))
        val withText = data(body = "note", attachments = JsonArray(listOf(attachment("cat.png"))))
        val withoutFiles = data(body = "", attachments = null)

        assertTrue(MessageMapping.isAttachmentOnly(attachmentsOnly))
        assertFalse(MessageMapping.isAttachmentOnly(withText))
        assertFalse(MessageMapping.isAttachmentOnly(withoutFiles))
        assertFalse(MessageMapping.isAttachmentOnly(null))
    }

    @Test
    fun attachmentNamesAreMappedInOrder() {
        val payload = data(attachments = JsonArray(listOf(attachment("a.png"), attachment("b.pdf"))))

        assertEquals(listOf("a.png", "b.pdf"), MessageMapping.attachmentNames(payload))
    }
}
