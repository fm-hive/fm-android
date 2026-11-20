package lan.fmnode.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Forward
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.HowToVote
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Card
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import lan.fmnode.android.R
import lan.fmnode.android.network.EchoPermissions
import lan.fmnode.android.storage.ProfileEntity
import lan.fmnode.android.ui.theme.FmTheme

/**
 * Display preferences of the echo feed. Grouped into one parameter to keep the
 * already very large [FmRoot] composable under the JVM method-size limit.
 */
data class FeedSettingsCallbacks(
    val onShowTearline: (Boolean) -> Unit = {},
    val onShowServiceMessages: (Boolean) -> Unit = {},
)

@Composable
fun FmRoot(
    state: UiState,
    onUnlock: (String) -> Unit = {},
    onRememberPassword: (Boolean) -> Unit = {},
    onLock: () -> Unit = {},
    onRequestLogout: () -> Unit = {},
    onConfirmLogout: () -> Unit = {},
    onCancelLogout: () -> Unit = {},
    onOpenAddNetwork: () -> Unit = {},
    onCloseAddNetwork: () -> Unit = {},
    onUrlChange: (String) -> Unit = {},
    onDisplayNameChange: (String) -> Unit = {},
    onPasswordChange: (String) -> Unit = {},
    onPasswordConfirmChange: (String) -> Unit = {},
    onAddRememberPassword: (Boolean) -> Unit = {},
    onFetchPolicy: () -> Unit = {},
    onRegister: () -> Unit = {},
    onSelectProfile: (String) -> Unit = {},
    onDeleteProfile: (String) -> Unit = {},
    onBackFromNetworks: () -> Unit = {},
    onRefresh: () -> Unit = {},
    onOpenNetworks: () -> Unit = {},
    onSelectEcho: (String) -> Unit = {},
    onBackFromEcho: () -> Unit = {},
    onBackFromBlog: () -> Unit = {},
    onNewBlogPost: () -> Unit = {},
    onEditBlogPost: (BlogPostUi) -> Unit = {},
    onDeleteBlogPost: (String) -> Unit = {},
    onDeleteBlog: () -> Unit = {},
    onBlogTitle: (String) -> Unit = {},
    onBlogBody: (String) -> Unit = {},
    onSaveBlogPost: () -> Unit = {},
    onBackFromBlogCompose: () -> Unit = {},
    onOpenCompose: () -> Unit = {},
    onBackFromCompose: () -> Unit = {},
    onComposeSubject: (String) -> Unit = {},
    onComposeBody: (String) -> Unit = {},
    onComposeAnnouncement: (Boolean) -> Unit = {},
    onSendCompose: () -> Unit = {},
    onOpenNetmail: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onNetmailFolder: (String) -> Unit = {},
    onOpenNetmailFolderDialog: () -> Unit = {},
    onCloseNetmailFolderDialog: () -> Unit = {},
    onNetmailFolderName: (String) -> Unit = {},
    onCreateNetmailFolder: () -> Unit = {},
    onDeleteNetmailFolder: (String) -> Unit = {},
    onOpenNetmailMove: (String) -> Unit = {},
    onCloseNetmailMove: () -> Unit = {},
    onMoveNetmail: (String?) -> Unit = {},
    onOpenNetmailMessage: (String) -> Unit = {},
    onBackFromNetmailDetail: () -> Unit = {},
    onOpenNetmailCompose: () -> Unit = {},
    onBackFromNetmailCompose: () -> Unit = {},
    onNetmailRecipient: (String) -> Unit = {},
    onNetmailSubject: (String) -> Unit = {},
    onNetmailBody: (String) -> Unit = {},
    onSendNetmail: () -> Unit = {},
    onSelectEchoTab: () -> Unit = {},
    onDeleteActiveNetwork: () -> Unit = {},
    onImportKeys: () -> Unit = {},
    onImportPassword: (String) -> Unit = {},
    onCancelImport: () -> Unit = {},
    onExportKeys: (String) -> Unit = {},
    onExportSettings: () -> Unit = {},
    onExportFull: () -> Unit = {},
    onImportBackup: () -> Unit = {},
    onClearBackup: () -> Unit = {},
    onAcceptRules: () -> Unit = {},
    onCancelRules: () -> Unit = {},
    onOpenRulesEditor: () -> Unit = {},
    onCloseRulesEditor: () -> Unit = {},
    onRulesEditJson: (String) -> Unit = {},
    onRulesEditName: (String) -> Unit = {},
    onRulesEditAllChunks: (Boolean) -> Unit = {},
    onSubmitRulesEdit: () -> Unit = {},
    onOpenEchoGroups: () -> Unit = {},
    onCloseEchoGroups: () -> Unit = {},
    onEchoGroupDraftName: (String) -> Unit = {},
    onCreateEchoGroup: () -> Unit = {},
    onDeleteEchoGroup: (String) -> Unit = {},
    onToggleEchoGroupSelection: (String) -> Unit = {},
    onAssignEchoGroup: (String?) -> Unit = {},
    onOpenDisplayName: () -> Unit = {},
    onCloseDisplayName: () -> Unit = {},
    onDisplayNameDraft: (String) -> Unit = {},
    onSubmitDisplayName: () -> Unit = {},
    onCreateEchoRules: (String) -> Unit = {},
    onCreateEchoNetworkChannel: (Boolean) -> Unit = {},
    onCreateEchoWriters: (String) -> Unit = {},
    onCreateEchoElectWritersFrom: (String) -> Unit = {},
    onCreateEchoRetractVote: (Boolean) -> Unit = {},
    onCreateEchoRetractVoteDays: (String) -> Unit = {},
    onFeedVote: (VoteCardUi, String) -> Unit = { _, _ -> },
    onOpenVoteCompose: () -> Unit = {},
    onStartElection: () -> Unit = {},
    onCloseVoteCompose: () -> Unit = {},
    onVoteQuestion: (String) -> Unit = {},
    onVoteVariant: (Int, String) -> Unit = { _, _ -> },
    onAddVoteVariant: () -> Unit = {},
    onRemoveVoteVariant: (Int) -> Unit = {},
    onSubmitVoteCompose: () -> Unit = {},
    onVoucherType: (String) -> Unit = {},
    onVoucherTtl: (String) -> Unit = {},
    onLoadVouchers: () -> Unit = {},
    onRevokeVoucher: (String) -> Unit = {},
    autoRequestPubkeys: Boolean = false,
    autoSendPubkey: Boolean = false,
    onAutoRequestPubkeys: (Boolean) -> Unit = {},
    onAutoSendPubkey: (Boolean) -> Unit = {},
    onOpenSearch: () -> Unit = {},
    onCloseSearch: () -> Unit = {},
    onSearchQuery: (String) -> Unit = {},
    onOpenSearchResult: (SearchResultUi) -> Unit = {},
    onOpenProtocols: () -> Unit = {},
    onCloseProtocols: () -> Unit = {},
    onCastVote: (ProtocolUi, String) -> Unit = { _, _ -> },
    onOpenScanner: () -> Unit = {},
    onCloseScanner: () -> Unit = {},
    onQrScanned: (String) -> Unit = {},
    onAttachFiles: () -> Unit = {},
    onRemoveAttachment: (Int) -> Unit = {},
    onRemoveBlogAttachment: (Int) -> Unit = {},
    onRemoveNetmailAttachment: (Int) -> Unit = {},
    onReplyEchoMessage: (MessageUi) -> Unit = {},
    onReplyNetmailMessage: (MessageUi) -> Unit = {},
    onClearComposeReply: () -> Unit = {},
    onClearNetmailReply: () -> Unit = {},
    onComposeTagline: (String) -> Unit = {},
    onNetmailTagline: (String) -> Unit = {},
    onTagline: (String) -> Unit = {},
    /** Display preferences of the echo feed (tearline, service messages). */
    feedSettings: FeedSettingsCallbacks = FeedSettingsCallbacks(),
    onAllowSendingWithoutRateLimit: (Boolean) -> Unit = {},
    onImageMaxDimension: (Int) -> Unit = {},
    onRememberImageDimension: (Boolean) -> Unit = {},
    onConfirmImageResize: () -> Unit = {},
    onCancelImageResize: () -> Unit = {},
    onOpenAdmin: () -> Unit = {},
    onOpenRecovery: () -> Unit = {},
    onRequestVoucher: (NodeUi) -> Unit = {},
    onCloseRecovery: () -> Unit = {},
    onRecoveryUrl: (String) -> Unit = {},
    onSubmitRecovery: () -> Unit = {},
    onCloseAdmin: () -> Unit = {},
    onAdminRefresh: () -> Unit = {},
    onAdminMergeUrl: (String) -> Unit = {},
    onStartMerge: () -> Unit = {},
    onScanPeer: () -> Unit = {},
    onCreateVoucher: () -> Unit = {},
    onDismissVoucher: () -> Unit = {},
    onOpenEchoBrowser: () -> Unit = {},
    onCloseEchoBrowser: () -> Unit = {},
    onToggleSubscription: (String, Boolean) -> Unit = { _, _ -> },
    onAcceptPolicyResign: () -> Unit = {},
    onCancelPolicyResign: () -> Unit = {},
    onReactMessage: (MessageUi, String) -> Unit = { _, _ -> },
    onOpenOutbox: () -> Unit = {},
    onCloseOutbox: () -> Unit = {},
    onRetryOutbox: () -> Unit = {},
    onDeleteOutbox: (Long) -> Unit = {},
    onOpenRefillDialog: () -> Unit = {},
    onOpenRemoteSubscribe: () -> Unit = {},
    onRemoteSubscribeEcho: (String) -> Unit = {},
    onRemoteSubscribeHost: (String) -> Unit = {},
    onSendRemoteSubscribe: () -> Unit = {},
    onCloseRemoteSubscribe: () -> Unit = {},
    onOpenAppeal: (MessageUi) -> Unit = {},
    onAppealReason: (String) -> Unit = {},
    onSubmitAppeal: () -> Unit = {},
    onCloseAppeal: () -> Unit = {},
    onCloseRefillDialog: () -> Unit = {},
    onRefillDate: (String) -> Unit = {},
    onSendRefill: () -> Unit = {},
    onCreateEchoDialog: () -> Unit = {},
    onCloseCreateEchoDialog: () -> Unit = {},
    onCreateEchoName: (String) -> Unit = {},
    onCreateEchoDescription: (String) -> Unit = {},
    onCreateEchoType: (CreateEchoType) -> Unit = {},
    onCreateLocalEcho: () -> Unit = {},
    onExcommTarget: (String) -> Unit = {},
    onExcommReason: (String) -> Unit = {},
    onSubmitExcomm: () -> Unit = {},
    onRespondRefill: (ProtocolUi) -> Unit = {},
    onVoteElection: (ProtocolUi, String) -> Unit = { _, _ -> },
    onOpenPolicyViewer: () -> Unit = {},
    onClosePolicyViewer: () -> Unit = {},
    onOpenRemoveEcho: () -> Unit = {},
    onCloseRemoveEcho: () -> Unit = {},
    onRemoveEchoName: (String) -> Unit = {},
    onRemoveEchoReason: (String) -> Unit = {},
    onSubmitRemoveEcho: () -> Unit = {},
    onOpenPolicyEdit: () -> Unit = {},
    onClosePolicyEdit: () -> Unit = {},
    onPolicyEditText: (String) -> Unit = {},
    onPolicyEditField: (String, String) -> Unit = { _, _ -> },
    onPolicyEditRateLimit: (String, String) -> Unit = { _, _ -> },
    onPreviewPolicyDiff: () -> Unit = {},
    onPolicyEditAllChunks: (Boolean) -> Unit = {},
    onSubmitPolicyEdit: () -> Unit = {},
    onOpenModeration: (MessageUi) -> Unit = {},
    onCloseModeration: () -> Unit = {},
    onModerationSeverity: (String) -> Unit = {},
    onModerationDuration: (String) -> Unit = {},
    onModerationReason: (String) -> Unit = {},
    onSubmitModeration: () -> Unit = {},
    onOpenContacts: () -> Unit = {},
    onCloseContacts: () -> Unit = {},
    onNewContactAddress: (String) -> Unit = {},
    onNewContactName: (String) -> Unit = {},
    onAddContact: () -> Unit = {},
    onDeleteContact: (String) -> Unit = {},
    onPickContact: (String) -> Unit = {},
    onForwardMessage: (MessageUi) -> Unit = {},
    onToggleSelection: () -> Unit = {},
    onToggleMessageSelection: (String) -> Unit = {},
    onForwardSelected: () -> Unit = {},
    onOpenMessage: (MessageUi) -> Unit = {},
    onBackFromMessage: () -> Unit = {},
    onChangeMessageView: (EchoMessageView) -> Unit = {},
    onBackToRoot: () -> Unit = {},
    onDismissSendErrors: () -> Unit = {},
    onDismissAddressNotice: () -> Unit = {},
    onSelectEchoInNetwork: (String, String) -> Unit = { _, _ -> },
) {
    var shareMessage by remember { mutableStateOf<MessageUi?>(null) }
    val shareLabels = ShareLabels(
        echo = stringResource(R.string.share_echo_label),
        from = stringResource(R.string.share_from_label),
        date = stringResource(R.string.share_date_label),
        netmailEcho = stringResource(R.string.share_netmail_echo),
    )
    // System back walks the in-app screen history instead of closing the app (#8).
    BackHandler {
        if (state.logoutDialog) return@BackHandler
        when (state.screen) {
            AppScreen.Unlock, AppScreen.Networks -> onBackToRoot()
            AppScreen.AddNetwork -> onCloseAddNetwork()
            AppScreen.Echoes -> onBackToRoot()
            AppScreen.Echo -> onBackFromEcho()
            AppScreen.Compose -> onBackFromCompose()
            AppScreen.MessageDetail -> onBackFromMessage()
            AppScreen.Blog -> onBackFromBlog()
            AppScreen.BlogCompose -> onBackFromBlogCompose()
            AppScreen.Netmail -> onBackToRoot()
            AppScreen.NetmailDetail -> onBackFromNetmailDetail()
            AppScreen.NetmailCompose -> onBackFromNetmailCompose()
            AppScreen.Search -> onCloseSearch()
            AppScreen.Protocols -> onCloseProtocols()
            AppScreen.QrScanner -> onCloseScanner()
            AppScreen.Admin -> onCloseAdmin()
            AppScreen.EchoBrowser -> onCloseEchoBrowser()
            AppScreen.Outbox -> onCloseOutbox()
            AppScreen.Settings -> onSelectEchoTab()
        }
    }
    if (state.logoutDialog) {
        AlertDialog(
            onDismissRequest = onCancelLogout,
            title = { Text(stringResource(R.string.logout)) },
            text = { Text(stringResource(R.string.logout_confirm)) },
            confirmButton = {
                TextButton(onClick = onConfirmLogout) { Text(stringResource(R.string.logout)) }
            },
            dismissButton = {
                TextButton(onClick = onCancelLogout) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
    if (state.contactsDialog) {
        ContactsDialog(
            contacts = state.contacts,
            address = state.newContactAddress,
            name = state.newContactName,
            onAddress = onNewContactAddress,
            onName = onNewContactName,
            onAdd = onAddContact,
            onPick = onPickContact,
            onDelete = onDeleteContact,
            onDismiss = onCloseContacts,
        )
    }
    if (state.policyViewer) {
        PolicyViewerDialog(text = state.policyViewText, onDismiss = onClosePolicyViewer)
    }
    if (state.removeEchoDialog) {
        RemoveEchoDialog(
            name = state.removeEchoName,
            reason = state.removeEchoReason,
            sending = state.removeEchoSending,
            status = state.removeEchoStatus,
            error = state.removeEchoError,
            onName = onRemoveEchoName,
            onReason = onRemoveEchoReason,
            onSubmit = onSubmitRemoveEcho,
            onDismiss = onCloseRemoveEcho,
        )
    }
    state.voteCompose?.let { draft ->
        VoteComposeDialog(
            state = draft,
            onQuestion = onVoteQuestion,
            onVariant = onVoteVariant,
            onAddVariant = onAddVoteVariant,
            onRemoveVariant = onRemoveVoteVariant,
            onSubmit = onSubmitVoteCompose,
            onDismiss = onCloseVoteCompose,
        )
    }
    state.policyEdit?.let { edit ->
        PolicyEditDialog(
            state = edit,
            onText = onPolicyEditText,
            onField = onPolicyEditField,
            onRateLimit = onPolicyEditRateLimit,
            onAllChunks = onPolicyEditAllChunks,
            onPreview = onPreviewPolicyDiff,
            onSubmit = onSubmitPolicyEdit,
            onDismiss = onClosePolicyEdit,
        )
    }
    state.moderationTarget?.let {
        ModerationDialog(
            severity = state.moderationSeverity,
            duration = state.moderationDuration,
            reason = state.moderationReason,
            sending = state.moderationSending,
            error = state.moderationError,
            onSeverity = onModerationSeverity,
            onDuration = onModerationDuration,
            onReason = onModerationReason,
            onSubmit = onSubmitModeration,
            onDismiss = onCloseModeration,
        )
    }
    if (state.createEchoDialog) {
        CreateEchoDialog(
            name = state.createEchoName,
            description = state.createEchoDescription,
            type = state.createEchoType,
            rules = state.createEchoRules,
            networkChannel = state.createEchoNetworkChannel,
            writers = state.createEchoWriters,
            electWritersFrom = state.createEchoElectWritersFrom,
            retractVote = state.createEchoRetractVote,
            retractVoteDays = state.createEchoRetractVoteDays,
            error = state.createEchoError,
            onName = onCreateEchoName,
            onDescription = onCreateEchoDescription,
            onType = onCreateEchoType,
            onRules = onCreateEchoRules,
            onNetworkChannel = onCreateEchoNetworkChannel,
            onWriters = onCreateEchoWriters,
            onElectWritersFrom = onCreateEchoElectWritersFrom,
            onRetractVote = onCreateEchoRetractVote,
            onRetractVoteDays = onCreateEchoRetractVoteDays,
            onCreate = onCreateLocalEcho,
            onDismiss = onCloseCreateEchoDialog,
        )
    }
    state.rulesPrompt?.let { prompt ->
        RulesPromptDialog(prompt = prompt, onAccept = onAcceptRules, onDismiss = onCancelRules)
    }
    state.rulesEdit?.let { edit ->
        RulesEditDialog(
            state = edit,
            onRules = onRulesEditJson,
            onName = onRulesEditName,
            onAllChunks = onRulesEditAllChunks,
            onSubmit = onSubmitRulesEdit,
            onDismiss = onCloseRulesEditor,
        )
    }
    if (state.echoGroupsDialog) {
        EchoGroupsDialog(
            groups = state.echoGroups,
            echoes = state.echoes.filterNot { it.isBlog },
            draftName = state.echoGroupDraftName,
            selection = state.echoGroupSelection,
            error = state.echoGroupError,
            onDraftName = onEchoGroupDraftName,
            onCreate = onCreateEchoGroup,
            onDelete = onDeleteEchoGroup,
            onToggle = onToggleEchoGroupSelection,
            onAssign = onAssignEchoGroup,
            onDismiss = onCloseEchoGroups,
        )
    }
    if (state.displayNameDialog) {
        DisplayNameDialog(
            draft = state.displayNameDraft,
            saving = state.displayNameSaving,
            error = state.displayNameError,
            onChange = onDisplayNameDraft,
            onSubmit = onSubmitDisplayName,
            onDismiss = onCloseDisplayName,
        )
    }
    if (state.refillDialog) {
        RefillDialog(
            date = state.refillDate,
            sending = state.refillSending,
            error = state.refillError,
            onDate = onRefillDate,
            onSend = onSendRefill,
            onDismiss = onCloseRefillDialog,
        )
    }
    if (state.remoteSubscribeDialog) {
        RemoteSubscribeDialog(
            echo = state.remoteSubscribeEcho,
            host = state.remoteSubscribeHost,
            sending = state.remoteSubscribeSending,
            status = state.remoteSubscribeStatus,
            error = state.remoteSubscribeError,
            onEcho = onRemoteSubscribeEcho,
            onHost = onRemoteSubscribeHost,
            onSubmit = onSendRemoteSubscribe,
            onDismiss = onCloseRemoteSubscribe,
        )
    }
    state.appealTarget?.let { target ->
        AppealDialog(
            message = target,
            reason = state.appealReason,
            sending = state.appealSending,
            status = state.appealStatus,
            error = state.appealError,
            onReason = onAppealReason,
            onSubmit = onSubmitAppeal,
            onDismiss = onCloseAppeal,
        )
    }
    if (state.showPolicyResign) {
        PolicyResignDialog(
            policyText = state.policyResignText,
            onAccept = onAcceptPolicyResign,
            onDismiss = onCancelPolicyResign,
        )
    }
    shareMessage?.let { message ->
        MessageShareDialog(
            message = message,
            labels = shareLabels,
            onDismiss = { shareMessage = null },
        )
    }
    if (state.imageResizeDialog) {
        ImageResizeDialog(
            maxDimension = state.imageMaxDimension,
            remember = state.rememberImageDimension,
            onDimension = onImageMaxDimension,
            onRemember = onRememberImageDimension,
            onConfirm = onConfirmImageResize,
            onDismiss = onCancelImageResize,
        )
    }
    when (state.screen) {
        AppScreen.Unlock -> UnlockScreen(
            error = state.unlockError,
            remember = state.rememberPassword,
            unlocking = state.autoUnlocking,
            onRemember = onRememberPassword,
            onUnlock = onUnlock,
        )
        AppScreen.Networks -> NetworksScreen(
            profiles = state.profiles,
            activeId = state.activeId,
            canGoBack = state.activeId != null,
            onBack = onBackFromNetworks,
            onAdd = onOpenAddNetwork,
            onSelect = onSelectProfile,
            onDelete = onDeleteProfile,
        )
        AppScreen.AddNetwork -> AddNetworkScreen(
            state = state.add,
            onBack = onCloseAddNetwork,
            onUrlChange = onUrlChange,
            onDisplayNameChange = onDisplayNameChange,
            onPasswordChange = onPasswordChange,
            onPasswordConfirmChange = onPasswordConfirmChange,
            onRememberPassword = onAddRememberPassword,
            onFetchPolicy = onFetchPolicy,
            onRegister = onRegister,
            onImportKeys = onImportKeys,
            onImportPassword = onImportPassword,
            onCancelImport = onCancelImport,
            onScanQr = onOpenScanner,
        )
        AppScreen.Echoes -> EchoesListScreen(
            title = state.networkName.ifBlank { stringResource(R.string.app_name) },
            echoes = state.echoes,
            syncing = state.syncing,
            error = state.syncError,
            onRefresh = onRefresh,
            onNetworks = onOpenNetworks,
            onLock = onLock,
            onLogout = onRequestLogout,
            onSelectEcho = onSelectEcho,
            onNetmail = onOpenNetmail,
            onSettings = onOpenSettings,
            onSearch = onOpenSearch,
            onProtocols = onOpenProtocols,
            onBrowseEchoes = onOpenEchoBrowser,
            networks = state.networks,
            activeId = state.activeId,
            sendErrors = state.sendErrors,
            addressNotice = state.addressNotice,
            onSelectEchoInNetwork = onSelectEchoInNetwork,
            onDismissSendErrors = onDismissSendErrors,
            onDismissAddressNotice = onDismissAddressNotice,
            echoGroups = state.echoGroups,
            onOpenEchoGroups = onOpenEchoGroups,
        )
        AppScreen.Echo -> {
            val echo = state.selectedEcho
            val networkNews = echo != null &&
                echo.name.equals(networkNewsEchoName(state.activeProfile?.policyJson.orEmpty()), ignoreCase = true)
            val canWrite = echo == null || EchoPermissions.canWriteToEcho(
                echo.rulesText,
                state.activeProfile?.address.orEmpty(),
                networkNews,
            )
            EchoConversationScreen(
                echoName = echo?.name ?: "",
                messages = state.messages,
                syncing = state.syncing,
                canModerate = state.activeProfile?.let { it.isAdmin || it.isModerator } == true,
                canWrite = canWrite,
                canAppeal = state.activeProfile?.isAdmin == true,
                selectionMode = state.selectionMode,
                selectedMessages = state.selectedMessages,
                onToggleSelection = onToggleSelection,
                onToggleMessageSelection = onToggleMessageSelection,
                onForwardSelected = onForwardSelected,
                onBack = onBackFromEcho,
                onRefresh = onRefresh,
                onCompose = onOpenCompose,
                onRefill = onOpenRefillDialog,
                onReact = onReactMessage,
                onModerate = onOpenModeration,
                onAppeal = onOpenAppeal,
                onForward = onForwardMessage,
                onReply = onReplyEchoMessage,
                onShare = { shareMessage = it },
                showTearline = state.showTearline,
                showServiceMessages = state.showServiceMessages,
                onShowServiceMessages = feedSettings.onShowServiceMessages,
                messageView = state.messageView,
                onChangeMessageView = onChangeMessageView,
                onOpenMessage = onOpenMessage,
                sendErrors = state.sendErrors,
                onDismissSendErrors = onDismissSendErrors,
                onOpenRules = onOpenRulesEditor,
                onOpenVote = onOpenVoteCompose,
                onOpenElection = onStartElection,
                onVote = onFeedVote,
            )
        }
        AppScreen.MessageDetail -> MessageDetailScreen(
            message = state.selectedMessage,
            onBack = onBackFromMessage,
            onReact = onReactMessage,
            onForward = onForwardMessage,
            onModerate = onOpenModeration,
            canModerate = state.activeProfile?.let { it.isAdmin || it.isModerator } == true,
            canAppeal = state.activeProfile?.isAdmin == true,
            onAppeal = onOpenAppeal,
            onReply = onReplyEchoMessage,
            onShare = { shareMessage = it },
            showTearline = state.showTearline,
            canReact = state.selectedEcho?.let { echo ->
                EchoPermissions.canWriteToEcho(
                    echo.rulesText,
                    state.activeProfile?.address.orEmpty(),
                    echo.name.equals(
                        networkNewsEchoName(state.activeProfile?.policyJson.orEmpty()),
                        ignoreCase = true,
                    ),
                )
            } ?: true,
        )
        AppScreen.Blog -> state.selectedBlog?.let { blog ->
            BlogScreen(
                blog = blog,
                posts = state.blogPosts,
                onBack = onBackFromBlog,
                onNewPost = onNewBlogPost,
                onEditPost = onEditBlogPost,
                onDeletePost = onDeleteBlogPost,
                onDeleteBlog = onDeleteBlog,
            )
        }
        AppScreen.BlogCompose -> BlogComposeScreen(
            state = state.blogCompose,
            onBack = onBackFromBlogCompose,
            onTitle = onBlogTitle,
            onBody = onBlogBody,
            onAttachFiles = onAttachFiles,
            onRemoveAttachment = onRemoveBlogAttachment,
            onSave = onSaveBlogPost,
        )
        AppScreen.Compose -> EchoComposeScreen(
            echoName = state.selectedEcho?.name ?: "",
            state = state.compose,
            announcementAllowed = state.selectedEcho?.let {
                EchoPermissions.announcementsEnabled(it.rulesText)
            } == true,
            onBack = onBackFromCompose,
            onSubject = onComposeSubject,
            onBody = onComposeBody,
            onAnnouncement = onComposeAnnouncement,
            onSend = onSendCompose,
            onAttachFiles = onAttachFiles,
            onRemoveAttachment = onRemoveAttachment,
            onTagline = onComposeTagline,
            onClearReply = onClearComposeReply,
        )
        AppScreen.QrScanner -> QrScannerScreen(
            onBack = onCloseScanner,
            onScanned = onQrScanned,
        )
        AppScreen.Search -> SearchScreen(
            query = state.searchQuery,
            loading = state.searchLoading,
            results = state.searchResults,
            onBack = onCloseSearch,
            onQuery = onSearchQuery,
            onOpenResult = onOpenSearchResult,
        )
        AppScreen.Protocols -> ProtocolsScreen(
            protocols = state.protocols,
            onBack = onCloseProtocols,
            onVote = onCastVote,
            onRespondRefill = onRespondRefill,
            onVoteElection = onVoteElection,
            refillStatus = state.refillRespondStatus,
        )
        AppScreen.Netmail -> NetmailScreen(
            messages = state.netmail,
            customFolders = state.netmailFolders,
            folder = state.netmailFolder,
            folderDialog = state.netmailFolderDialog,
            folderName = state.netmailFolderName,
            folderError = state.netmailFolderError,
            moveMessageId = state.netmailMoveMessageId,
            onFolder = onNetmailFolder,
            onOpen = onOpenNetmailMessage,
            onCompose = onOpenNetmailCompose,
            onOpenFolderDialog = onOpenNetmailFolderDialog,
            onCloseFolderDialog = onCloseNetmailFolderDialog,
            onFolderName = onNetmailFolderName,
            onCreateFolder = onCreateNetmailFolder,
            onDeleteFolder = onDeleteNetmailFolder,
            onOpenMove = onOpenNetmailMove,
            onCloseMove = onCloseNetmailMove,
            onMove = onMoveNetmail,
            onEchoes = onSelectEchoTab,
            onSettings = onOpenSettings,
            onLock = onLock,
            onLogout = onRequestLogout,
        )
        AppScreen.NetmailDetail -> NetmailDetailScreen(
            message = state.selectedNetmail,
            onBack = onBackFromNetmailDetail,
            onForward = onForwardMessage,
            onReply = onReplyNetmailMessage,
            onShare = { shareMessage = it },
            showTearline = state.showTearline,
        )
        AppScreen.NetmailCompose -> NetmailComposeScreen(
            state = state.netmailCompose,
            onBack = onBackFromNetmailCompose,
            onRecipient = onNetmailRecipient,
            onSubject = onNetmailSubject,
            onBody = onNetmailBody,
            onSend = onSendNetmail,
            onContacts = onOpenContacts,
            onAttachFiles = onAttachFiles,
            onRemoveAttachment = onRemoveNetmailAttachment,
            onTagline = onNetmailTagline,
            onClearReply = onClearNetmailReply,
        )
        AppScreen.Outbox -> OutboxScreen(
            entries = state.outbox,
            onBack = onCloseOutbox,
            onRetry = onRetryOutbox,
            onDelete = onDeleteOutbox,
        )
        AppScreen.EchoBrowser -> EchoBrowserScreen(
            echoes = state.allEchoes.filter { !it.isBlog },
            onBack = onCloseEchoBrowser,
            onToggle = onToggleSubscription,
            onCreateLocal = onCreateEchoDialog,
            onRemoteSubscribe = onOpenRemoteSubscribe,
        )
        AppScreen.Admin -> AdminScreen(
            nodes = state.adminNodes,
            loading = state.adminLoading,
            mergeUrl = state.adminMergeUrl,
            mergeStatus = state.adminMergeStatus,
            mergeError = state.adminMergeError,
            onBack = onCloseAdmin,
            onRefresh = onAdminRefresh,
            onMergeUrl = onAdminMergeUrl,
            onScanPeer = onScanPeer,
            onStartMerge = onStartMerge,
            excommTarget = state.excommTarget,
            excommReason = state.excommReason,
            excommSending = state.excommSending,
            excommStatus = state.excommStatus,
            excommError = state.excommError,
            mergeMessages = state.protocols.filter { it.type == "merge" },
            onExcommTarget = onExcommTarget,
            onExcommReason = onExcommReason,
            onSubmitExcomm = onSubmitExcomm,
            onOpenRemoveEcho = onOpenRemoveEcho,
            onOpenPolicyEdit = onOpenPolicyEdit,
        )
        AppScreen.Settings -> SettingsScreen(
            profile = state.activeProfile,
            networkName = state.networkName,
            onDeleteNetwork = onDeleteActiveNetwork,
            onEchoes = onSelectEchoTab,
            onNetmail = onOpenNetmail,
            onExportKeys = onExportKeys,
            onExportSettings = onExportSettings,
            onExportFull = onExportFull,
            onImportBackup = onImportBackup,
            backupStatus = state.backupStatus,
            backupError = state.backupError,
            onClearBackup = onClearBackup,
            onOpenAdmin = onOpenAdmin,
            onOpenRecovery = onOpenRecovery,
            openNodes = state.adminNodes.filter { it.isOpenNode },
            onRequestVoucher = onRequestVoucher,
            onCreateVoucher = onCreateVoucher,
            voucherCode = state.voucherCode,
            voucherLoading = state.voucherLoading,
            voucherError = state.voucherError,
            onDismissVoucher = onDismissVoucher,
            onOpenOutbox = onOpenOutbox,
            onOpenPolicyViewer = onOpenPolicyViewer,
            onOpenDisplayName = onOpenDisplayName,
            tagline = state.tagline,
            showTearline = state.showTearline,
            onTagline = onTagline,
            onShowTearline = feedSettings.onShowTearline,
            allowSendingWithoutRateLimit = state.allowSendingWithoutRateLimit,
            onAllowSendingWithoutRateLimit = onAllowSendingWithoutRateLimit,
            voucherEnabled = state.voucherEnabled,
            voucherType = state.voucherType,
            voucherTtl = state.voucherTtl,
            voucherReusable = state.voucherReusable,
            voucherExpiresAt = state.voucherExpiresAt,
            vouchers = state.vouchers,
            vouchersLoading = state.vouchersLoading,
            vouchersError = state.vouchersError,
            onVoucherType = onVoucherType,
            onVoucherTtl = onVoucherTtl,
            onLoadVouchers = onLoadVouchers,
            onRevokeVoucher = onRevokeVoucher,
            autoRequestPubkeys = autoRequestPubkeys,
            autoSendPubkey = autoSendPubkey,
            onAutoRequestPubkeys = onAutoRequestPubkeys,
            onAutoSendPubkey = onAutoSendPubkey,
            onLock = onLock,
            onLogout = onRequestLogout,
        )
    }
    if (state.recoveryDialog) {
        RecoveryDialog(
            url = state.recoveryUrl,
            loading = state.recoveryLoading,
            error = state.recoveryError,
            onUrlChange = onRecoveryUrl,
            onConfirm = onSubmitRecovery,
            onDismiss = onCloseRecovery,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecoveryDialog(
    url: String,
    loading: Boolean,
    error: UiError?,
    onUrlChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!loading) onDismiss() },
        title = { Text(stringResource(R.string.recovery_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.recovery_hint))
                OutlinedTextField(
                    value = url,
                    onValueChange = onUrlChange,
                    label = { Text(stringResource(R.string.recovery_url)) },
                    singleLine = false,
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let {
                    val message = (it as? UiError.Failed)?.text ?: it.toString()
                    Text(message, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !loading && url.isNotBlank()) {
                Text(if (loading) stringResource(R.string.recovery_loading) else stringResource(R.string.recovery_start))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !loading) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

// ----------------------------------------------------------------------- vault

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UnlockScreen(
    error: UiError?,
    remember: Boolean = false,
    unlocking: Boolean = false,
    onRemember: (Boolean) -> Unit = {},
    onUnlock: (String) -> Unit,
) {
    var password by remember { mutableStateOf("") }
    Scaffold(
        topBar = { CenterAlignedTopAppBar(title = { Text(stringResource(R.string.app_name)) }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(stringResource(R.string.unlock_title), style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(16.dp))
            if (unlocking) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.width(24.dp).height(24.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.unlocking_saved_password))
                }
            } else {
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.password)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { onRemember(!remember) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = remember, onCheckedChange = onRemember)
                    Text(
                        text = stringResource(R.string.remember_password),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (error != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(errorText(error), color = MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.height(16.dp))
                Button(onClick = { onUnlock(password) }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.unlock))
                }
            }
        }
    }
}

// -------------------------------------------------------------------- networks

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworksScreen(
    profiles: List<ProfileEntity>,
    activeId: String?,
    canGoBack: Boolean,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onSelect: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.networks_title)) },
                navigationIcon = {
                    if (canGoBack) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAdd) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.add_network))
            }
        },
    ) { padding ->
        if (profiles.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(R.string.networks_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 88.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(profiles, key = { it.id }) { profile ->
                ProfileCard(
                    profile = profile,
                    active = profile.id == activeId,
                    onSelect = { onSelect(profile.id) },
                    onDelete = { onDelete(profile.id) },
                )
            }
        }
    }
}

