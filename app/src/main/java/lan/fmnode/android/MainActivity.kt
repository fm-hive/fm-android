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
import lan.fmnode.android.ui.theme.FmTheme

class MainActivity : ComponentActivity() {
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
                    onOpenCompose = viewModel::openCompose,
                    onBackFromCompose = viewModel::backFromCompose,
                    onComposeSubject = viewModel::setComposeSubject,
                    onComposeBody = viewModel::setComposeBody,
                    onSendCompose = viewModel::sendCompose,
                    onOpenNetmail = viewModel::openNetmail,
                    onOpenSettings = viewModel::openSettings,
                    onNetmailFolder = viewModel::selectNetmailFolder,
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
                    onOpenScanner = viewModel::openScanner,
                    onCloseScanner = viewModel::closeScanner,
                    onQrScanned = viewModel::onQrScanned,
                    onExportKeys = { password ->
                        viewModel.exportKeysJson(password)?.let { json ->
                            pendingExport = json
                            exportLauncher.launch("fm-keys.json")
                        }
                    },
                )
            }
        }
    }
}
