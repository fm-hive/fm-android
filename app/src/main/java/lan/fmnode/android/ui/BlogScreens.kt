package lan.fmnode.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import lan.fmnode.android.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlogScreen(
    blog: BlogUi,
    posts: List<BlogPostUi>,
    onBack: () -> Unit,
    onNewPost: () -> Unit,
    onEditPost: (BlogPostUi) -> Unit,
    onDeletePost: (String) -> Unit,
    onDeleteBlog: () -> Unit,
) {
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(blog.name, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(onClick = onNewPost) {
                        Icon(Icons.Default.Add, contentDescription = stringResource(R.string.fm_bloggor_newpost))
                    }
                    IconButton(onClick = onDeleteBlog) {
                        Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.fm_bloggor_delete))
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (blog.description.isNotBlank()) {
                Text(
                    text = blog.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            if (posts.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(stringResource(R.string.fm_bloggor_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                return@Scaffold
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(posts, key = { it.id }) { post ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth().padding(12.dp)) {
                            if (post.title.isNotBlank()) {
                                Text(post.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            }
                            if (post.body.isNotBlank()) {
                                Spacer(Modifier.height(4.dp))
                                Text(MarkdownRenderer.render(post.body), style = MaterialTheme.typography.bodyMedium)
                            }
                            if (post.attachments.isNotEmpty()) {
                                Spacer(Modifier.height(8.dp))
                                AttachmentsRow(post.attachments)
                            }
                            Spacer(Modifier.height(6.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    formatBlogTimestamp(post.timestamp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(onClick = { onEditPost(post) }) {
                                    Text(stringResource(R.string.fm_bloggor_edit))
                                }
                                IconButton(onClick = { onDeletePost(post.id) }) {
                                    Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.fm_bloggor_delete))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlogComposeScreen(
    state: BlogComposeState,
    onBack: () -> Unit,
    onTitle: (String) -> Unit,
    onBody: (String) -> Unit,
    onAttachFiles: () -> Unit,
    onRemoveAttachment: (Int) -> Unit,
    onSave: () -> Unit,
) {
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.fm_bloggor_newpost)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(onClick = onAttachFiles) {
                        Icon(Icons.Default.AttachFile, contentDescription = stringResource(R.string.attach_file))
                    }
                    TextButton(onClick = onSave, enabled = !state.saving) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.fm_bloggor_save))
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(12.dp)) {
            OutlinedTextField(
                value = state.title,
                onValueChange = onTitle,
                label = { Text(stringResource(R.string.subject)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = state.body,
                onValueChange = onBody,
                label = { Text(stringResource(R.string.fm_bloggor_body)) },
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
            if (state.attachments.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                ComposerAttachments(state.attachments, onRemoveAttachment)
            }
            state.error?.let {
                Spacer(Modifier.height(8.dp))
                Text(errorText(it), color = MaterialTheme.colorScheme.error)
            }
            if (state.saving) {
                Spacer(Modifier.height(8.dp))
                CircularProgressIndicator(Modifier.width(24.dp).height(24.dp))
            }
        }
    }
}

private fun formatBlogTimestamp(millis: Long): String =
    SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(if (millis < 10_000_000_000L) millis * 1000 else millis))
