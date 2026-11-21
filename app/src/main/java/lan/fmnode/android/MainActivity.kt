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
import lan.fmnode.android.ui.EchoActionsCallbacks
import lan.fmnode.android.ui.FeedSettingsCallbacks
import lan.fmnode.android.ui.FmRoot
import lan.fmnode.android.ui.NetworkSettingsCallbacks
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
                    factory = AppViewModel.factory(
                        container.networkManager,
                        container.session,
                        container.passwordStore,
                        container.networkPreferences,
                        container.networkMonitor,
                    ),
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
                        val target = when (state.screen) {
                            lan.fmnode.android.ui.AppScreen.BlogCompose ->
                                lan.fmnode.android.ui.AttachmentTarget.Blog
                            lan.fmnode.android.ui.AppScreen.NetmailCompose ->
                                lan.fmnode.android.ui.AttachmentTarget.Netmail
                            else -> lan.fmnode.android.ui.AttachmentTarget.Echo
                        }
                        viewModel.prepareAttachments(files, target)
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
                    onRememberPassword = viewModel::setRememberPassword,
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
                    onAddRememberPassword = viewModel::setAddRememberPassword,
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
                    onComposeAnnouncement = viewModel::setComposeAnnouncement,
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
                    onRemoveNetmailAttachment = viewModel::removeNetmailAttachment,
                    onReplyEchoMessage = viewModel::replyToEchoMessage,
                    onReplyNetmailMessage = viewModel::replyToNetmailMessage,
                    onClearComposeReply = viewModel::clearComposeReply,
                    onClearNetmailReply = viewModel::clearNetmailReply,
                    onComposeTagline = viewModel::setComposeTagline,
                    onNetmailTagline = viewModel::setNetmailTagline,
                    onTagline = viewModel::setTagline,
                    feedSettings = FeedSettingsCallbacks(
                        onShowTearline = viewModel::setShowTearline,
                        onShowServiceMessages = viewModel::setShowServiceMessages,
                        onOpenLogs = viewModel::openLogs,
                        networkSettings = NetworkSettingsCallbacks(
                            proxy = state.proxy,
                            onMode = viewModel::setProxyMode,
                            onKind = viewModel::setProxyKind,
                            onHost = viewModel::setProxyHost,
                            onPort = viewModel::setProxyPort,
                            onSave = viewModel::saveProxySettings,
                        ),
                    ),
                    echoActions = EchoActionsCallbacks(
                        onDeleteEchoGroup = viewModel::deleteEchoGroup,
                        onDeleteEchoLocalCopy = viewModel::deleteEchoLocalCopy,
                    ),
                    onAllowSendingWithoutRateLimit = viewModel::setAllowSendingWithoutRateLimit,
                    onImageMaxDimension = viewModel::setImageMaxDimension,
                    onRememberImageDimension = viewModel::setRememberImageDimension,
                    onConfirmImageResize = viewModel::confirmImageResize,
                    onCancelImageResize = viewModel::cancelImageResize,
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
                    onOpenRemoteSubscribe = viewModel::openRemoteSubscribeDialog,
                    onRemoteSubscribeEcho = viewModel::setRemoteSubscribeEcho,
                    onRemoteSubscribeHost = viewModel::setRemoteSubscribeHost,
                    onSendRemoteSubscribe = viewModel::sendRemoteSubscribe,
                    onCloseRemoteSubscribe = viewModel::closeRemoteSubscribeDialog,
                    onOpenAppeal = viewModel::openAppeal,
                    onAppealReason = viewModel::setAppealReason,
                    onSubmitAppeal = viewModel::submitAppeal,
                    onCloseAppeal = viewModel::closeAppeal,
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
                    onPolicyEditField = viewModel::setPolicyEditField,
                    onPolicyEditRateLimit = viewModel::setPolicyEditRateLimit,
                    onPreviewPolicyDiff = viewModel::previewPolicyDiff,
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
                    onDismissSendErrors = viewModel::dismissSendErrors,
                    onDismissAddressNotice = viewModel::dismissAddressNotice,
                    onSelectEchoInNetwork = viewModel::selectEchoInNetwork,
                    onAcceptRules = viewModel::confirmRulesPrompt,
                    onCancelRules = viewModel::cancelRulesPrompt,
                    onOpenRulesEditor = viewModel::openRulesEditor,
                    onCloseRulesEditor = viewModel::closeRulesEditor,
                    onRulesEditJson = viewModel::setRulesEditJson,
                    onRulesEditName = viewModel::setRulesEditName,
                    onRulesEditAllChunks = viewModel::setRulesEditAllChunks,
                    onSubmitRulesEdit = viewModel::submitRulesEdit,
                    onOpenEchoGroups = viewModel::openEchoGroupsDialog,
                    onCloseEchoGroups = viewModel::closeEchoGroupsDialog,
                    onEchoGroupDraftName = viewModel::setEchoGroupDraftName,
                    onCreateEchoGroup = viewModel::createEchoGroup,
                    onToggleEchoGroupSelection = viewModel::toggleEchoGroupSelection,
                    onAssignEchoGroup = viewModel::assignSelectionToGroup,
                    onOpenDisplayName = viewModel::openDisplayNameDialog,
                    onCloseDisplayName = viewModel::closeDisplayNameDialog,
                    onDisplayNameDraft = viewModel::setDisplayNameDraft,
                    onSubmitDisplayName = viewModel::submitDisplayName,
                    onCreateEchoRules = viewModel::setCreateEchoRules,
                    onCreateEchoNetworkChannel = viewModel::setCreateEchoNetworkChannel,
                    onCreateEchoWriters = viewModel::setCreateEchoWriters,
                    onCreateEchoElectWritersFrom = viewModel::setCreateEchoElectWritersFrom,
                    onCreateEchoRetractVote = viewModel::setCreateEchoRetractVote,
                    onCreateEchoRetractVoteDays = viewModel::setCreateEchoRetractVoteDays,
                    onFeedVote = viewModel::castFeedVote,
                    onStartElection = viewModel::startElection,
                    onOpenVoteCompose = viewModel::openVoteCompose,
                    onCloseVoteCompose = viewModel::closeVoteCompose,
                    onVoteQuestion = viewModel::setVoteQuestion,
                    onVoteVariant = viewModel::setVoteVariant,
                    onAddVoteVariant = viewModel::addVoteVariant,
                    onRemoveVoteVariant = viewModel::removeVoteVariant,
                    onSubmitVoteCompose = viewModel::submitVoteCompose,
                    onVoucherType = viewModel::setVoucherType,
                    onVoucherTtl = viewModel::setVoucherTtl,
                    onLoadVouchers = viewModel::loadVouchers,
                    onRevokeVoucher = viewModel::revokeVoucher,
                    autoRequestPubkeys = state.autoRequestPubkeys,
                    autoSendPubkey = state.autoSendPubkey,
                    onAutoRequestPubkeys = viewModel::setAutoRequestPubkeys,
                    onAutoSendPubkey = viewModel::setAutoSendPubkey,
                )
            }
        }
    }
}
