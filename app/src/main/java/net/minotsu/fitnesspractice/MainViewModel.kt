package net.minotsu.fitnesspractice

import android.app.Activity
import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.fitness.Fitness
import com.google.android.gms.fitness.FitnessActivities
import com.google.android.gms.fitness.FitnessOptions
import com.google.android.gms.fitness.data.DataSource
import com.google.android.gms.fitness.data.DataType
import com.google.android.gms.fitness.data.Session
import com.google.android.gms.fitness.request.DataReadRequest
import com.google.android.gms.fitness.request.DataSourcesRequest
import com.google.android.gms.fitness.request.SessionInsertRequest
import com.google.android.gms.fitness.request.SessionReadRequest
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onEmpty
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor() : ViewModel() {

    private val account: MutableStateFlow<GoogleSignInAccount?> = MutableStateFlow(null)

    private val dataTypes: List<DataType> = listOf(
        DataType.TYPE_STEP_COUNT_DELTA,
        DataType.TYPE_DISTANCE_DELTA,
        DataType.TYPE_CALORIES_EXPENDED
    )

    private val effectiveAccount: Flow<GoogleSignInAccount> =
        account.onEach {
            if (it == null) {
                { activity: Activity ->
                    FitnessOptions.builder().run {
                        dataTypes.forEach { dataType -> addDataType(dataType) }
                        build()
                    }.let { fitnessOptions ->
                        GoogleSignInOptions.Builder()
                            .addExtension(fitnessOptions)
                            .build()
                    }.let { googleSignInOptions ->
                        GoogleSignIn.getClient(activity, googleSignInOptions).signInIntent
                    }
                }.let { intentFactory ->
                    effectInternal.emit(Effect.LaunchLogin(intentFactory))
                }
            }
        }.filterNotNull().take(1)

    private val sessionStartTime: MutableStateFlow<OffsetDateTime?> = MutableStateFlow(null)

    private val stateInternal: MutableStateFlow<State> = MutableStateFlow(State())
    val state: StateFlow<State> = stateInternal

    private val effectInternal: MutableSharedFlow<Effect> = MutableSharedFlow()
    val effect: Flow<Effect> = effectInternal

    val dispatcher: (Event) -> Unit = { event(it) }

    private fun event(event: Event): Any = when (event) {
        is Event.LoggedIn -> onLoggedIn(event.account)
        Event.LoginError -> onLoginError()
        Event.StartSessionRequest -> onStartSessionRequest()
        Event.StopSessionRequest -> onStopSessionRequest()
        Event.FetchSessionHistory -> onFetchSessionHistory()
        Event.FetchActivityHistory -> onFetchActivityHistory()
        Event.GetDataSource -> onGetDataSource()
    }

    private fun onLoggedIn(account: GoogleSignInAccount) = viewModelScope.launch {
        this@MainViewModel.account.value = account
    }

    private fun onLoginError() = viewModelScope.launch {
        effectInternal.emit(Effect.DisplayLoginError)
    }

    @OptIn(FlowPreview::class)
    private fun onStartSessionRequest() = viewModelScope.launch {
        { activity: AppCompatActivity ->
            flow {
                combine(
                    effectiveAccount,
                    dataTypes.asFlow()
                ) { account, dataType ->
                    flowOf(
                        Fitness.getRecordingClient(activity, account)
                            .subscribe(dataType)
                            .await()
                    )
                }.toList().let {
                    emit(it)
                }
            }.flatMapMerge {
                combine(it) {}
            }.flowOn(Dispatchers.IO).onStart {
                stateInternal.update { it.copy(sessionStatus = SessionState.STARTING) }
            }.onEach {
                sessionStartTime.value = OffsetDateTime.now()
                stateInternal.update { it.copy(sessionStatus = SessionState.STARTED) }
            }.catch {
                effectInternal.emit(Effect.DisplayStartSessionError)
                stateInternal.update { it.copy(sessionStatus = SessionState.IDLE) }
            }.launchIn(activity.lifecycleScope)
        }.let {
            effectInternal.emit(Effect.StartSession(it))
        }
    }

    @OptIn(FlowPreview::class)
    private fun onStopSessionRequest() = viewModelScope.launch {
        { activity: AppCompatActivity ->
            sessionStartTime.filterNotNull().take(1).map {
                Session.Builder()
                    .setIdentifier(UUID.randomUUID().toString())
                    .setName("FitnessPracticeSession")
                    .setActivity(FitnessActivities.RUNNING)
                    .setStartTime(it.toEpochSecond(), TimeUnit.SECONDS)
                    .setEndTime(OffsetDateTime.now().toEpochSecond(), TimeUnit.SECONDS)
                    .build()
            }.map {
                flowOf(
                    SessionInsertRequest.Builder()
                        .setSession(it)
                        .build()
                )
            }.flatMapMerge {
                combine(
                    it,
                    effectiveAccount
                ) { sessionInsertRequest, account ->
                    Fitness.getSessionsClient(activity, account)
                        .insertSession(sessionInsertRequest)
                        .await()
                }
            }.flatMapMerge {
                unsubscribeAll(activity)
            }.flowOn(Dispatchers.IO).onStart {
                stateInternal.update { it.copy(sessionStatus = SessionState.STOPPING) }
            }.onEach {
                stateInternal.update { it.copy(sessionStatus = SessionState.IDLE) }
            }.catch {
                effectInternal.emit(Effect.DisplayStopSessionError)
                stateInternal.update { it.copy(sessionStatus = SessionState.STARTED) }
            }.launchIn(activity.lifecycleScope)
        }.let {
            effectInternal.emit(Effect.StopSession(it))
        }
    }

    @OptIn(FlowPreview::class)
    private fun unsubscribeAll(activity: Activity): Flow<Unit> {
        return effectiveAccount.map {
            Fitness.getRecordingClient(activity, it)
                .listSubscriptions()
                .await().map { subscription ->
                    flowOf(
                        Fitness.getRecordingClient(activity, it)
                            .unsubscribe(subscription)
                            .await()
                    )
                }
        }.flatMapMerge {
            combine(it) {}
        }
    }

    @OptIn(FlowPreview::class)
    private fun onFetchSessionHistory() = viewModelScope.launch {
        { activity: AppCompatActivity ->
            flowOf(
                OffsetDateTime.now().let {
                    listOf(
                        it.minusHours(2),
                        it.plusHours(2)
                    ).map { dateTime ->
                        dateTime.toEpochSecond()
                    }
                }.let {
                    SessionReadRequest.Builder()
                        .setTimeInterval(it[0], it[1], TimeUnit.SECONDS)
                        .build()
                }
            ).let {
                combine(it, effectiveAccount) { sessionReadRequest, account ->
                    Fitness.getSessionsClient(activity, account)
                        .readSession(sessionReadRequest)
                        .await()
                }
            }.map {
                if (it.status.isSuccess) {
                    it.sessions.associateWith { res -> it.getDataSet(res) }.toList()
                } else {
                    throw Exception("fetch session history failure")
                }
            }.flatMapMerge {
                it.asFlow()
            }.flowOn(Dispatchers.IO).onStart {
                stateInternal.update {
                    it.copy(
                        isFetchingSessionHistory = true,
                        log = "fetch session history now ..."
                    )
                }
            }.onEach { (session, dataSets) ->
                stateInternal.update { it.copy(log = toString(session, dataSets)) }
            }.onEmpty {
                stateInternal.update { it.copy(log = "no session") }
            }.catch {
                stateInternal.update { it.copy(log = "") }
                effectInternal.emit(Effect.DisplayFetchSessionHistoryError)
            }.onCompletion {
                stateInternal.update { it.copy(isFetchingSessionHistory = false) }
            }.launchIn(activity.lifecycleScope)
        }.let {
            effectInternal.emit(Effect.FetchSessionHistory(it))
        }
    }

    @OptIn(FlowPreview::class)
    private fun onFetchActivityHistory() = viewModelScope.launch {
        { activity: AppCompatActivity ->
            flowOf(
                OffsetDateTime.now().let {
                    listOf(
                        it.minusHours(2),
                        it.plusHours(2)
                    ).map { dateTime ->
                        dateTime.toEpochSecond()
                    }
                }.let {
                    DataReadRequest.Builder().run {
                        dataTypes.forEach { read(it) }
                        setTimeRange(it[0], it[1], TimeUnit.SECONDS)
                        enableServerQueries()
                        build()
                    }
                }
            ).let {
                combine(it, effectiveAccount) { dataReadRequest, account ->
                    Fitness.getHistoryClient(activity, account)
                        .readData(dataReadRequest)
                        .await()
                }
            }.map {
                if (it.status.isSuccess) {
                    it.dataSets
                } else {
                    throw Exception("fetch activity history failure")
                }
            }.flatMapMerge {
                it.asFlow()
            }.flowOn(Dispatchers.IO).onStart {
                stateInternal.update {
                    it.copy(
                        isFetchingActivityHistory = true,
                        log = "fetch activity history now ..."
                    )
                }
            }.onEach {
                stateInternal.update { state -> state.copy(log = toString(it)) }
            }.onEmpty {
                stateInternal.update { it.copy(log = "no activity") }
            }.catch {
                stateInternal.update { it.copy(log = "") }
                effectInternal.emit(Effect.DisplayFetchActivityHistoryError)
            }.onCompletion {
                stateInternal.update { it.copy(isFetchingActivityHistory = false) }
            }.launchIn(activity.lifecycleScope)
        }.let {
            effectInternal.emit(Effect.FetchActivityHistory(it))
        }
    }

    @OptIn(FlowPreview::class)
    private fun onGetDataSource() = viewModelScope.launch {
        { activity: AppCompatActivity ->
            flowOf(
                DataSourcesRequest.Builder()
                    .setDataTypes(*dataTypes.toTypedArray())
                    .setDataSourceTypes(DataSource.TYPE_DERIVED)
                    .build()
            ).let {
                combine(it, effectiveAccount) { dataSourcesRequest, account ->
                    Fitness.getSensorsClient(activity, account)
                        .findDataSources(dataSourcesRequest)
                        .await()
                }
            }.flatMapMerge {
                it.asFlow()
            }.flowOn(Dispatchers.IO).onStart {
                stateInternal.update {
                    it.copy(
                        isGettingDataSources = true,
                        log = "get data sources now ..."
                    )
                }
            }.onEach {
                stateInternal.update { state -> state.copy(log = toString(it)) }
            }.catch {
                stateInternal.update { it.copy(log = "") }
                effectInternal.emit(Effect.DisplayGetDataSourceError)
            }.onCompletion {
                stateInternal.update { it.copy(isGettingDataSources = false) }
            }.launchIn(activity.lifecycleScope)
        }.let {
            effectInternal.emit(Effect.GetDataSource(it))
        }
    }

    data class State(
        val isLoginProcessing: Boolean = false,
        val sessionStatus: SessionState = SessionState.IDLE,
        val isFetchingSessionHistory: Boolean = false,
        val isFetchingActivityHistory: Boolean = false,
        val isGettingDataSources: Boolean = false,
        val log: String = ""
    )

    @Immutable
    sealed interface Effect {

        data class LaunchLogin(
            val createGoogleSignInIntent: (Activity) -> Intent
        ) : Effect

        object DisplayLoginError : Effect

        data class StartSession(
            val startSession: (AppCompatActivity) -> Job
        ) : Effect

        object DisplayStartSessionError : Effect

        data class StopSession(
            val stopSession: (AppCompatActivity) -> Job
        ) : Effect

        object DisplayStopSessionError : Effect

        data class FetchSessionHistory(
            val fetchSessionHistory: (AppCompatActivity) -> Job
        ) : Effect

        object DisplayFetchSessionHistoryError : Effect

        data class FetchActivityHistory(
            val fetchActivityHistory: (AppCompatActivity) -> Job
        ) : Effect

        object DisplayFetchActivityHistoryError : Effect

        data class GetDataSource(
            val getDataSource: (AppCompatActivity) -> Job
        ) : Effect

        object DisplayGetDataSourceError : Effect
    }

    @Immutable
    sealed interface Event {

        data class LoggedIn(
            val account: GoogleSignInAccount
        ) : Event

        object LoginError : Event
        object StartSessionRequest : Event
        object StopSessionRequest : Event
        object FetchSessionHistory : Event
        object FetchActivityHistory : Event
        object GetDataSource : Event
    }

    enum class SessionState {

        IDLE, STARTING, STARTED, STOPPING
    }
}