@Composable
private fun ProfileCard(
    profile: ProfileEntity,
    active: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                // The network is named by its policy (#20), not by the HTTP host.
                val networkName = networkNameFromPolicyJson(profile.policyJson)
                Text(
                    text = networkName.ifBlank { profile.title },
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = profile.address,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = profile.apiUrl,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (active) {
                AssistChip(onClick = onSelect, label = { Text(stringResource(R.string.active)) })
                Spacer(Modifier.width(4.dp))
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.delete))
            }
        }
    }
}

// ----------------------------------------------------------------- add network

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddNetworkScreen(
    state: AddNetworkState,
    onBack: () -> Unit,
    onUrlChange: (String) -> Unit,
    onDisplayNameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onPasswordConfirmChange: (String) -> Unit,
    onRememberPassword: (Boolean) -> Unit = {},
    onFetchPolicy: () -> Unit,
    onRegister: () -> Unit,
    onImportKeys: () -> Unit,
    onImportPassword: (String) -> Unit,
    onCancelImport: () -> Unit,
    onScanQr: () -> Unit,
) {
    if (state.awaitingImportPassword) {
        ImportPasswordDialog(
            error = state.importError,
            busy = state.isLoading,
            onConfirm = onImportPassword,
            onDismiss = onCancelImport,
        )
    }
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.add_network)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            when (state.step) {
                AddStep.Url -> {
                    OutlinedTextField(
                        value = state.url,
                        onValueChange = onUrlChange,
                        label = { Text(stringResource(R.string.invite_url)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(16.dp))
                    OutlinedButton(onClick = onImportKeys, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.import_keys))
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = onScanQr, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.scan_qr))
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = onFetchPolicy,
                        enabled = !state.isLoading && state.url.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (state.isLoading) {
                            CircularProgressIndicator(modifier = Modifier.width(20.dp).height(20.dp))
                        } else {
                            Text(stringResource(R.string.fetch_policy))
                        }
                    }
                }
                AddStep.Policy -> {
                    Text(stringResource(R.string.policy_title), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = state.policyText,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    OutlinedTextField(
                        value = state.displayName,
                        onValueChange = onDisplayNameChange,
                        label = { Text(stringResource(R.string.display_name)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (state.createPassword) {
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = state.password,
                            onValueChange = onPasswordChange,
                            label = { Text(stringResource(R.string.password)) },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = state.passwordConfirm,
                            onValueChange = onPasswordConfirmChange,
                            label = { Text(stringResource(R.string.password_confirm)) },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable {
                                onRememberPassword(!state.rememberPassword)
                            },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = state.rememberPassword,
                                onCheckedChange = onRememberPassword,
                            )
                            Text(
                                text = stringResource(R.string.remember_password),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                    if (state.imported) {
                        Spacer(Modifier.height(8.dp))
                        Text(stringResource(R.string.keys_imported), color = MaterialTheme.colorScheme.primary)
                    }
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = onRegister,
                        enabled = !state.isLoading,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (state.isLoading) {
                            CircularProgressIndicator(modifier = Modifier.width(20.dp).height(20.dp))
                        } else {
                            Text(stringResource(R.string.register))
                        }
                    }
                }
            }
            state.error?.let {
                Spacer(Modifier.height(12.dp))
                Text(errorText(it), color = MaterialTheme.colorScheme.error)
            }
            // Import failures surface here too: a dialog-only message was
            // invisible once the password dialog closed.
            if (!state.awaitingImportPassword) {
                state.importError?.let {
                    Spacer(Modifier.height(12.dp))
                    Text(errorText(it), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------- echoes

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EchoesListScreen(
    title: String,
    echoes: List<EchoUi>,
    syncing: Boolean,
    error: UiError?,
    onRefresh: () -> Unit,
    onNetworks: () -> Unit,
    onLock: () -> Unit,
    onLogout: () -> Unit,
    onSelectEcho: (String) -> Unit,
    onNetmail: () -> Unit,
    onSettings: () -> Unit,
    onSearch: () -> Unit,
    onProtocols: () -> Unit,
    onBrowseEchoes: () -> Unit,
    networks: List<NetworkGroupUi> = emptyList(),
    activeId: String? = null,
    sendErrors: List<String> = emptyList(),
    addressNotice: AddressNoticeUi? = null,
    onSelectEchoInNetwork: (String, String) -> Unit = { _, _ -> },
    onDismissSendErrors: () -> Unit = {},
    onDismissAddressNotice: () -> Unit = {},
    /** User-defined groups of the active network (#366 of fm-app). */
    echoGroups: List<EchoGroupUi> = emptyList(),
    onOpenEchoGroups: () -> Unit = {},
) {
    Scaffold(
        bottomBar = {
            MainBottomBar(AppScreen.Echoes, onEchoes = {}, onNetmail = onNetmail, onSettings = onSettings)
        },
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                actions = {
                    if (syncing) {
                        CircularProgressIndicator(modifier = Modifier.width(20.dp).height(20.dp))
                        Spacer(Modifier.width(8.dp))
                    }
                    IconButton(onClick = onBrowseEchoes) {
                        Icon(Icons.Default.Explore, contentDescription = stringResource(R.string.browse_echoes))
                    }
                    IconButton(onClick = onSearch) {
                        Icon(Icons.Default.Search, contentDescription = stringResource(R.string.search_title))
                    }
                    IconButton(onClick = onProtocols) {
                        Icon(Icons.Default.HowToVote, contentDescription = stringResource(R.string.protocols_title))
                    }
                    IconButton(onClick = onOpenEchoGroups) {
                        Icon(Icons.Default.Checklist, contentDescription = stringResource(R.string.echo_groups_title))
                    }
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.refresh))
                    }
                    IconButton(onClick = onNetworks) {
                        Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.networks_title))
                    }
                    AppMenu(onLock = onLock, onLogout = onLogout)
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            error?.let {
                Text(
                    text = errorText(it),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            addressNotice?.let { notice ->
                NoticeBanner(
                    title = stringResource(R.string.address_changed, notice.network, notice.address),
                    tone = MaterialTheme.colorScheme.primaryContainer,
                    onDismiss = onDismissAddressNotice,
                )
            }
            if (sendErrors.isNotEmpty()) {
                NoticeBanner(
                    title = stringResource(R.string.send_error_title),
                    body = sendErrors.joinToString("\n") + "\n" + stringResource(R.string.send_error_hint),
                    tone = MaterialTheme.colorScheme.errorContainer,
                    onDismiss = onDismissSendErrors,
                )
            }
            // Several networks of the vault are listed as separate groups (#15).
            val groups = if (networks.size > 1) networks else emptyList()
            val activeGroup = networks.firstOrNull { it.profileId == activeId }
            // With one network the list stays flat; its group may be filled
            // before the active-session flow emits, so use the active profile's
            // cache first and only then the live echo list (#28).
            val flat = if (groups.isEmpty()) {
                activeGroup?.echoes ?: networks.firstOrNull()?.echoes ?: echoes
            } else {
                emptyList()
            }
            if (groups.isEmpty() && flat.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.echoes_empty),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                return@Scaffold
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (groups.isEmpty()) {
                    EchoItems(
                        echoes = flat,
                        echoGroups = echoGroups,
                        keyPrefix = activeId ?: "echo",
                        onSelect = { onSelectEcho(it.name) },
                    )
                } else {
                    groups.forEach { group ->
                        if (group.echoes.isEmpty()) return@forEach
                        stickyHeader(key = "group-${group.profileId}") {
                            NetworkGroupHeader(group)
                        }
                        EchoItems(
                            echoes = group.echoes,
                            // User groups belong to the active network profile only.
                            echoGroups = if (group.profileId == activeId) echoGroups else emptyList(),
                            keyPrefix = group.profileId,
                            onSelect = { onSelectEchoInNetwork(group.profileId, it.name) },
                        )
                    }
                }
            }
        }
    }
}

/** Echo cards of one network, split by the user's groups when they exist (#366). */
private fun LazyListScope.EchoItems(
    echoes: List<EchoUi>,
    echoGroups: List<EchoGroupUi>,
    keyPrefix: String,
    onSelect: (EchoUi) -> Unit,
) {
    if (echoGroups.isEmpty()) {
        items(echoes, key = { "$keyPrefix:${it.name}" }) { echo ->
            EchoCard(echo = echo, onClick = { onSelect(echo) })
        }
        return
    }
    // Conferences inside one network are split by the user's own groups; the
    // conferences that belong to no group stay under "Ungrouped".
    val grouped = echoGroups.flatMap { it.echoes }.toSet()
    echoGroups.forEach { group ->
        val members = echoes.filter { it.name in group.echoes }
        item(key = "$keyPrefix:ug:${group.id}") {
            Text(
                text = group.name,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        if (members.isEmpty()) {
            item(key = "$keyPrefix:ug:${group.id}:empty") {
                Text(
                    text = stringResource(R.string.echo_groups_empty_members),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
                )
            }
        } else {
            items(members, key = { "$keyPrefix:${group.id}:${it.name}" }) { echo ->
                EchoCard(echo = echo, onClick = { onSelect(echo) })
            }
        }
    }
    val rest = echoes.filterNot { it.name in grouped }
    if (rest.isNotEmpty()) {
        item(key = "$keyPrefix:ug:none") {
            Text(
                text = stringResource(R.string.echo_groups_ungrouped),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        items(rest, key = { "$keyPrefix:${it.name}" }) { echo ->
            EchoCard(echo = echo, onClick = { onSelect(echo) })
        }
    }
}

/** Header of one network in the grouped echo list (#15). */
@Composable
private fun NetworkGroupHeader(group: NetworkGroupUi) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                // Network name from the policy, not the node URL (#20).
                text = group.displayName,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            if (group.isActive) {
                Text(
                    text = stringResource(R.string.network_group_active),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (group.unread > 0) {
                Badge { Text(group.unread.toString()) }
            }
        }
        Text(
            // The endpoint stays as secondary information for telling two
            // networks on one host apart.
            text = "${group.address} · ${group.endpoint}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Dismissible banner used for the address notice (#11) and send errors (#14). */
@Composable
private fun NoticeBanner(
    title: String,
    body: String? = null,
    tone: Color,
    onDismiss: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(tone)
            .padding(start = 12.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            if (!body.isNullOrBlank()) {
                Text(text = body, style = MaterialTheme.typography.bodySmall)
            }
        }
        IconButton(onClick = onDismiss) {
            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.close))
        }
    }
}

/** Toolbar overflow with the exit actions (#3). */
@Composable
fun AppMenu(onLock: () -> Unit, onLogout: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.more))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.lock)) },
                onClick = {
                    expanded = false
                    onLock()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.logout)) },
                onClick = {
                    expanded = false
                    onLogout()
                },
            )
        }
    }
}

