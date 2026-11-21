package lan.fmnode.android.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * One block of a message body: either Markdown text or an attachment reference.
 * Used by the Compose message feed and by the share-image renderer so inline
 * images stay at the same position in both.
 */
internal data class MessageBodyBlock(
    val text: String? = null,
    val attachment: AttachmentUi? = null,
    /** Display name for an unresolved/scoped attachment reference. */
    val label: String? = null,
)

/** Splits a body into text and `attachment:N` blocks, in source order. */
internal fun messageBodyBlocks(
    body: String,
    messageId: String,
    attachments: List<AttachmentUi>,
    otherAttachments: (String) -> List<AttachmentUi>? = { null },
): List<MessageBodyBlock> {
    val matches = attachmentRefs(body)
    if (matches.isEmpty()) return listOf(MessageBodyBlock(text = body))
    val blocks = mutableListOf<MessageBodyBlock>()
    var cursor = 0
    for (match in matches) {
        val before = body.substring(cursor, match.range.first)
        if (before.isNotEmpty()) blocks += MessageBodyBlock(text = before)
        val attachment = resolveAttachment(
            match.groupValues[2],
            messageId,
            attachments,
            otherAttachments,
        )
        blocks += MessageBodyBlock(
            attachment = attachment,
            label = attachment?.name ?: match.groupValues[1].ifBlank { "attachment" },
        )
        cursor = match.range.last + 1
    }
    val tail = body.substring(cursor)
    if (tail.isNotEmpty()) blocks += MessageBodyBlock(text = tail)
    return blocks
}

/**
 * Renders a message body with inline attachment images. Text segments keep the
 * usual Markdown formatting; `attachment:N` references become full-width
 * images (or a file label for non-images) at their position in the body.
 */
@Composable
fun MessageBody(
    body: String,
    messageId: String,
    attachments: List<AttachmentUi>,
    modifier: Modifier = Modifier,
    maxImageHeight: Dp = 260.dp,
    otherAttachments: (String) -> List<AttachmentUi>? = { null },
    onOpenAttachment: (AttachmentUi) -> Unit = {},
) {
    val blocks = remember(body, messageId, attachments) {
        messageBodyBlocks(body, messageId, attachments, otherAttachments)
    }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (block in blocks) {
            val attachment = block.attachment
            if (block.text != null) {
                if (block.text.isNotBlank()) {
                    Text(
                        text = MarkdownRenderer.render(block.text),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                continue
            }
            if (attachment != null && isImage(attachment)) {
                val bitmap = remember(attachment.dataUrl) { decodeDataUrl(attachment.dataUrl) }
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = attachment.name,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = maxImageHeight)
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { onOpenAttachment(attachment) },
                        contentScale = ContentScale.Fit,
                    )
                } else {
                    AttachmentLabel(attachment.name)
                }
            } else {
                AttachmentLabel(attachment?.name ?: block.label ?: "attachment")
            }
        }
    }
}

@Composable
private fun AttachmentLabel(name: String) {
    Text(
        text = "📎 $name",
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(vertical = 2.dp),
    )
}

/** Attachment indices referenced in [body] for the current message. */
internal fun referencedAttachmentIndices(body: String, messageId: String): Set<Int> {
    val out = mutableSetOf<Int>()
    for (match in attachmentRefs(body)) {
        val parts = match.groupValues[2].removePrefix("attachment:").split(":")
        val index = parts.lastOrNull()?.toIntOrNull() ?: continue
        val target = if (parts.size >= 2) parts.first() else messageId
        if (target == messageId) out += index
    }
    return out
}

internal val ATTACHMENT_REF = Regex("""!?\[([^\]]*)]\((attachment:[^)]+)\)""")

private fun attachmentRefs(body: String): List<MatchResult> =
    ATTACHMENT_REF.findAll(body).toList()

internal fun resolveAttachment(
    ref: String,
    messageId: String,
    attachments: List<AttachmentUi>,
    otherAttachments: (String) -> List<AttachmentUi>? = { null },
): AttachmentUi? {
    val parts = ref.removePrefix("attachment:").split(":")
    val index = parts.lastOrNull()?.toIntOrNull() ?: return null
    val target = if (parts.size >= 2) parts.first() else messageId
    val list = if (target == messageId) attachments else otherAttachments(target)
    return list?.getOrNull(index)
}

internal fun isImage(attachment: AttachmentUi): Boolean =
    attachment.type.startsWith("image/") || attachment.dataUrl.startsWith("data:image/")
