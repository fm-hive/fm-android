package lan.fmnode.android.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import lan.fmnode.android.R
import lan.fmnode.android.network.NetworkSession

/** Localized labels for the share header. */
data class ShareLabels(
    val echo: String,
    val from: String,
    val date: String,
    val netmailEcho: String,
)

object MessageShare {
    /** Markdown text shared for a message (#39). */
    fun text(message: MessageUi, labels: ShareLabels): String {
        val echo = if (message.echoName == NetworkSession.NETMAIL_ECHO) {
            labels.netmailEcho
        } else {
            message.echoName
        }
        val builder = StringBuilder()
        builder.append("**${labels.echo}:** ").append(echo).append("\n")
        builder.append("**${labels.from}:** ")
            .append(message.senderName.ifBlank { message.senderAddress })
            .append(" (").append(message.senderAddress).append(")\n")
        builder.append("**${labels.date}:** ").append(formatTimestamp(message.timestamp)).append("\n")
        if (message.subject.isNotBlank()) {
            builder.append("\n# ").append(message.subject).append("\n")
        }
        if (message.body.isNotBlank()) {
            builder.append("\n").append(message.body.trim()).append("\n")
        }
        if (message.tagline.isNotBlank()) {
            builder.append("\n").append(message.tagline).append("\n")
        }
        if (message.tearline.isNotBlank()) {
            builder.append("--- ").append(message.tearline).append("\n")
        }
        return builder.toString().trimEnd()
    }

