package lan.fmnode.android.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Checkbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import lan.fmnode.android.R

/**
 * Reply banner above the subject: the author and a button that removes the
 * quoted text and the `reply_to_message_id`.
 */
@Composable
fun ReplyBanner(label: String, onClear: () -> Unit) {
    if (label.isBlank()) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "↩ ${stringResource(R.string.fm_editor_replyingto)} $label",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onClear) {
            Text(stringResource(R.string.fm_app_cancel))
        }
    }
}

/** Warning/blocking banner for policy and rate-limit problems. */
@Composable
fun MessageLimitWarnings(issues: List<MessageLimitIssue>) {
    if (issues.isEmpty()) return
    val blocking = issues.any { it.blocking }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        issues.forEach { issue ->
            Text(
                text = limitIssueText(issue),
                style = MaterialTheme.typography.bodySmall,
                color = if (blocking) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.tertiary
                },
            )
        }
        if (blocking) {
            Text(
                text = stringResource(R.string.limit_blocked_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun limitIssueText(issue: MessageLimitIssue): String = when (issue) {
    is MessageLimitIssue.AttachmentTooLarge -> stringResource(
        R.string.limit_attachment_too_large,
        issue.name,
        sizeText(issue.size),
        sizeText(issue.limit),
    )
    is MessageLimitIssue.TooManyAttachments -> stringResource(
        R.string.limit_too_many_attachments,
        issue.count,
        issue.limit,
    )
    is MessageLimitIssue.MessageTooLarge -> stringResource(
        R.string.limit_message_too_large,
        sizeText(issue.size),
        sizeText(issue.limit),
    )
    is MessageLimitIssue.RateLimitExceeded -> {
        val kind = when (issue.kind) {
            "echomail" -> stringResource(R.string.limit_kind_echomail)
            "netmail" -> stringResource(R.string.limit_kind_netmail)
            "vote" -> stringResource(R.string.limit_kind_vote)
            "poster" -> stringResource(R.string.limit_kind_poster)
            "reaction" -> stringResource(R.string.limit_kind_reaction)
            else -> issue.kind
        }
        if (issue.blocking) {
            "$kind: " + stringResource(R.string.limit_rate_exceeded, issue.used, issue.limit)
        } else {
            "$kind: " + stringResource(R.string.limit_rate_override)
        }
    }
}

private fun sizeText(bytes: Long): String =
    formatFileSize(bytes.coerceIn(0, Int.MAX_VALUE.toLong()).toInt())

/** Resolution + "remember" dialog shown when picked files include images. */
@Composable
fun ImageResizeDialog(
    maxDimension: Int,
    remember: Boolean,
    onDimension: (Int) -> Unit,
    onRemember: (Boolean) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val options = listOf(640, 1280, 1920, 2560)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.image_resize_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.image_resize_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                options.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { size ->
                            FilterChip(
                                selected = maxDimension == size,
                                onClick = { onDimension(size) },
                                label = { Text("$size px") },
                            )
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = remember, onCheckedChange = onRemember)
                    Text(
                        text = stringResource(R.string.image_resize_remember),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.image_resize_apply)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

/**
 * Markdown body editor with a preview and the syntax help used by fm-app.
 * The caller gives the height via [modifier] (usually `weight(1f)`).
 */
@Composable
fun ComposerBodyEditor(
    body: String,
    onBody: (String) -> Unit,
    attachments: List<AttachmentUi>,
    modifier: Modifier = Modifier,
) {
    var preview by remember { mutableStateOf(false) }
    var help by remember { mutableStateOf(false) }
    if (help) {
        MarkdownHelpDialog(onDismiss = { help = false })
    }
    Column(modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { help = true }) {
                Icon(
                    Icons.AutoMirrored.Filled.HelpOutline,
                    contentDescription = stringResource(R.string.fm_markdownhelp_title),
                )
            }
            Text(
                text = "Markdown",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { preview = !preview }) {
                Text(
                    stringResource(
                        if (preview) R.string.fm_editor_edit else R.string.fm_editor_preview,
                    ),
                )
            }
        }
        if (preview) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outline,
                        shape = MaterialTheme.shapes.extraSmall,
                    )
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp),
            ) {
                MessageBody(
                    body = body,
                    messageId = "",
                    attachments = attachments,
                    maxImageHeight = 240.dp,
                )
            }
        } else {
            OutlinedTextField(
                value = body,
                onValueChange = onBody,
                label = { Text(stringResource(R.string.fm_editor_body)) },
                placeholder = { Text(stringResource(R.string.fm_editor_bodyplaceholder)) },
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
        }
    }
}

@Composable
private fun MarkdownHelpDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.fm_markdownhelp_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                MarkdownHelpRow("# Текст", stringResource(R.string.fm_markdownhelp_header))
                MarkdownHelpRow("**жирный**", stringResource(R.string.fm_markdownhelp_bold))
                MarkdownHelpRow("*курсив*", stringResource(R.string.fm_markdownhelp_italic))
                MarkdownHelpRow("[текст](url)", stringResource(R.string.fm_markdownhelp_link))
                MarkdownHelpRow("- пункт", stringResource(R.string.fm_markdownhelp_list))
                MarkdownHelpRow("1. пункт", stringResource(R.string.fm_markdownhelp_orderedlist))
                MarkdownHelpRow("`код`", stringResource(R.string.fm_markdownhelp_code))
                MarkdownHelpRow("> цитата", stringResource(R.string.fm_markdownhelp_quote))
                MarkdownHelpRow("![alt](url)", stringResource(R.string.fm_markdownhelp_image))
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.fm_markdownhelp_attachments),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) }
        },
    )
}

@Composable
private fun MarkdownHelpRow(syntax: String, meaning: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = syntax,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = meaning,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