@Composable
private fun EchoCard(echo: EchoUi, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(echo.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    if (echo.isBlog) {
                        Spacer(Modifier.width(6.dp))
                        AssistChip(onClick = {}, label = { Text("B") })
                    }
                }
                if (echo.description.isNotBlank()) {
                    Text(
                        text = echo.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (echo.unread > 0) {
                Badge { Text(echo.unread.toString()) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EchoConversationScreen(
    echoName: String,
    messages: List<MessageUi>,
    syncing: Boolean,
    canModerate: Boolean,
    canWrite: Boolean = true,
    canAppeal: Boolean = false,
    selectionMode: Boolean,
    selectedMessages: Set<String>,
    onToggleSelection: () -> Unit,
    onToggleMessageSelection: (String) -> Unit,
    onForwardSelected: () -> Unit,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onCompose: () -> Unit,
    onRefill: () -> Unit,
    onReact: (MessageUi, String) -> Unit,
    onModerate: (MessageUi) -> Unit,
    onAppeal: (MessageUi) -> Unit = {},
    onForward: (MessageUi) -> Unit,
    onReply: (MessageUi) -> Unit = {},
    onShare: (MessageUi) -> Unit = {},
    showTearline: Boolean = false,
    /** List protocol/service messages in the feed (#46). */
    showServiceMessages: Boolean = false,
    onShowServiceMessages: (Boolean) -> Unit = {},
    messageView: EchoMessageView = EchoMessageView.All,
    onChangeMessageView: (EchoMessageView) -> Unit = {},
    onOpenMessage: (MessageUi) -> Unit = {},
    sendErrors: List<String> = emptyList(),
    onDismissSendErrors: () -> Unit = {},
    onOpenRules: () -> Unit = {},
    onOpenVote: () -> Unit = {},
    onOpenElection: () -> Unit = {},
    onVote: (VoteCardUi, String) -> Unit = { _, _ -> },
) {
    var versionsMessage by remember { mutableStateOf<MessageUi?>(null) }
    var menuExpanded by remember { mutableStateOf(false) }
    // Attachment-only uploads are listed separately as one entry per file bundle (#9).
    val visibleMessages = remember(messages, messageView) {
        if (messageView == EchoMessageView.Attachments) {
            messages.filter { it.attachments.isNotEmpty() }
        } else {
            messages
        }
    }
    if (versionsMessage != null) {
        MessageVersionsDialog(message = versionsMessage!!, onDismiss = { versionsMessage = null })
    }
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(echoName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    if (syncing) {
                        CircularProgressIndicator(modifier = Modifier.width(20.dp).height(20.dp))
                        Spacer(Modifier.width(8.dp))
                    }
                    MessageViewToggle(view = messageView, onChange = onChangeMessageView)
                    IconButton(onClick = onToggleSelection) {
                        Icon(Icons.Default.Checklist, contentDescription = stringResource(R.string.select_messages))
                    }
                    if (selectionMode && selectedMessages.isNotEmpty()) {
                        IconButton(onClick = onForwardSelected) {
                            Icon(Icons.Default.Forward, contentDescription = stringResource(R.string.forward))
                        }
                    }
                    IconButton(onClick = onRefill) {
                        Icon(Icons.Default.History, contentDescription = stringResource(R.string.refill_title))
                    }
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.refresh))
                    }
                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.more))
                        }
                        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                            if (canWrite) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.vote_create_title)) },
                                    onClick = {
                                        menuExpanded = false
                                        onOpenVote()
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.election_start)) },
                                    onClick = {
                                        menuExpanded = false
                                        onOpenElection()
                                    },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.rules_edit_title)) },
                                onClick = {
                                    menuExpanded = false
                                    onOpenRules()
                                },
                            )
                            // Protocol/service messages stay out of the feed
                            // unless this checkbox is on (#46).
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.show_service_messages)) },
                                leadingIcon = {
                                    Checkbox(
                                        checked = showServiceMessages,
                                        onCheckedChange = null,
                                    )
                                },
                                onClick = { onShowServiceMessages(!showServiceMessages) },
                            )
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            if (canWrite) {
                FloatingActionButton(onClick = onCompose) {
                    Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.compose))
                }
            }
        },
    ) { padding ->
        if (visibleMessages.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(padding)) {
                if (sendErrors.isNotEmpty()) {
                    NoticeBanner(
                        title = stringResource(R.string.send_error_title),
                        body = sendErrors.joinToString("\n") + "\n" + stringResource(R.string.send_error_hint),
                        tone = MaterialTheme.colorScheme.errorContainer,
                        onDismiss = onDismissSendErrors,
                    )
                }
                Box(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(
                            if (messageView == EchoMessageView.Attachments) {
                                R.string.attachments_empty
                            } else {
                                R.string.no_messages
                            },
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            return@Scaffold
        }
        Column(Modifier.fillMaxSize()) {
            if (sendErrors.isNotEmpty()) {
                NoticeBanner(
                    title = stringResource(R.string.send_error_title),
                    body = sendErrors.joinToString("\n") + "\n" + stringResource(R.string.send_error_hint),
                    tone = MaterialTheme.colorScheme.errorContainer,
                    onDismiss = onDismissSendErrors,
                )
            }
            ConversationList(
                messages = visibleMessages,
                messageView = messageView,
                padding = padding,
                canModerate = canModerate,
                canReact = canWrite,
                canAppeal = canAppeal,
                onAppeal = onAppeal,
                selectionMode = selectionMode,
                selectedMessages = selectedMessages,
                onToggleMessageSelection = onToggleMessageSelection,
                onReact = onReact,
                onModerate = onModerate,
                onForward = onForward,
                onReply = onReply,
                onShare = onShare,
                showTearline = showTearline,
                onOpenMessage = onOpenMessage,
                versionsMessage = { versionsMessage = it },
                onVote = onVote,
            )
        }
    }
}

/** The message list of a conversation, below the optional notice banner. */
@Composable
private fun ConversationList(
    messages: List<MessageUi>,
    messageView: EchoMessageView,
    padding: androidx.compose.foundation.layout.PaddingValues,
    canModerate: Boolean,
    canReact: Boolean = true,
    canAppeal: Boolean = false,
    onAppeal: (MessageUi) -> Unit = {},
    selectionMode: Boolean,
    selectedMessages: Set<String>,
    onToggleMessageSelection: (String) -> Unit,
    onReact: (MessageUi, String) -> Unit,
    onModerate: (MessageUi) -> Unit,
    onForward: (MessageUi) -> Unit,
    onReply: (MessageUi) -> Unit = {},
    onShare: (MessageUi) -> Unit = {},
    showTearline: Boolean = false,
    onOpenMessage: (MessageUi) -> Unit,
    versionsMessage: (MessageUi) -> Unit,
    onVote: (VoteCardUi, String) -> Unit = { _, _ -> },
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 12.dp,
            end = 12.dp,
            top = padding.calculateTopPadding() + 8.dp,
            bottom = padding.calculateBottomPadding() + 88.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(messages, key = { it.messageId }) { message ->
            MessageCard(
                message = message,
                canModerate = canModerate,
                canReact = canReact,
                canAppeal = canAppeal,
                onAppeal = onAppeal,
                selectionMode = selectionMode,
                selected = message.messageId in selectedMessages,
                onToggleSelected = { onToggleMessageSelection(message.messageId) },
                onReact = onReact,
                onModerate = onModerate,
                onForward = onForward,
                onReply = onReply,
                onShare = onShare,
                showTearline = showTearline,
                onVersions = { versionsMessage(message) },
                onOpen = { onOpenMessage(message) },
                onVote = onVote,
            )
        }
    }
}

