package lan.fmnode.android

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import lan.fmnode.android.ui.AppViewModel
import lan.fmnode.android.ui.FmRoot
import lan.fmnode.android.ui.ScanPurpose
import lan.fmnode.android.ui.theme.FmTheme

class MainActivity : ComponentActivity() {
    /** Real file name from the document provider (content URIs end with "image:37"). */
    private fun displayName(uri: Uri): String? = runCatching {
        contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
    }.getOrNull()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as FmApplication).container
        setContent {
            FmTheme {
                val viewModel: AppViewModel = viewModel(
                    factory = AppViewModel.factory(container.networkManager, container.session),
                )
                val state by viewModel.state.collectAsStateWithLifecycle()

                val importLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocument(),
                ) { uri: Uri? ->
                    if (uri != null) {
                        val text = runCatching {
                            contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                        }.getOrNull()
                        viewModel.onImportFile(text)
                    }
                }
                val attachLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenMultipleDocuments(),
                ) { uris: List<Uri> ->
                    if (uris.isNotEmpty()) {
                        val files = uris.mapNotNull { uri ->
                            runCatching {
                                val name = displayName(uri) ?: uri.lastPathSegment?.substringAfterLast('/') ?: "file"
                                val type = contentResolver.getType(uri) ?: "application/octet-stream"
                                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                                    ?: return@runCatching null
                                Triple(name, type, bytes)
                            }.getOrNull()
                        }
                        if (state.screen == lan.fmnode.android.ui.AppScreen.BlogCompose) {
                            viewModel.addBlogAttachments(files)
                        } else {
                            viewModel.addAttachments(files)
                        }
                    }
                }
                var pendingExport by remember { mutableStateOf<String?>(null) }
                val exportLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.CreateDocument("application/json"),
                ) { uri: Uri? ->
                    val data = pendingExport
                    if (uri != null && data != null) {
                        runCatching {
                            contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(data) }
                        }
                    }
                    pendingExport = null
                }

                FmRoot(
                    state = state,
                    onUnlock = viewModel::unlock,
                    onLock = viewModel::lock,
                    onRequestLogout = viewModel::requestLogout,
                    onConfirmLogout = viewModel::confirmLogout,
                    onCancelLogout = viewModel::cancelLogout,
                    onOpenAddNetwork = viewModel::openAddNetwork,
                    onCloseAddNetwork = viewModel::closeAddNetwork,
                    onUrlChange = viewModel::setAddUrl,
                    onDisplayNameChange = viewModel::setDisplayName,
                    onPasswordChange = viewModel::setAddPassword,
                    onPasswordConfirmChange = viewModel::setAddPasswordConfirm,
                    onFetchPolicy = viewModel::fetchPolicy,
                    onRegister = viewModel::register,
                    onSelectProfile = viewModel::selectProfile,
                    onDeleteProfile = viewModel::deleteProfile,
                    onBackFromNetworks = viewModel::backFromNetworks,
                    onRefresh = viewModel::refresh,
                    onOpenNetworks = viewModel::openNetworks,
                    onSelectEcho = viewModel::selectEcho,
                    onBackFromEcho = viewModel::backFromEcho,
                    onBackFromBlog = viewModel::backFromBlog,
                    onNewBlogPost = { viewModel.openBlogPostEditor() },
                    onEditBlogPost = viewModel::openBlogPostEditor,
                    onDeleteBlogPost = viewModel::deleteBlogPost,
                    onDeleteBlog = viewModel::deleteSelectedBlog,
                    onBlogTitle = viewModel::setBlogPostTitle,
                    onBlogBody = viewModel::setBlogPostBody,
                    onSaveBlogPost = viewModel::saveBlogPost,
                    onBackFromBlogCompose = viewModel::backFromBlogCompose,
                    onOpenCompose = viewModel::openCompose,
                    onBackFromCompose = viewModel::backFromCompose,
                    onComposeSubject = viewModel::setComposeSubject,
                    onComposeBody = viewModel::setComposeBody,
                    onSendCompose = viewModel::sendCompose,
                    onOpenNetmail = viewModel::openNetmail,
                    onOpenSettings = viewModel::openSettings,
                    onNetmailFolder = viewModel::selectNetmailFolder,
                    onOpenNetmailFolderDialog = viewModel::openNetmailFolderDialog,
                    onCloseNetmailFolderDialog = viewModel::closeNetmailFolderDialog,
                    onNetmailFolderName = viewModel::setNetmailFolderName,
                    onCreateNetmailFolder = viewModel::createNetmailFolder,
                    onDeleteNetmailFolder = viewModel::deleteNetmailFolder,
                    onOpenNetmailMove = viewModel::openNetmailMove,
                    onCloseNetmailMove = viewModel::closeNetmailMove,
                    onMoveNetmail = viewModel::moveNetmailToFolder,
                    onOpenNetmailMessage = viewModel::openNetmailMessage,
                    onBackFromNetmailDetail = viewModel::backFromNetmailDetail,
                    onOpenNetmailCompose = { viewModel.openNetmailCompose() },
                    onBackFromNetmailCompose = viewModel::backFromNetmailCompose,
                    onNetmailRecipient = viewModel::setNetmailRecipient,
                    onNetmailSubject = viewModel::setNetmailSubject,
                    onNetmailBody = viewModel::setNetmailBody,
                    onSendNetmail = viewModel::sendNetmailCompose,
                    onSelectEchoTab = viewModel::backFromEcho,
                    onDeleteActiveNetwork = viewModel::deleteActiveNetwork,
                    onImportKeys = { importLauncher.launch(arrayOf("application/json", "text/plain")) },
                    onImportPassword = viewModel::confirmImportPassword,
                    onCancelImport = viewModel::cancelImport,
                    onOpenSearch = viewModel::openSearch,
                    onCloseSearch = viewModel::closeSearch,
                    onSearchQuery = viewModel::setSearchQuery,
                    onOpenSearchResult = viewModel::openSearchResult,
                    onOpenProtocols = viewModel::openProtocols,
                    onCloseProtocols = viewModel::closeProtocols,
                    onCastVote = viewModel::castVote,
                    onOpenScanner = { viewModel.openScanner(ScanPurpose.AddNetwork) },
                    onCloseScanner = viewModel::closeScanner,
                    onQrScanned = viewModel::onQrScanned,
                    onAttachFiles = { attachLauncher.launch(arrayOf("*/*")) },
                    onRemoveAttachment = viewModel::removeAttachment,
                    onRemoveBlogAttachment = viewModel::removeBlogAttachment,
                    onOpenAdmin = viewModel::openAdmin,
                    onOpenRecovery = viewModel::openRecovery,
                    onRequestVoucher = viewModel::requestVoucher,
                    onCloseRecovery = viewModel::closeRecovery,
                    onRecoveryUrl = viewModel::setRecoveryUrl,
                    onSubmitRecovery = viewModel::submitRecovery,
                    onCloseAdmin = viewModel::closeAdmin,
                    onAdminRefresh = viewModel::loadNodes,
                    onAdminMergeUrl = viewModel::setAdminMergeUrl,
                    onStartMerge = viewModel::startMerge,
                    onScanPeer = { viewModel.openScanner(ScanPurpose.Merge) },
                    onCreateVoucher = viewModel::createVoucher,
                    onDismissVoucher = viewModel::dismissVoucher,
                    onReactMessage = viewModel::toggleReaction,
                    onOpenOutbox = viewModel::openOutbox,
                    onCloseOutbox = viewModel::closeOutbox,
                    onRetryOutbox = viewModel::retryOutbox,
                    onDeleteOutbox = viewModel::deleteOutboxEntry,
                    onOpenRefillDialog = viewModel::openRefillDialog,
                    onCloseRefillDialog = viewModel::closeRefillDialog,
                    onRefillDate = viewModel::setRefillDate,
                    onSendRefill = viewModel::sendRefill,
                    onToggleSelection = viewModel::toggleSelectionMode,
                    onToggleMessageSelection = viewModel::toggleMessageSelection,
                    onForwardSelected = viewModel::forwardSelected,
                    onOpenContacts = viewModel::openContacts,
                    onCloseContacts = viewModel::closeContacts,
                    onNewContactAddress = viewModel::setNewContactAddress,
                    onNewContactName = viewModel::setNewContactName,
                    onAddContact = viewModel::addContact,
                    onDeleteContact = viewModel::deleteContact,
                    onPickContact = viewModel::pickContact,
                    onForwardMessage = viewModel::forwardMessage,
                    onOpenPolicyViewer = viewModel::openPolicyViewer,
                    onClosePolicyViewer = viewModel::closePolicyViewer,
                    onOpenRemoveEcho = viewModel::openRemoveEchoDialog,
                    onCloseRemoveEcho = viewModel::closeRemoveEchoDialog,
                    onRemoveEchoName = viewModel::setRemoveEchoName,
                    onRemoveEchoReason = viewModel::setRemoveEchoReason,
                    onSubmitRemoveEcho = viewModel::submitRemoveEcho,
                    onOpenPolicyEdit = viewModel::openPolicyEditDialog,
                    onClosePolicyEdit = viewModel::closePolicyEditDialog,
                    onPolicyEditText = viewModel::setPolicyEditText,
                    onPolicyEditParams = viewModel::setPolicyEditParams,
                    onPolicyEditAllChunks = viewModel::setPolicyEditAllChunks,
                    onSubmitPolicyEdit = viewModel::submitPolicyEdit,
                    onOpenModeration = viewModel::openModeration,
                    onCloseModeration = viewModel::closeModeration,
                    onModerationSeverity = viewModel::setModerationSeverity,
                    onModerationDuration = viewModel::setModerationDuration,
                    onModerationReason = viewModel::setModerationReason,
                    onSubmitModeration = viewModel::submitModeration,
                    onRespondRefill = viewModel::respondRefill,
                    onVoteElection = viewModel::voteElection,
                    onCreateEchoDialog = viewModel::openCreateEchoDialog,
                    onCloseCreateEchoDialog = viewModel::closeCreateEchoDialog,
                    onCreateEchoName = viewModel::setCreateEchoName,
                    onCreateEchoDescription = viewModel::setCreateEchoDescription,
                    onCreateEchoType = viewModel::setCreateEchoType,
                    onCreateLocalEcho = viewModel::createLocalEcho,
                    onExcommTarget = viewModel::setExcommTarget,
                    onExcommReason = viewModel::setExcommReason,
                    onSubmitExcomm = viewModel::submitExcommunicate,
                    onOpenEchoBrowser = viewModel::openEchoBrowser,
                    onCloseEchoBrowser = viewModel::closeEchoBrowser,
                    onToggleSubscription = viewModel::toggleSubscription,
                    onAcceptPolicyResign = viewModel::acceptPolicyResign,
                    onCancelPolicyResign = viewModel::cancelPolicyResign,
                    onExportKeys = { password ->
                        viewModel.exportKeysJson(password)?.let { json ->
                            pendingExport = json
                            exportLauncher.launch("fm-keys.json")
                        }
                    },
                    onExportSettings = {
                        viewModel.exportSettingsJson()?.let { json ->
                            pendingExport = json
                            exportLauncher.launch("fm-settings.json")
                        }
                    },
                    onExportFull = {
                        viewModel.exportFullJson()?.let { json ->
                            pendingExport = json
                            exportLauncher.launch("fm-full-export.json")
                        }
                    },
                    onImportBackup = { importLauncher.launch(arrayOf("application/json", "text/plain")) },
                    onClearBackup = viewModel::clearBackupMessage,
                    onOpenMessage = viewModel::openMessage,
                    onBackFromMessage = viewModel::backFromMessage,
                    onChangeMessageView = viewModel::setMessageView,
                    onBackToRoot = { finish() },
                )
            }
        }
    }
}