    fun shareText(context: Context, message: MessageUi, labels: ShareLabels) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, message.subject.ifBlank { labels.echo })
            putExtra(Intent.EXTRA_TEXT, text(message, labels))
        }
        context.startActivity(Intent.createChooser(intent, labels.echo))
    }

    /** Renders a message card as a PNG and sends it through the system share sheet. */
    fun shareImage(
        context: Context,
        message: MessageUi,
        width: Int,
        netmailLabel: String = "Netmail",
    ) {
        val bitmap = renderImage(message, width, netmailLabel)
        val dir = File(context.cacheDir, "shares").apply { mkdirs() }
        val file = File(dir, "message-${message.messageId.hashCode()}-$width.png")
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, message.subject.ifBlank { "FM" }))
    }

    /**
     * Canvas renderer for a share image: header, subject, body, tagline and all
     * attachments at the content width (images are scaled to [width]).
     */
    fun renderImage(
        message: MessageUi,
        width: Int,
        netmailLabel: String = "Netmail",
    ): Bitmap {
        val safeWidth = width.coerceAtLeast(480)
        val scale = safeWidth / 1080f
        val padding = (48f * scale).toInt().coerceAtLeast(16)
        val contentWidth = (safeWidth - padding * 2).coerceAtLeast(100)
        val items = mutableListOf<DrawItem>()
        val headerPaint = paint(28f * scale, Color.DKGRAY, mono = false)
        val subjectPaint = paint(44f * scale, Color.BLACK, mono = false, bold = true)
        val bodyPaint = paint(32f * scale, Color.BLACK, mono = false)
        val smallPaint = paint(26f * scale, Color.DKGRAY, mono = false)

        fun addText(text: String, paint: TextPaint) {
            if (text.isBlank()) return
            val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, contentWidth)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0f, 1.18f)
                .setIncludePad(false)
                .build()
            items += DrawItem(layout = layout, height = layout.height)
        }

        val echo = if (message.echoName == NetworkSession.NETMAIL_ECHO) {
            netmailLabel
        } else {
            message.echoName
        }
        addText(
            "${echo} · ${message.senderName.ifBlank { message.senderAddress }} · " +
                formatTimestamp(message.timestamp),
            headerPaint,
        )
        if (message.subject.isNotBlank()) addText(message.subject, subjectPaint)

        fun addImage(attachment: AttachmentUi) {
            val image = if (isImage(attachment)) {
                decodeDataUrl(attachment.dataUrl)?.asAndroidBitmap()
            } else {
                null
            }
            if (image != null && image.width > 0 && image.height > 0) {
                val imageHeight = (contentWidth * image.height / image.width.toFloat())
                    .toInt()
                    .coerceAtLeast(1)
                val scaled = if (image.width == contentWidth && image.height == imageHeight) {
                    image
                } else {
                    Bitmap.createScaledBitmap(image, contentWidth, imageHeight, true)
                }
                items += DrawItem(bitmap = scaled, height = imageHeight + (12f * scale).toInt())
            } else {
                addText("📎 ${attachment.name}", smallPaint)
            }
        }

        // Render attachment references at their position in the body; only
        // unreferenced attachments go into the gallery at the end.
        val referenced = mutableSetOf<Int>()
        for (block in messageBodyBlocks(message.body, message.messageId, message.attachments)) {
            if (block.text != null) {
                addText(stripMarkdown(block.text), bodyPaint)
                continue
            }
            val attachment = block.attachment
            if (attachment != null) {
                val index = message.attachments.indexOfFirst { it == attachment }
                if (index >= 0) referenced += index
                if (isImage(attachment)) {
                    addImage(attachment)
                } else {
                    addText("📎 ${attachment.name}", smallPaint)
                }
            } else {
                addText("📎 ${block.label ?: "attachment"}", smallPaint)
            }
        }
        if (message.tagline.isNotBlank()) addText(message.tagline, smallPaint)
        if (message.tearline.isNotBlank()) addText("--- ${message.tearline}", smallPaint)

        // Attachments without an inline reference still belong to the card.
        message.attachments.forEachIndexed { index, attachment ->
            if (index !in referenced) addImage(attachment)
        }

        val spacing = (18f * scale).toInt()
        val contentHeight = padding * 2 + items.sumOf { it.height + spacing }
        val height = contentHeight.coerceAtMost(20_000)
        val bitmap = Bitmap.createBitmap(safeWidth, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        var y = padding
        for (item in items) {
            if (y >= height) break
            item.layout?.let { layout ->
                canvas.save()
                canvas.translate(padding.toFloat(), y.toFloat())
                layout.draw(canvas)
                canvas.restore()
            }
            item.bitmap?.let { image ->
                canvas.drawBitmap(image, padding.toFloat(), y.toFloat(), null)
            }
            y += item.height + spacing
        }
        return bitmap
    }

    private fun paint(size: Float, color: Int, mono: Boolean, bold: Boolean = false): TextPaint =
        TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            this.color = color
            typeface = Typeface.create(
                if (mono) Typeface.MONOSPACE else Typeface.SANS_SERIF,
                if (bold) Typeface.BOLD else Typeface.NORMAL,
            )
        }

    private fun stripMarkdown(text: String): String = text
        .replace(Regex("!\\[([^]]*)]\\([^)]*\\)"), "$1")
        .replace(Regex("\\[([^]]*)]\\([^)]*\\)"), "$1")
        .replace(Regex("(?m)^#{1,6}\\s*"), "")
        .replace(Regex("(?m)^>\\s?"), "")
        .replace("**", "")
        .replace("__", "")
        .replace("`", "")

    private data class DrawItem(
        val layout: StaticLayout? = null,
        val bitmap: Bitmap? = null,
        val height: Int,
    )

}

/** Share dialog: Text or Image; image width is chosen here (#39). */
@Composable
fun MessageShareDialog(
    message: MessageUi,
    labels: ShareLabels,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var mode by remember { mutableStateOf("") }
    var imageWidth by remember { mutableIntStateOf(1080) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.share_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = {
                        MessageShare.shareText(context, message, labels)
                        onDismiss()
                    }) {
                        Text(stringResource(R.string.share_text))
                    }
                    TextButton(onClick = { mode = "image" }) {
                        Text(stringResource(R.string.share_image))
                    }
                }
                if (mode == "image") {
                    Text(
                        text = stringResource(R.string.share_image_width),
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(720, 1080, 1440).forEach { width ->
                            FilterChip(
                                selected = imageWidth == width,
                                onClick = { imageWidth = width },
                                label = { Text("$width px") },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (mode == "image") {
                TextButton(
                    onClick = {
                        runCatching {
                            MessageShare.shareImage(
                                context,
                                message,
                                imageWidth,
                                netmailLabel = labels.netmailEcho,
                            )
                        }
                            .onFailure {
                                android.widget.Toast.makeText(
                                    context,
                                    R.string.share_failed,
                                    android.widget.Toast.LENGTH_SHORT,
                                ).show()
                            }
                        onDismiss()
                    },
                ) {
                    Text(stringResource(R.string.share))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}
