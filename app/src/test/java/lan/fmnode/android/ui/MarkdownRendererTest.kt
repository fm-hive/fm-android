package lan.fmnode.android.ui

import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MarkdownRendererTest {
    @Test
    fun `bold text is rendered without markers`() {
        val rendered = MarkdownRenderer.render("Hello **world**")
        assertEquals("Hello world", rendered.text.trim())
        assertTrue(rendered.spanStyles.any { it.item.fontWeight == FontWeight.Bold })
    }

    @Test
    fun `links keep their target`() {
        val rendered = MarkdownRenderer.render("See [site](https://example.com)")
        assertEquals("See site", rendered.text.trim())
        assertTrue(rendered.getLinkAnnotations(0, rendered.length).isNotEmpty())
    }

    @Test
    fun `lists and code blocks are rendered`() {
        val rendered = MarkdownRenderer.render("- one\n- two\n\n```\ncode line\n```")
        assertTrue(rendered.text.contains("• one"))
        assertTrue(rendered.text.contains("code line"))
    }
}
