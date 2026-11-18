package lan.fmnode.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import lan.fmnode.android.R

/** Draft of a new vote poster: a question and at least two options. */
@Composable
fun VoteComposeDialog(
    state: VoteComposeState,
    onQuestion: (String) -> Unit,
    onVariant: (Int, String) -> Unit,
    onAddVariant: () -> Unit,
    onRemoveVariant: (Int) -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!state.sending) onDismiss() },
        title = { Text(stringResource(R.string.vote_create_title)) },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = state.question,
                    onValueChange = onQuestion,
                    label = { Text(stringResource(R.string.vote_create_question)) },
                    enabled = !state.sending,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 90.dp),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.vote_create_options),
                    style = MaterialTheme.typography.labelLarge,
                )
                state.variants.forEachIndexed { index, value ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = value,
                            onValueChange = { onVariant(index, it) },
                            enabled = !state.sending,
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        if (state.variants.size > 2) {
                            IconButton(onClick = { onRemoveVariant(index) }, enabled = !state.sending) {
                                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.delete))
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                }
                TextButton(onClick = onAddVariant, enabled = !state.sending) {
                    Text(stringResource(R.string.vote_create_add_option))
                }
                state.error?.let {
                    Text(errorText(it), color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onSubmit, enabled = !state.sending) {
                Text(stringResource(R.string.vote_create_submit))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !state.sending) { Text(stringResource(R.string.cancel)) }
        },
    )
}

/**
 * Shown before subscribing to a conference that has rules: joining requires the
 * user's signature over those rules, so they are displayed first.
 */
