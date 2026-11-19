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

    /**
     * [attachmentResolver] receives a full `attachment:N` or
     * `attachment:<message_id>:N` reference and returns the file name to show.
     * The reference is rendered as an attachment label instead of a broken
     * link (the composer inserts these references, the gallery shows the file).
     */
    fun render(
        markdown: String,
        attachmentResolver: (String) -> String? = { null },
    ): AnnotatedString {
        val source = markdown.replace("\r\n", "\n")
        val tree = parser.buildMarkdownTreeFromString(source)
        val built = buildAnnotatedString {
            appendChildren(this, tree, source, attachmentResolver)
        }
        var end = built.length
        while (end > 0 && built.text[end - 1] == '\n') end--
        return built.subSequence(0, end)
    }

    private fun appendChildren(
        builder: AnnotatedString.Builder,
        node: ASTNode,
        source: String,
        attachmentResolver: (String) -> String?,
    ) {
        for (child in node.children) appendNode(builder, child, source, attachmentResolver)
    }

    private fun appendNode(
        builder: AnnotatedString.Builder,
        node: ASTNode,
        source: String,
        attachmentResolver: (String) -> String?,
    ) {
        // Structural marker tokens (delimiters, bullets, hashes, quotes) are
        // rendered as decoration by the parent elements, never as text.
        if (node.type in SKIPPED_MARKERS) return
        when (node.type) {
            MarkdownElementTypes.PARAGRAPH -> {
                appendChildren(builder, node, source, attachmentResolver)
                builder.append("\n\n")
            }
            MarkdownElementTypes.ATX_1, MarkdownElementTypes.ATX_2, MarkdownElementTypes.ATX_3,
            MarkdownElementTypes.ATX_4, MarkdownElementTypes.ATX_5, MarkdownElementTypes.ATX_6,
            MarkdownElementTypes.SETEXT_1, MarkdownElementTypes.SETEXT_2,
            -> {
                builder.withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                    appendChildren(builder, node, source, attachmentResolver)
                }
                builder.append("\n\n")
            }
            MarkdownElementTypes.STRONG -> builder.withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                appendChildren(builder, node, source, attachmentResolver)
            }
            MarkdownElementTypes.EMPH -> builder.withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                appendChildren(builder, node, source, attachmentResolver)
            }
            MarkdownElementTypes.CODE_SPAN -> builder.withStyle(
                SpanStyle(fontFamily = FontFamily.Monospace),
            ) {
                appendChildren(builder, node, source, attachmentResolver)
            }
            MarkdownElementTypes.CODE_FENCE, MarkdownElementTypes.CODE_BLOCK -> {
                val raw = node.getTextInNode(source).toString()
                val code = raw.trim('\n').lines()
                    .filterNot { it.trimStart().startsWith("```") }
                    .joinToString("\n")
                builder.withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(code) }
                builder.append("\n\n")
            }
            MarkdownElementTypes.INLINE_LINK, MarkdownElementTypes.IMAGE,
            MarkdownElementTypes.AUTOLINK,
            -> {
                val text = node.getTextInNode(source).toString()
                // IMAGE wraps an INLINE_LINK node; the destination and link text
                // live there, not directly under IMAGE.
                val linkNode = if (node.type == MarkdownElementTypes.IMAGE) {
                    node.findChildOfType(MarkdownElementTypes.INLINE_LINK) ?: node
                } else {
                    node
                }
                val url = if (node.type == MarkdownElementTypes.AUTOLINK) {
                    text.trim('<', '>')
                } else {
                    val dest = linkNode.findChildOfType(MarkdownElementTypes.LINK_DESTINATION)
                    dest?.getTextInNode(source)?.toString()?.trim('<', '>') ?: text
                }
                val label = if (node.type == MarkdownElementTypes.AUTOLINK) {
                    url
                } else {
                    val linkText = linkNode.findChildOfType(MarkdownElementTypes.LINK_TEXT)
                    linkText?.getTextInNode(source)?.toString()?.trim('[', ']')?.trimStart('!')
                        ?: url
                }
                if (url.startsWith("attachment:")) {
                    // The composer inserts attachment:N references; never emit
                    // a broken URL. Show the file name (or the alt text) instead.
                    val name = attachmentResolver(url) ?: label.ifBlank { "attachment" }
                    builder.withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) {
                        append("\uD83D\uDCCE $name")
                    }
                } else {
                    builder.withLink(LinkAnnotation.Url(url)) { append(label) }
                }
            }
            MarkdownElementTypes.UNORDERED_LIST, MarkdownElementTypes.ORDERED_LIST -> {
                appendChildren(builder, node, source, attachmentResolver)
                builder.append("\n")
            }
            MarkdownElementTypes.LIST_ITEM -> {
                builder.append("• ")
                appendChildren(builder, node, source, attachmentResolver)
                builder.append("\n")
            }
            MarkdownElementTypes.BLOCK_QUOTE -> {
                appendChildren(builder, node, source, attachmentResolver)
                builder.append("\n")
            }
            MarkdownTokenTypes.HORIZONTAL_RULE -> builder.append("\n———\n")
            MarkdownTokenTypes.EOL -> builder.append("\n")
            else -> {
                if (node.children.isEmpty()) {
                    appendLeafText(builder, node.getTextInNode(source).toString(), attachmentResolver)
                } else {
                    appendChildren(builder, node, source, attachmentResolver)
                }
            }
        }
    }

    /**
     * The markdown parser leaves image syntax (`![alt](attachment:N)`) as a
     * plain text node in this flavour; replace those references with the same
     * attachment label used for normal links.
     */
    private val IMAGE_ATTACHMENT = Regex("""!?\[([^\]]*)]\((attachment:[^)]+)\)""")

    private fun appendLeafText(
        builder: AnnotatedString.Builder,
        text: String,
        attachmentResolver: (String) -> String?,
    ) {
        var last = 0
        for (match in IMAGE_ATTACHMENT.findAll(text)) {
            builder.append(text.substring(last, match.range.first))
            val label = match.groupValues[1]
            val ref = match.groupValues[2]
            val name = attachmentResolver(ref) ?: label.ifBlank { "attachment" }
            builder.withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) {
                append("\uD83D\uDCCE $name")
            }
            last = match.range.last + 1
        }
        builder.append(text.substring(last))
    }
}

/**
 * Resolves `attachment:N` against the current message and
 * `attachment:<message_id>:N` against another message of the conversation.
 * Returns the file name, or null when the reference cannot be resolved.
 */
internal fun attachmentRefName(
    ref: String,
    messageId: String,
    attachments: List<AttachmentUi>,
    otherMessage: (String) -> List<AttachmentUi>? = { null },
): String? {
    if (!ref.startsWith("attachment:")) return null
    val parts = ref.removePrefix("attachment:").split(":")
    val index = parts.lastOrNull()?.toIntOrNull() ?: return null
    val targetId = if (parts.size >= 2) parts.first() else messageId
    val list = if (targetId == messageId) attachments else otherMessage(targetId)
    return list?.getOrNull(index)?.name
}
