package lan.fmnode.android.ui

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Requirement #9: a message that carries only files is a valid message, so the
 * composer must not reject it as "empty" — and the node requires a subject, so
 * a file bundle gets one derived from its file names.
 */
class ComposeValidationTest {
    @Test
    fun textOnlyMessageIsSendable() {
        assertTrue(MessageMapping.canSend("hello", 0))
    }

    @Test
    fun fileOnlyMessageIsSendable() {
        assertTrue(MessageMapping.canSend("", 1))
    }

    @Test
    fun fileWithTextIsSendable() {
        assertTrue(MessageMapping.canSend("look at this", 2))
    }

    @Test
    fun emptyMessageIsRejected() {
        assertFalse(MessageMapping.canSend("", 0))
        assertFalse(MessageMapping.canSend("   ", 0))
    }

    @Test
    fun typedSubjectIsKept() {
        assertEquals("Report", MessageMapping.subjectFor("Report", listOf("a.pdf")))
    }

    @Test
    fun fileBundleGetsSubjectFromFileNames() {
        assertEquals(
            "📎 a.png, b.pdf",
            MessageMapping.subjectFor("   ", listOf("a.png", "b.pdf")),
        )
    }

    @Test
    fun textOnlyMessageWithoutSubjectStaysEmpty() {
        assertEquals("", MessageMapping.subjectFor("", emptyList()))
    }

    @Test
    fun taglineAndTearlineAreReadFromPayload() {
        val data = Json.parseToJsonElement(
            """{"body":"Hi","tagline":"  Pavel  ","tearline":"fm-android v0.3.0 :0:1"}""",
        ).jsonObject
        assertEquals("Pavel", MessageMapping.taglineOf(data))
        assertEquals("fm-android v0.3.0 :0:1", MessageMapping.tearlineOf(data))
        assertEquals("", MessageMapping.taglineOf(null))
    }
}
