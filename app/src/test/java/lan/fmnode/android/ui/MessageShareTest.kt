package lan.fmnode.android.ui

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

    @Test
    fun `image renderer produces a bitmap with the requested width`() {
        val bitmap = MessageShare.renderImage(message(), 720)
        assertEquals(720, bitmap.width)
        assertTrue(bitmap.height > 0)
        bitmap.recycle()
    }
}