@Composable
fun RulesPromptDialog(
    prompt: RulesPromptUi,
    onAccept: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.rules_accept_title) + ": " + prompt.echoName) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text(prompt.rulesText, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.rules_accept_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onAccept) { Text(stringResource(R.string.rules_accept_action)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

/** Proposes new rules for a conference; the same protocol renames it. */
@Composable
fun RulesEditDialog(
    state: RulesEditState,
    onRules: (String) -> Unit,
    onName: (String) -> Unit,
    onAllChunks: (Boolean) -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!state.sending) onDismiss() },
        title = { Text(stringResource(R.string.rules_edit_title) + ": " + state.echoName) },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = state.rulesJson,
                    onValueChange = onRules,
                    label = { Text(stringResource(R.string.rules_edit_rules)) },
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = state.newName,
                    onValueChange = onName,
                    label = { Text(stringResource(R.string.rules_edit_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = state.allChunksOnly, onCheckedChange = onAllChunks)
                    Text(
                        text = stringResource(R.string.rules_edit_all_chunks),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                state.status?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                state.error?.let { Text(errorText(it), color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = onSubmit, enabled = !state.sending) {
                Text(stringResource(R.string.rules_edit_submit))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !state.sending) { Text(stringResource(R.string.cancel)) }
        },
    )
}

/**
 * User-defined groups of conferences (#366): create groups, pick conferences and
 * move them into a group (or out of every group).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EchoGroupsDialog(
    groups: List<EchoGroupUi>,
    echoes: List<EchoUi>,
    draftName: String,
    selection: Set<String>,
    error: UiError?,
    onDraftName: (String) -> Unit,
    onCreate: () -> Unit,
    onDelete: (String) -> Unit,
    onToggle: (String) -> Unit,
    onAssign: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.echo_groups_title)) },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = draftName,
                        onValueChange = onDraftName,
                        label = { Text(stringResource(R.string.echo_groups_name)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.height(0.dp))
                    TextButton(onClick = onCreate) { Text(stringResource(R.string.echo_groups_create)) }
                }
                if (groups.isEmpty()) {
                    Text(
                        text = stringResource(R.string.echo_groups_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    groups.forEach { group ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable(enabled = selection.isNotEmpty()) {
                                onAssign(group.id)
                            },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = "${group.name} (${group.echoes.size})",
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (selection.isNotEmpty()) {
                                    Text(
                                        text = stringResource(R.string.echo_groups_move_selected),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                            TextButton(onClick = { onDelete(group.id) }) { Text("✕") }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.echo_groups_select), fontWeight = FontWeight.SemiBold)
                Text(
                    text = stringResource(R.string.echo_groups_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                echoes.forEach { echo ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = echo.name in selection,
                            onCheckedChange = { onToggle(echo.name) },
                        )
                        Text(echo.name, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (selection.isNotEmpty()) {
                    Text(stringResource(R.string.echo_groups_move_to), fontWeight = FontWeight.SemiBold)
                    // A plain Row clipped the last groups; FlowRow keeps every
                    // target reachable with many or long group names (#28).
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        OutlinedButton(onClick = { onAssign(null) }) {
                            Text(stringResource(R.string.echo_groups_ungrouped))
                        }
                        groups.forEach { group ->
                            OutlinedButton(onClick = { onAssign(group.id) }) { Text(group.name) }
                        }
                    }
                }
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(errorText(it), color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
        },
    )
}

/** Renames the user on the node (`set_name`). */
@Composable
fun DisplayNameDialog(
    draft: String,
    saving: Boolean,
    error: UiError?,
    onChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(stringResource(R.string.display_name_change)) },
        text = {
            Column {
                OutlinedTextField(
                    value = draft,
                    onValueChange = onChange,
                    label = { Text(stringResource(R.string.display_name)) },
                    singleLine = true,
                    enabled = !saving,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(errorText(it), color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onSubmit, enabled = !saving) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !saving) { Text(stringResource(R.string.cancel)) }
        },
    )
}

/** Remote subscription to a channel hosted on another node (docs/proto/remote_subscribe.md). */
@Composable
fun RemoteSubscribeDialog(
    echo: String,
    host: String,
    sending: Boolean,
    status: String?,
    error: UiError?,
    onEcho: (String) -> Unit,
    onHost: (String) -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!sending) onDismiss() },
        title = { Text(stringResource(R.string.remote_subscribe_title)) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text(
                    text = stringResource(R.string.remote_subscribe_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = echo,
                    onValueChange = onEcho,
                    label = { Text(stringResource(R.string.remote_subscribe_channel)) },
                    singleLine = true,
                    enabled = !sending,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = host,
                    onValueChange = onHost,
                    label = { Text(stringResource(R.string.remote_subscribe_host)) },
                    singleLine = true,
                    enabled = !sending,
                    modifier = Modifier.fillMaxWidth(),
                )
                status?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.primary)
                }
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(errorText(it), color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onSubmit, enabled = !sending) {
                Text(stringResource(R.string.remote_subscribe_send))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !sending) {
                Text(stringResource(R.string.close))
            }
        },
    )
}

/** Admin appeal of a moderator warning/punishment (docs/proto/appeal.md). */
@Composable
fun AppealDialog(
    message: MessageUi,
    reason: String,
    sending: Boolean,
    status: String?,
    error: UiError?,
    onReason: (String) -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit,
) {
    val moderation = message.moderation
    AlertDialog(
        onDismissRequest = { if (!sending) onDismiss() },
        title = { Text(stringResource(R.string.appeal_title)) },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                Text(
                    text = stringResource(R.string.appeal_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                if (moderation != null) {
                    // The moderator authored the notice; the punished message only
                // names the victim, so showing its sender here labelled the victim
                // as the moderator.
                Text(
                    "${stringResource(R.string.appeal_moderator)}: " +
                        moderation.moderatorAddress.ifBlank { message.senderAddress },
                )
                    Text("${stringResource(R.string.appeal_target)}: ${moderation.targetAddress}")
                    Text("${stringResource(R.string.appeal_echo)}: ${message.echoName}")
                    Text("${stringResource(R.string.appeal_severity)}: ${moderation.severity}")
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = reason,
                    onValueChange = onReason,
                    label = { Text(stringResource(R.string.appeal_reason)) },
                    enabled = !sending,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp),
                )
                status?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.primary)
                }
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(errorText(it), color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onSubmit, enabled = !sending) {
                Text(stringResource(R.string.appeal_submit))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !sending) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}
