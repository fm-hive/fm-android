package lan.fmnode.android.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.findChildOfType
import org.intellij.markdown.ast.getTextInNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser

/**
 * Lightweight markdown -> AnnotatedString renderer for message bodies:
 * headings, bold/italic, inline code and code blocks, links, lists, quotes.
 */
object MarkdownRenderer {
    private val parser = MarkdownParser(GFMFlavourDescriptor())

    private val SKIPPED_MARKERS = setOf(
        MarkdownTokenTypes.EMPH,
        MarkdownTokenTypes.BACKTICK,
        MarkdownTokenTypes.ESCAPED_BACKTICKS,
        MarkdownTokenTypes.ATX_HEADER,
        MarkdownTokenTypes.SETEXT_1,
        MarkdownTokenTypes.SETEXT_2,
        MarkdownTokenTypes.LIST_BULLET,
        MarkdownTokenTypes.LIST_NUMBER,
        MarkdownTokenTypes.BLOCK_QUOTE,
        MarkdownTokenTypes.CODE_FENCE_START,
        MarkdownTokenTypes.CODE_FENCE_END,
    )

    fun render(markdown: String): AnnotatedString {
        val source = markdown.replace("\r\n", "\n")
        val tree = parser.buildMarkdownTreeFromString(source)
        val built = buildAnnotatedString {
            appendChildren(this, tree, source)
        }
        var end = built.length
        while (end > 0 && built.text[end - 1] == '\n') end--
        return built.subSequence(0, end)
    }

    private fun appendChildren(builder: AnnotatedString.Builder, node: ASTNode, source: String) {
        for (child in node.children) appendNode(builder, child, source)
    }

    private fun appendNode(builder: AnnotatedString.Builder, node: ASTNode, source: String) {
        // Structural marker tokens (delimiters, bullets, hashes, quotes) are
        // rendered as decoration by the parent elements, never as text.
        if (node.type in SKIPPED_MARKERS) return
        when (node.type) {
            MarkdownElementTypes.PARAGRAPH -> {
                appendChildren(builder, node, source)
                builder.append("\n\n")
            }
            MarkdownElementTypes.ATX_1, MarkdownElementTypes.ATX_2, MarkdownElementTypes.ATX_3,
            MarkdownElementTypes.ATX_4, MarkdownElementTypes.ATX_5, MarkdownElementTypes.ATX_6,
            MarkdownElementTypes.SETEXT_1, MarkdownElementTypes.SETEXT_2,
            -> {
                builder.withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                    appendChildren(builder, node, source)
                }
                builder.append("\n\n")
            }
            MarkdownElementTypes.STRONG -> builder.withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                appendChildren(builder, node, source)
            }
            MarkdownElementTypes.EMPH -> builder.withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                appendChildren(builder, node, source)
            }
            MarkdownElementTypes.CODE_SPAN -> builder.withStyle(
                SpanStyle(fontFamily = FontFamily.Monospace),
            ) {
                appendChildren(builder, node, source)
            }
            MarkdownElementTypes.CODE_FENCE, MarkdownElementTypes.CODE_BLOCK -> {
                val raw = node.getTextInNode(source).toString()
                val code = raw.trim('\n').lines()
                    .filterNot { it.trimStart().startsWith("```") }
                    .joinToString("\n")
                builder.withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(code) }
                builder.append("\n\n")
            }
            MarkdownElementTypes.INLINE_LINK, MarkdownElementTypes.AUTOLINK -> {
                val text = node.getTextInNode(source).toString()
                val url = if (node.type == MarkdownElementTypes.AUTOLINK) {
                    text.trim('<', '>')
                } else {
                    val dest = node.findChildOfType(MarkdownElementTypes.LINK_DESTINATION)
                    dest?.getTextInNode(source)?.toString()?.trim('<', '>') ?: text
                }
                val label = if (node.type == MarkdownElementTypes.AUTOLINK) {
                    url
                } else {
                    val linkText = node.findChildOfType(MarkdownElementTypes.LINK_TEXT)
                    linkText?.getTextInNode(source)?.toString()?.trim('[', ']') ?: url
                }
                builder.withLink(LinkAnnotation.Url(url)) { append(label) }
            }
            MarkdownElementTypes.UNORDERED_LIST, MarkdownElementTypes.ORDERED_LIST -> {
                appendChildren(builder, node, source)
                builder.append("\n")
            }
            MarkdownElementTypes.LIST_ITEM -> {
                builder.append("• ")
                appendChildren(builder, node, source)
                builder.append("\n")
            }
            MarkdownElementTypes.BLOCK_QUOTE -> {
                appendChildren(builder, node, source)
                builder.append("\n")
            }
            MarkdownTokenTypes.HORIZONTAL_RULE -> builder.append("\n———\n")
            MarkdownTokenTypes.EOL -> builder.append("\n")
            else -> {
                if (node.children.isEmpty()) {
                    builder.append(node.getTextInNode(source).toString())
                } else {
                    appendChildren(builder, node, source)
                }
            }
        }
    }
}
