package lan.fmnode.android.ui

import android.graphics.Bitmap
import android.util.Base64
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MessageShareTest {
    private val labels = ShareLabels(
        echo = "Эха",
        from = "От",
        date = "Дата",
        netmailEcho = "Личные",
    )

    private fun message(subject: String = "Привет", body: String = "Текст") = MessageUi(
        messageId = "m1",
        senderName = "Иван",
        senderAddress = ":0:1:1",
        recipientAddress = null,
        timestamp = 1_800_000_000,
        subject = subject,
        body = body,
        own = false,
        echoName = "Newcomers",
        tagline = "подпись",
        tearline = "fm-android v1 :0:1:1",
    )

    @Test
    fun `text share has the requested header subject and body`() {
        val text = MessageShare.text(message(), labels)
        assertTrue(text.startsWith("**Эха:** Newcomers"))
        assertTrue(text.contains("**От:** Иван (:0:1:1)"))
        assertTrue(text.contains("**Дата:**"))
        assertTrue(text.contains("# Привет"))
        assertTrue(text.contains("Текст"))
        assertTrue(text.contains("подпись"))
        assertTrue(text.contains("--- fm-android v1 :0:1:1"))
    }

    @Test
    fun `netmail text uses the localized netmail label`() {
        val netmail = message().copy(echoName = "__netmail__")
        val text = MessageShare.text(netmail, labels)
        assertTrue(text.contains("**Эха:** Личные"))
    }

    private fun imageAttachment(): AttachmentUi {
        val bitmap = Bitmap.createBitmap(20, 10, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.RED)
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        bitmap.recycle()
        val bytes = out.toByteArray()
        return AttachmentUi(
            name = "photo.png",
            type = "image/png",
            size = bytes.size,
            dataUrl = "data:image/png;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP),
        )
    }

    @Test
    fun `inline image references keep their position between text blocks`() {
        val attachment = imageAttachment()
        val blocks = messageBodyBlocks(
            "before ![photo](attachment:0) after",
            "m1",
            listOf(attachment),
        )
        assertEquals(3, blocks.size)
        assertEquals("before ", blocks[0].text)
        assertEquals("photo.png", blocks[1].attachment?.name)
        assertEquals(" after", blocks[2].text)
    }

    @Test
    fun `image renderer produces a bitmap with the requested width`() {
        val bitmap = MessageShare.renderImage(message(), 720)
        assertEquals(720, bitmap.width)
        assertTrue(bitmap.height > 0)
        bitmap.recycle()
    }

    @Test
    fun `image renderer handles inline images`() {
        val message = message(body = "before ![photo](attachment:0) after")
            .copy(attachments = listOf(imageAttachment()))
        val bitmap = MessageShare.renderImage(message, 720)
        assertEquals(720, bitmap.width)
        assertTrue(bitmap.height > 0)
        bitmap.recycle()
    }
}
