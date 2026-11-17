package lan.fmnode.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "${group.name} (${group.echoes.size})",
                                modifier = Modifier.weight(1f),
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            TextButton(onClick = { onDelete(group.id) }) { Text("✕") }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.echo_groups_select), fontWeight = FontWeight.SemiBold)
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
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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
