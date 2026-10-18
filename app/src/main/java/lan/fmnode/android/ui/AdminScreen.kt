package lan.fmnode.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import lan.fmnode.android.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminScreen(
    nodes: List<NodeUi>,
    loading: Boolean,
    mergeUrl: String,
    mergeStatus: String?,
    mergeError: UiError?,
    excommTarget: String,
    excommReason: String,
    excommSending: Boolean,
    excommStatus: String?,
    excommError: UiError?,
    mergeMessages: List<ProtocolUi>,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onMergeUrl: (String) -> Unit,
    onScanPeer: () -> Unit,
    onStartMerge: () -> Unit,
    onExcommTarget: (String) -> Unit,
    onExcommReason: (String) -> Unit,
    onSubmitExcomm: () -> Unit,
    onOpenRemoveEcho: () -> Unit,
    onOpenPolicyEdit: () -> Unit,
) {
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.admin_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    if (loading) {
                        CircularProgressIndicator(modifier = Modifier.width(20.dp).height(20.dp))
                        Spacer(Modifier.width(8.dp))
                    }
                    OutlinedButton(onClick = onRefresh) {
                        Text(stringResource(R.string.refresh))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(12.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.merge_title), style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = mergeUrl,
                onValueChange = onMergeUrl,
                label = { Text(stringResource(R.string.merge_peer_invite)) },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onStartMerge, enabled = !loading && mergeUrl.isNotBlank()) {
                    Text(stringResource(R.string.merge_start))
                }
                OutlinedButton(onClick = onScanPeer) {
                    Text(stringResource(R.string.scan_qr))
                }
            }
            mergeStatus?.let {
                Text(it, color = MaterialTheme.colorScheme.primary)
            }
            mergeError?.let {
                Text(errorText(it), color = MaterialTheme.colorScheme.error)
            }

            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.excomm_title), style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = excommTarget,
                onValueChange = onExcommTarget,
                label = { Text(stringResource(R.string.excomm_target)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = excommReason,
                onValueChange = onExcommReason,
                label = { Text(stringResource(R.string.excomm_reason)) },
                modifier = Modifier.fillMaxWidth(),
            )
            Button(onClick = onSubmitExcomm, enabled = !excommSending && excommTarget.isNotBlank()) {
                Text(stringResource(R.string.excomm_submit))
            }
            excommStatus?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            excommError?.let { Text(errorText(it), color = MaterialTheme.colorScheme.error) }

            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onOpenRemoveEcho) {
                    Text(stringResource(R.string.remove_echo_title))
                }
                OutlinedButton(onClick = onOpenPolicyEdit) {
                    Text(stringResource(R.string.change_policy_title))
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.merge_sessions_title), style = MaterialTheme.typography.titleMedium)
            if (mergeMessages.isEmpty()) {
                Text(stringResource(R.string.merge_sessions_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                mergeMessages.take(10).forEach { message ->
                    Text(
                        text = "${message.echoName}: ${message.title}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.nodes_title), style = MaterialTheme.typography.titleMedium)
            if (nodes.isEmpty()) {
                Text(
                    text = stringResource(R.string.nodes_empty),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(nodes, key = { it.address }) { node ->
                        Card(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(node.address, fontWeight = FontWeight.SemiBold)
                                    if (node.url.isNotBlank()) {
                                        Text(
                                            text = node.url,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                if (node.isRecoverNode) {
                                    AssistChip(onClick = {}, label = { Text(stringResource(R.string.node_recover)) })
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
