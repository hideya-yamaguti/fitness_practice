package net.minotsu.fitnesspractice

import android.Manifest
import android.app.Activity
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.ScaffoldState
import androidx.compose.material.SnackbarHost
import androidx.compose.material.rememberScaffoldState
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.PermissionsRequired
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.google.android.gms.auth.api.signin.GoogleSignIn
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    @OptIn(ExperimentalMaterial3Api::class, ExperimentalPermissionsApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            Scaffold(
                modifier = Modifier.fillMaxSize()
            ) {
                val viewModel = viewModel<MainViewModel>()
                val state by viewModel.state.collectAsState()
                val scaffoldState = rememberScaffoldState()

                val permissionsState = rememberMultiplePermissionsState(
                    listOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                        Manifest.permission.ACTIVITY_RECOGNITION
                    )
                )

                SideEffect {
                    permissionsState.launchMultiplePermissionRequest()
                }

                Box(
                    modifier = Modifier
                        .padding(it)
                        .fillMaxSize()
                ) {

                    PermissionsRequired(
                        multiplePermissionsState = permissionsState,
                        permissionsNotGrantedContent = {},
                        permissionsNotAvailableContent = {}
                    ) {
                        Content(state, viewModel.dispatcher)
                    }

                    EventEffect(viewModel.effect, viewModel.dispatcher, scaffoldState)

                    SnackbarHost(
                        hostState = scaffoldState.snackbarHostState,
                        modifier = Modifier.align(Alignment.BottomCenter)
                    )
                }
            }
        }
    }
}

@Composable
private fun Content(state: MainViewModel.State, dispatcher: (MainViewModel.Event) -> Unit) {
    Column(
        modifier = Modifier.padding(16.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("セッション")

            Button(
                onClick = { dispatcher(MainViewModel.Event.StartSessionRequest) },
                modifier = Modifier.padding(start = 8.dp),
                enabled = !state.isLoginProcessing && state.sessionStatus == MainViewModel.SessionState.IDLE
            ) {
                Text(stringResource(R.string.start_session))
            }

            Button(
                onClick = { dispatcher(MainViewModel.Event.StopSessionRequest) },
                modifier = Modifier.padding(start = 8.dp),
                enabled = !state.isLoginProcessing && state.sessionStatus == MainViewModel.SessionState.STARTED
            ) {
                Text(stringResource(R.string.stop_session))
            }

            Button(
                onClick = { dispatcher(MainViewModel.Event.FetchSessionHistory) },
                modifier = Modifier.padding(start = 8.dp),
                enabled = !state.isLoginProcessing && !state.isFetchingSessionHistory
            ) {
                Text(stringResource(R.string.session_history))
            }
        }

        Row(
            modifier = Modifier.padding(top = 8.dp)
        ) {
            Button(
                onClick = { dispatcher(MainViewModel.Event.FetchActivityHistory) },
                modifier = Modifier.padding(start = 8.dp),
                enabled = !state.isLoginProcessing && !state.isFetchingActivityHistory
            ) {
                Text(stringResource(R.string.activity_history))
            }

            Button(
                onClick = { dispatcher(MainViewModel.Event.GetDataSource) },
                modifier = Modifier.padding(start = 8.dp),
                enabled = !state.isLoginProcessing && !state.isGettingDataSources
            ) {
                Text(stringResource(R.string.data_source))
            }
        }

        Column(
            modifier = Modifier
                .padding(8.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            Text(state.log)
        }
    }
}

@Composable
private fun EventEffect(
    effect: Flow<MainViewModel.Effect>,
    dispatcher: (MainViewModel.Event) -> Unit,
    scaffoldState: ScaffoldState
) {
    val activity = LocalContext.current as AppCompatActivity

    val loginLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (it.resultCode == Activity.RESULT_OK) {
            val account = checkNotNull(GoogleSignIn.getLastSignedInAccount(activity))
            dispatcher(MainViewModel.Event.LoggedIn(account))
        } else {
            dispatcher(MainViewModel.Event.LoginError)
        }
    }

    LaunchedEffect(effect) {
        effect.onEach { effect ->
            when (effect) {
                is MainViewModel.Effect.LaunchLogin -> {
                    loginLauncher.launch(effect.createGoogleSignInIntent(activity))
                }
                MainViewModel.Effect.DisplayLoginError -> {
                    scaffoldState.snackbarHostState.showSnackbar(activity.getString(R.string.login_failure))
                }
                is MainViewModel.Effect.StartSession -> {
                    effect.startSession(activity)
                }
                MainViewModel.Effect.DisplayStartSessionError -> {
                    scaffoldState.snackbarHostState.showSnackbar(activity.getString(R.string.start_session_failure))
                }
                is MainViewModel.Effect.StopSession -> {
                    effect.stopSession(activity)
                }
                MainViewModel.Effect.DisplayStopSessionError -> {
                    scaffoldState.snackbarHostState.showSnackbar(activity.getString(R.string.stop_session_failure))
                }
                is MainViewModel.Effect.FetchSessionHistory -> {
                    effect.fetchSessionHistory(activity)
                }
                MainViewModel.Effect.DisplayFetchSessionHistoryError -> {
                    scaffoldState.snackbarHostState.showSnackbar(activity.getString(R.string.fetch_session_history_failure))
                }
                is MainViewModel.Effect.FetchActivityHistory -> {
                    effect.fetchActivityHistory(activity)
                }
                MainViewModel.Effect.DisplayFetchActivityHistoryError -> {
                    scaffoldState.snackbarHostState.showSnackbar(activity.getString(R.string.fetch_activity_history_failure))
                }
                is MainViewModel.Effect.GetDataSource -> {
                    effect.getDataSource(activity)
                }
                MainViewModel.Effect.DisplayGetDataSourceError -> {
                    scaffoldState.snackbarHostState.showSnackbar(activity.getString(R.string.get_data_source_failure))
                }
            }
        }.launchIn(this)
    }
}

@Preview(
    name = "メイン画面",
    showBackground = true
)
@Composable
private fun PreviewUi() {
    Content(MainViewModel.State(), {})
}