/**
 * Ballot of a vote poster: question, variants with counts, the current choice,
 * vote/retract actions and the result once the vote is over (#21).
 */
@Composable
private fun VoteBallot(vote: VoteCardUi, onVote: (String) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.vote_card_title),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = if (vote.finished) {
                    stringResource(R.string.vote_card_finished)
                } else {
                    stringResource(R.string.vote_card_active)
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = stringResource(R.string.vote_card_total, vote.totalVotes),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (vote.question.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(vote.question, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        }
        if (vote.variants.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            vote.variants.forEach { variant ->
                val count = vote.counts[variant] ?: 0
                val selected = vote.myVote == variant
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !vote.finished) { onVote(variant) }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = selected,
                        onClick = { onVote(variant) },
                        enabled = !vote.finished,
                    )
                    Text(
                        text = if (count > 0) "$variant ($count)" else variant,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        modifier = Modifier.weight(1f),
                    )
                    if (vote.finished && vote.winner == variant) {
                        Text(
                            text = "\uD83C\uDFC6",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }
        if (vote.myVote != null && !vote.finished) {
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = { onVote("") }) {
                Text(stringResource(R.string.vote_retract))
            }
        }
        if (vote.finished) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = when {
                    !vote.success -> vote.resultText.ifBlank { stringResource(R.string.vote_card_failed) }
                    vote.winner != null -> {
                        val base = stringResource(R.string.vote_card_winner, vote.winner)
                        if (vote.resultText.isBlank()) base else "$base — ${vote.resultText}"
                    }
                    else -> vote.resultText
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (vote.success) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
        }
    }
}

