package lan.fmnode.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
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
                FmRoot(
                    state = state,
                    onUnlock = viewModel::unlock,
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
                )
            }
        }
    }
}