@Composable
private fun MessageCard(
    message: MessageUi,
    canModerate: Boolean,
    canReact: Boolean = true,
    canAppeal: Boolean = false,
    onAppeal: (MessageUi) -> Unit = {},
    selectionMode: Boolean,
    selected: Boolean,
    onToggleSelected: () -> Unit,
    onReact: (MessageUi, String) -> Unit,
    onModerate: (MessageUi) -> Unit,
    onForward: (MessageUi) -> Unit,
    onReply: (MessageUi) -> Unit = {},
    onShare: (MessageUi) -> Unit = {},
    showTearline: Boolean = false,
    onVersions: (MessageUi) -> Unit,
    onOpen: (MessageUi) -> Unit = {},
    onVote: (VoteCardUi, String) -> Unit = { _, _ -> },
) {
    var viewer by remember { mutableStateOf<AttachmentUi?>(null) }
    viewer?.let { attachment ->
        AttachmentViewerDialog(attachment = attachment, onDismiss = { viewer = null })
    }
    Card(modifier = Modifier.fillMaxWidth().clickable { onOpen(message) }) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (selectionMode) {
                    Checkbox(checked = selected, onCheckedChange = { onToggleSelected() })
                }
                Text(
                    text = message.senderName,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                if (message.versions.isNotEmpty()) {
                    TextButton(onClick = { onVersions(message) }) {
                        Text("${message.versions.size}✎")
                    }
                }
                IconButton(onClick = { onReply(message) }) {
                    Icon(
                        Icons.AutoMirrored.Filled.Reply,
                        contentDescription = stringResource(R.string.fm_messageactions_reply),
                    )
                }
                IconButton(onClick = { onShare(message) }) {
                    Icon(
                        Icons.Default.Share,
                        contentDescription = stringResource(R.string.share_title),
                    )
                }
                IconButton(onClick = { onForward(message) }) {
                    Icon(Icons.Default.Forward, contentDescription = stringResource(R.string.forward))
                }
                if (canModerate && !message.own) {
                    IconButton(onClick = { onModerate(message) }) {
                        Icon(Icons.Default.Gavel, contentDescription = stringResource(R.string.moderate))
                    }
                }
                if (canAppeal && message.moderation != null && !message.own) {
                    IconButton(onClick = { onAppeal(message) }) {
                        Icon(Icons.Default.Gavel, contentDescription = stringResource(R.string.appeal_title))
                    }
                }
                Text(
                    text = formatTimestamp(message.timestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = message.senderAddress,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // A service message is marked with its protocol type and, for a
            // refill request, the cutoff date (#46).
            message.protocolType?.let { protocolType ->
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AssistChip(onClick = {}, label = { Text(protocolType) })
                    message.refillBefore?.let { before ->
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.refill_until, formatRefillDate(before)),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            val vote = message.vote
            if (vote != null) {
                // A vote poster is shown as a ballot: question, options with
                // counts, the current choice and the result (#21).
                Spacer(Modifier.height(6.dp))
                VoteBallot(vote = vote, onVote = { variant -> onVote(vote, variant) })
            } else {
                if (message.subject.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(message.subject, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                }
                if (message.body.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    MessageBody(
                        body = message.body,
                        messageId = message.messageId,
                        attachments = message.attachments,
                        onOpenAttachment = { viewer = it },
                    )
                }
                if (message.tagline.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = message.tagline,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (showTearline && message.tearline.isNotBlank()) {
                    Text(
                        text = "--- ${message.tearline}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val inline = referencedAttachmentIndices(message.body, message.messageId)
                val gallery = message.attachments.filterIndexed { index, _ -> index !in inline }
                if (gallery.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    AttachmentsRow(gallery)
                }
            }
            if (canReact) {
                val quick = listOf("👍", "❤️", "😂")
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    quick.forEach { emoji ->
                        val count = message.reactions[emoji] ?: 0
                        TextButton(onClick = { onReact(message, emoji) }) {
                            Text(
                                text = if (count > 0) "$emoji $count" else emoji,
                                color = if (message.myReaction == emoji) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    }
                    message.reactions.filterKeys { it !in quick }.forEach { (emoji, count) ->
                        AssistChip(onClick = { onReact(message, emoji) }, label = { Text("$emoji $count") })
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EchoComposeScreen(
    echoName: String,
    state: ComposeState,
    announcementAllowed: Boolean = false,
    onBack: () -> Unit,
    onSubject: (String) -> Unit,
    onBody: (String) -> Unit,
    onAnnouncement: (Boolean) -> Unit = {},
    onSend: () -> Unit,
    onAttachFiles: () -> Unit,
    onRemoveAttachment: (Int) -> Unit,
    onTagline: (String) -> Unit = {},
    onClearReply: () -> Unit = {},
) {
    var showEmoji by remember { mutableStateOf(false) }
    if (showEmoji) {
        EmojiPickerDialog(
            onPick = { emoji -> onBody(state.body + emoji) },
            onDismiss = { showEmoji = false },
        )
    }
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(echoName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(onClick = onAttachFiles) {
                        Icon(Icons.Default.AttachFile, contentDescription = stringResource(R.string.attach_file))
                    }
                    IconButton(onClick = { showEmoji = true }) {
                        Icon(Icons.Default.EmojiEmotions, contentDescription = stringResource(R.string.emoji_title))
                    }
                    TextButton(
                        onClick = onSend,
                        enabled = !state.sending && state.limitIssues.none { it.blocking },
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.send))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(12.dp),
        ) {
            ReplyBanner(label = state.replyToLabel, onClear = onClearReply)
            OutlinedTextField(
                value = state.subject,
                onValueChange = onSubject,
                label = { Text(stringResource(R.string.subject)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (announcementAllowed) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = state.announcement,
                        onCheckedChange = onAnnouncement,
                    )
                    Text(
                        text = stringResource(R.string.compose_announcement),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            MessageLimitWarnings(state.limitIssues)
            Spacer(Modifier.height(8.dp))
            ComposerBodyEditor(
                body = state.body,
                onBody = onBody,
                attachments = state.attachments,
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = state.tagline,
                onValueChange = onTagline,
                label = { Text(stringResource(R.string.fm_editor_tagline)) },
                placeholder = { Text(stringResource(R.string.fm_editor_taglineplaceholder)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (state.attachments.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                ComposerAttachments(attachments = state.attachments, onRemove = onRemoveAttachment)
            }
            state.error?.let {
                Spacer(Modifier.height(8.dp))
                Text(errorText(it), color = MaterialTheme.colorScheme.error)
            }
            if (state.sending) {
                Spacer(Modifier.height(8.dp))
                CircularProgressIndicator(modifier = Modifier.width(24.dp).height(24.dp))
            }
        }
    }
}

@Composable
private fun ImportPasswordDialog(
    error: UiError?,
    busy: Boolean,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var password by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.import_password_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    enabled = !busy,
                    label = { Text(stringResource(R.string.password)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                if (busy) {
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.width(20.dp).height(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.import_in_progress))
                    }
                }
                if (error != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(errorText(error), color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(password) }, enabled = !busy) {
                Text(stringResource(R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

// -------------------------------------------------------------------- helpers

/** All / attachments-only switch of the conversation toolbar (#9). */
@Composable
private fun MessageViewToggle(view: EchoMessageView, onChange: (EchoMessageView) -> Unit) {
    IconButton(
        onClick = {
            onChange(
                if (view == EchoMessageView.All) EchoMessageView.Attachments else EchoMessageView.All,
            )
        },
    ) {
        Icon(
            imageVector = if (view == EchoMessageView.All) Icons.Default.AttachFile else Icons.Default.Checklist,
            contentDescription = stringResource(
                if (view == EchoMessageView.All) R.string.attachments_only else R.string.show_all_messages,
            ),
        )
    }
}

/** Full-screen view of one message with attachments at full size (#9). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageDetailScreen(
    message: MessageUi?,
    onBack: () -> Unit,
    onReact: (MessageUi, String) -> Unit,
    onForward: (MessageUi) -> Unit,
    onModerate: (MessageUi) -> Unit,
    canModerate: Boolean,
    canReact: Boolean = true,
    canAppeal: Boolean = false,
    onAppeal: (MessageUi) -> Unit = {},
    onReply: (MessageUi) -> Unit = {},
    onShare: (MessageUi) -> Unit = {},
    showTearline: Boolean = false,
) {
    var viewer by remember { mutableStateOf<AttachmentUi?>(null) }
    viewer?.let { attachment ->
        AttachmentViewerDialog(attachment = attachment, onDismiss = { viewer = null })
    }
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.message_title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    if (message != null) {
                        IconButton(onClick = { onReply(message) }) {
                            Icon(
                                Icons.AutoMirrored.Filled.Reply,
                                contentDescription = stringResource(R.string.fm_messageactions_reply),
                            )
                        }
                        IconButton(onClick = { onShare(message) }) {
                            Icon(
                                Icons.Default.Share,
                                contentDescription = stringResource(R.string.share_title),
                            )
                        }
                        IconButton(onClick = { onForward(message) }) {
                            Icon(Icons.Default.Forward, contentDescription = stringResource(R.string.forward))
                        }
                        if (canModerate && !message.own) {
                            IconButton(onClick = { onModerate(message) }) {
                                Icon(Icons.Default.Gavel, contentDescription = stringResource(R.string.moderate))
                            }
                        }
                        if (canAppeal && message.moderation != null && !message.own) {
                            IconButton(onClick = { onAppeal(message) }) {
                                Icon(Icons.Default.Gavel, contentDescription = stringResource(R.string.appeal_title))
                            }
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (message == null) return@Scaffold
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            if (message.subject.isNotBlank()) {
                Text(
                    text = message.subject,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(8.dp))
            }
            Text(
                text = "${message.senderName} (${message.senderAddress})",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = formatTimestamp(message.timestamp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (message.body.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                MessageBody(
                    body = message.body,
                    messageId = message.messageId,
                    attachments = message.attachments,
                    maxImageHeight = 420.dp,
                    onOpenAttachment = { viewer = it },
                )
            }
            if (message.tagline.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = message.tagline,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (showTearline && message.tearline.isNotBlank()) {
                Text(
                    text = "--- ${message.tearline}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val inline = referencedAttachmentIndices(message.body, message.messageId)
            val gallery = message.attachments.filterIndexed { index, _ -> index !in inline }
            if (gallery.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                gallery.forEach { attachment ->
                    AttachmentViewer(attachment)
                    Spacer(Modifier.height(12.dp))
                }
            }
            if (canReact) {
                val quick = listOf("👍", "❤️", "😂")
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    quick.forEach { emoji ->
                        val count = message.reactions[emoji] ?: 0
                        TextButton(onClick = { onReact(message, emoji) }) {
                            Text(
                                text = if (count > 0) "$emoji $count" else emoji,
                                color = if (message.myReaction == emoji) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    }
                    message.reactions.filterKeys { it !in quick }.forEach { (emoji, count) ->
                        AssistChip(onClick = { onReact(message, emoji) }, label = { Text("$emoji $count") })
                    }
                }
            }
        }
    }
}

@Composable
internal fun errorText(error: UiError): String = when (error) {
    UiError.WrongPassword -> stringResource(R.string.error_password)
    UiError.PasswordsDiffer -> stringResource(R.string.error_passwords_differ)
    UiError.PasswordShort -> stringResource(R.string.error_password_short)
    is UiError.Failed -> error.text
}

internal fun formatTimestamp(seconds: Long): String {
    val millis = if (seconds < 10_000_000_000L) seconds * 1000 else seconds
    return SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(millis))
}

/**
 * Refill cutoff (`YYYY-MM-DD` or a full ISO timestamp) as a local date.
 * Unparseable values are shown as is.
 */
internal fun formatRefillDate(raw: String): String {
    val text = raw.trim()
    if (text.isEmpty()) return raw
    val date = runCatching { java.time.LocalDate.parse(text) }.getOrNull()
        ?: runCatching {
            java.time.Instant.parse(text).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        }.getOrNull()
        ?: runCatching { java.time.OffsetDateTime.parse(text).toLocalDate() }.getOrNull()
        ?: return text
    return date.format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy"))
}

@Composable
fun MessageVersionsDialog(message: MessageUi, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${stringResource(R.string.versions_title)} (${message.versions.size})") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                message.versions.asReversed().forEach { version ->
                    Text(
                        text = formatTimestamp(version.timestamp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (version.subject.isNotBlank()) {
                        Text(version.subject, fontWeight = FontWeight.SemiBold)
                    }
                    Text(version.body, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(12.dp))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
        },
    )
}

// ------------------------------------------------------------------ previews

private fun sampleProfile(id: String, title: String, address: String, api: String) = ProfileEntity(
    id = id,
    title = title,
    appUrl = api.replace("/api", "/app"),
    apiUrl = api,
    nodePublicKeyB64 = "",
    address = address,
    displayName = "Ivan",
    isAdmin = false,
    isModerator = false,
    publicKeyB64 = "",
    encryptedSeedB64 = "",
    seedSaltB64 = "",
    seedIvB64 = "",
    seedIterations = 100000,
    policyJson = "{}",
    createdAt = 0,
    lastSyncAt = null,
)

@Preview(showBackground = true)
@Composable
private fun NetworksPreview() {
    FmTheme {
        FmRoot(
            state = UiState(
                profiles = listOf(
                    sampleProfile("1", "fm.example.com (:0:1)", ":0:1", "https://fm.example.com/api"),
                    sampleProfile("2", "2.28.13.140 (:0:1)", ":0:1", "https://2.28.13.140/abc/api"),
                ),
                activeId = "1",
                unlocked = true,
            ),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun UnlockPreview() {
    FmTheme {
        FmRoot(state = UiState(screen = AppScreen.Unlock, unlockError = UiError.WrongPassword))
    }
}

@Preview(showBackground = true)
@Composable
private fun AddNetworkUrlPreview() {
    FmTheme {
        FmRoot(state = UiState(screen = AppScreen.AddNetwork))
    }
}

@Preview(showBackground = true)
@Composable
private fun AddNetworkPolicyPreview() {
    FmTheme {
        FmRoot(
            state = UiState(
                screen = AppScreen.AddNetwork,
                add = AddNetworkState(
                    step = AddStep.Policy,
                    policyText = "Правила сети\n\n{ \"voting_days\": \"30\", \"min_percent_for_network_decisions\": \"50\" }",
                    createPassword = true,
                ),
            ),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun EchoesPreview() {
    FmTheme {
        FmRoot(
            state = UiState(
                screen = AppScreen.Echoes,
                unlocked = true,
                activeProfile = sampleProfile("1", "fm.example.com (:0:1)", ":0:1", "https://fm.example.com/api"),
                echoes = listOf(
                    EchoUi("General", "General discussions", 3),
                    EchoUi("Tech", "Technology talks", 0),
                    EchoUi("Admin council", "Council of the network", 1),
                ),
            ),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun EchoPreview() {
    FmTheme {
        FmRoot(
            state = UiState(
                screen = AppScreen.Echo,
                selectedEcho = EchoUi("General", "General discussions", 0),
                messages = listOf(
                    MessageUi(
                        messageId = "1",
                        senderName = "Petya",
                        senderAddress = ":0:2",
                        recipientAddress = null,
                        timestamp = 1792200000,
                        subject = "Привет",
                        body = "Как дела?",
                        own = false,
                    ),
                    MessageUi(
                        messageId = "2",
                        senderName = "Ivan",
                        senderAddress = ":0:1",
                        recipientAddress = null,
                        timestamp = 1792200600,
                        subject = "Re: Привет",
                        body = "Всё хорошо!",
                        own = true,
                    ),
                ),
            ),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ComposePreview() {
    FmTheme {
        FmRoot(
            state = UiState(
                screen = AppScreen.Compose,
                selectedEcho = EchoUi("General", "", 0),
                compose = ComposeState(subject = "Тема", body = "Текст сообщения"),
            ),
        )
    }
}
