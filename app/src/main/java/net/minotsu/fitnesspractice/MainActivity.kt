package net.minotsu.fitnesspractice

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.fitness.Fitness
import com.google.android.gms.fitness.FitnessActivities
import com.google.android.gms.fitness.FitnessOptions
import com.google.android.gms.fitness.data.DataSet
import com.google.android.gms.fitness.data.DataSource
import com.google.android.gms.fitness.data.DataType
import com.google.android.gms.fitness.data.Session
import com.google.android.gms.fitness.request.DataReadRequest
import com.google.android.gms.fitness.request.DataSourcesRequest
import com.google.android.gms.fitness.request.SessionInsertRequest
import com.google.android.gms.fitness.request.SessionReadRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import net.minotsu.fitnesspractice.databinding.ActivityMainBinding
import permissions.dispatcher.NeedsPermission
import permissions.dispatcher.RuntimePermissions
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.*
import java.util.concurrent.TimeUnit

@RuntimePermissions
class MainActivity : AppCompatActivity(R.layout.activity_main) {

    private lateinit var binding: ActivityMainBinding

    private val newAccount: MutableSharedFlow<GoogleSignInAccount> = MutableSharedFlow()

    private val stepCountDataSource: DataSource by lazy {
        DataSource.Builder()
            .setAppPackageName("com.google.android.gms")
            //.setDevice(Device.getLocalDevice(this))
            .setDataType(DataType.TYPE_STEP_COUNT_DELTA)
            .setStreamName("overlay_explicit_input_local")
            //.setType(DataSource.TYPE_RAW)
            .setType(DataSource.TYPE_DERIVED)
            .build()
    }

    private val loginLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode == Activity.RESULT_OK) {
                lifecycleScope.launch { newAccount.emit(getAccount()) }
                setup()
            } else {
                Toast.makeText(this, getString(R.string.login_failure), Toast.LENGTH_SHORT).show()
            }
        }

    @OptIn(FlowPreview::class)
    private val unsubscribeAll: Flow<Unit> =
        flow {
            Fitness.getRecordingClient(this@MainActivity, getAccount())
                .listSubscriptions()
                .await()
                .let { emit(it) }
        }.flowOn(Dispatchers.IO).onStart {
            binding.startSessionButton.isEnabled = false
            binding.stopSessionButton.isEnabled = false
            binding.logoutButton.isEnabled = false
        }.flowOn(Dispatchers.Main).map {
            it.map { subscription ->
                Log.i("Unsubscribe subscription", subscription.toDebugString())

                flow {
                    Fitness.getRecordingClient(this@MainActivity, getAccount())
                        .unsubscribe(subscription)
                        .await()
                        .let { ret -> emit(ret) }
                }
            }
        }.flatMapMerge {
            combine(it) {}
        }.flowOn(Dispatchers.IO).onEach {
            Log.i("UnsubscribeAll", "success")
            binding.startSessionButton.isEnabled = true
            binding.logoutButton.isEnabled = true
        }

    private var sessionStartTime: OffsetDateTime? = null

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        when (resultCode) {
            Activity.RESULT_OK -> when (requestCode) {
                GOOGLE_REQUEST_PERMISSIONS_REQUEST_CODE -> setup()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater).apply {
            setContentView(root)
            startSessionButton.setOnClickListener { onStartSessionButtonClicked() }
            stopSessionButton.setOnClickListener { onStopSessionButtonClicked() }
            logoutButton.setOnClickListener { onLogoutButtonClicked() }
            activityHistoryButton.setOnClickListener { onActivityHistoryButtonClicked() }
            sessionHistoryButton.setOnClickListener { onSessionHistoryButtonClicked() }
            dataSourceButton.setOnClickListener { onDataSourceButtonClicked() }
        }

    }

    private fun setup() {
        binding.startSessionButton.isEnabled = true
        binding.logoutButton.isEnabled = true
        binding.activityHistoryButton.isEnabled = true
        binding.sessionHistoryButton.isEnabled = true
        binding.dataSourceButton.isEnabled = true
    }

    private fun login() {
        flowOf(
            FitnessOptions.builder()
                .addDataType(DataType.TYPE_STEP_COUNT_DELTA)
                .addDataType(DataType.TYPE_STEP_COUNT_DELTA, FitnessOptions.ACCESS_WRITE)
                .addDataType(DataType.TYPE_STEP_COUNT_CUMULATIVE)
                .addDataType(DataType.TYPE_STEP_COUNT_CUMULATIVE, FitnessOptions.ACCESS_WRITE)
                .addDataType(DataType.TYPE_STEP_COUNT_CADENCE)
                .addDataType(DataType.TYPE_STEP_COUNT_CADENCE, FitnessOptions.ACCESS_WRITE)
                .addDataType(DataType.TYPE_DISTANCE_DELTA)
                .addDataType(DataType.TYPE_DISTANCE_DELTA, FitnessOptions.ACCESS_WRITE)
                .addDataType(DataType.TYPE_CALORIES_EXPENDED)
                .addDataType(DataType.TYPE_CALORIES_EXPENDED, FitnessOptions.ACCESS_WRITE)
                .build()
        ).map {
            /*
            GoogleSignInOptions.Builder()
                .addExtension(it)
                .build()
             */
            it to GoogleSignIn.getAccountForExtension(this, it)
        }.onEach { (fitnessOptions, account) ->
            //GoogleSignIn.getClient(this, it).signInIntent
            if (GoogleSignIn.hasPermissions(account, fitnessOptions)) {
                setup()
            } else {
                GoogleSignIn.requestPermissions(
                    this,
                    GOOGLE_REQUEST_PERMISSIONS_REQUEST_CODE,
                    account,
                    fitnessOptions
                )
            }
        }.launchIn(lifecycleScope)
    }

    private fun onStartSessionButtonClicked() = startSessionWithPermissionCheck()

    @OptIn(FlowPreview::class)
    @RequiresApi(Build.VERSION_CODES.Q)
    @NeedsPermission(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.ACTIVITY_RECOGNITION,
        //Manifest.permission.BODY_SENSORS
    )
    fun startSession() {
        flow {
            listOf(
                DataType.TYPE_STEP_COUNT_DELTA,
                DataType.TYPE_DISTANCE_DELTA,
                DataType.TYPE_CALORIES_EXPENDED
            ).map {
                flow {
                    Fitness.getRecordingClient(this@MainActivity, getAccount())
                        .subscribe(it)
                        .await()
                        .let { ret -> emit(ret) }
                }
            }.toList().let {
                emit(it)
            }
            /*
            listOf(
                flow {
                    Fitness.getRecordingClient(this@MainActivity, getAccount())
                        .subscribe(stepCountDataSource)
                        .await()
                        .let { ret -> emit(ret) }
                }
            ).let {
                emit(it)
            }
             */
        }.flowOn(Dispatchers.IO).onStart {
            sessionStartTime = OffsetDateTime.now()
            binding.startSessionButton.isEnabled = false
            binding.stopSessionButton.isEnabled = false
            binding.logoutButton.isEnabled = false
        }.flatMapMerge {
            combine(it) {}
        }.onEach {
            Log.i("Subscribe", "success")
            binding.stopSessionButton.isEnabled = true
            binding.logoutButton.isEnabled = true
        }.launchIn(lifecycleScope)
    }

    private fun onStopSessionButtonClicked() = stopSessionWithPermissionCheck()

    @OptIn(FlowPreview::class)
    @RequiresApi(Build.VERSION_CODES.Q)
    @NeedsPermission(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.ACTIVITY_RECOGNITION,
        //Manifest.permission.BODY_SENSORS
    )
    fun stopSession() {
        flow {
            val session = Session.Builder()
                .setIdentifier(UUID.randomUUID().toString())
                .setName("FitnessPracticeSession")
                //.setDescription()
                .setActivity(FitnessActivities.RUNNING)
                .setStartTime(checkNotNull(sessionStartTime).toEpochSecond(), TimeUnit.SECONDS)
                .setEndTime(OffsetDateTime.now().toEpochSecond(), TimeUnit.SECONDS)
                //.setActiveTime()
                .build()

            val request = SessionInsertRequest.Builder()
                .setSession(session)
                .build()

            Fitness.getSessionsClient(this@MainActivity, getAccount())
                .insertSession(request)
                .await()
                .let { emit(it) }
        }.flatMapMerge {
            unsubscribeAll
        }.launchIn(lifecycleScope)
    }

    private fun onLogoutButtonClicked() = logoutWithPermissionCheck()

    @RequiresApi(Build.VERSION_CODES.Q)
    @NeedsPermission(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.ACTIVITY_RECOGNITION,
        //Manifest.permission.BODY_SENSORS
    )
    fun logout() {
        flow {
            emit(Fitness.getConfigClient(this@MainActivity, getAccount()))
        }.onStart {
            binding.startSessionButton.isEnabled = true
            binding.stopSessionButton.isEnabled = false
            binding.logoutButton.isEnabled = false
            binding.activityHistoryButton.isEnabled = false
            binding.sessionHistoryButton.isEnabled = false
            binding.dataSourceButton.isEnabled = false
        }.flowOn(Dispatchers.Main).map {
            it.disableFit().await()
        }.flowOn(Dispatchers.IO).onEach {
            Log.i("Logout", "success")
            binding.startSessionButton.isEnabled = true
            binding.activityHistoryButton.isEnabled = true
            binding.sessionHistoryButton.isEnabled = true
            binding.dataSourceButton.isEnabled = true
        }.catch {
            Log.i("Logout", "failed $it")
        }.launchIn(lifecycleScope)
    }

    private fun onActivityHistoryButtonClicked() = fetchActivityHistoryWithPermissionCheck()

    @RequiresApi(Build.VERSION_CODES.Q)
    @NeedsPermission(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.ACTIVITY_RECOGNITION,
        //Manifest.permission.BODY_SENSORS
    )
    fun fetchActivityHistory() {
        @OptIn(FlowPreview::class)
        flow {
            OffsetDateTime.now().let {
                listOf(
                    it.minusHours(2),
                    it.plusHours(2)
                ).map { dateTime ->
                    dateTime.toEpochSecond()
                }
            }.let {
                /*
                val dataSource = DataSource.Builder()
                    //.setAppPackageName(this@MainActivity)
                    //.setDevice(Device.getLocalDevice(this@MainActivity))
                    .setDataType(DataType.TYPE_STEP_COUNT_DELTA)
                    //.setStreamName(STREAM_NAME)
                    .setType(DataSource.TYPE_RAW)
                    .build()
                 */

                DataReadRequest.Builder()
                    .read(stepCountDataSource)
                    //.read(dataSource)
                    //.read(DataType.TYPE_STEP_COUNT_DELTA)
                    //.read(DataType.TYPE_DISTANCE_DELTA)
                    //.read(DataType.TYPE_CALORIES_EXPENDED)
                    .setTimeRange(it[0], it[1], TimeUnit.SECONDS)
                    .enableServerQueries()
                    .build()
            }.let {
                emit(it)
            }
        }.onStart {
            binding.activityHistoryButton.isEnabled = false
            binding.logView.text = ""
        }.flowOn(Dispatchers.Main).map {
            Fitness.getHistoryClient(this@MainActivity, getAccount())
                .readData(it)
                .await()
        }.flowOn(Dispatchers.IO).onEach {
            when {
                it.status.isSuccess -> "Success"
                it.status.isCanceled -> "Canceled"
                it.status.isInterrupted -> "Interrupted"
                else -> error("impossible")
            }.let { msg ->
                Log.i("FetHistoryStatus", msg)
            }

            binding.activityHistoryButton.isEnabled = true
        }.map {
            if (it.status.isSuccess) it.dataSets else emptyList()
        }.flatMapMerge {
            it.asFlow()
        }.map {
            toString(it)
        }.onEach {
            Log.i("Activity History", it)
            val text = binding.logView.text.toString() + it
            binding.logView.text = text
        }.onEmpty {
            val log = "no data set"
            Log.i("Activity History", log)
            binding.logView.text = log
        }.launchIn(lifecycleScope)
    }

    private fun toString(dataSet: DataSet): String = buildString {
        appendLine("DataType:${dataSet.dataType}")
        appendLine(toString(dataSet.dataSource))

        if (dataSet.isEmpty) {
            appendLine()
            appendLine("No data points")
        } else {
            dataSet.dataPoints.forEach {
                appendLine()
                appendLine("streamIdentifier:${it.dataSource.streamIdentifier}")
                appendLine("streamIdentifier(original):${it.originalDataSource.streamIdentifier}")
                appendLine("DataType:${it.dataType.name}")
                appendLine("StartTime:${getOffsetDateTime(it.getStartTime(TimeUnit.SECONDS))}")
                appendLine("EndTime:${getOffsetDateTime(it.getEndTime(TimeUnit.SECONDS))}")

                it.dataType.fields.forEach { field ->
                    appendLine("${field.name}:${it.getValue(field)}")
                }
            }
        }

        appendLine()
    }

    private fun onSessionHistoryButtonClicked() = fetchSessionHistoryWithPermissionCheck()

    @RequiresApi(Build.VERSION_CODES.Q)
    @NeedsPermission(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.ACTIVITY_RECOGNITION,
        //Manifest.permission.BODY_SENSORS
    )
    fun fetchSessionHistory() {
        @OptIn(FlowPreview::class)
        flow {
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
            }.let {
                emit(it)
            }
        }.onStart {
            binding.sessionHistoryButton.isEnabled = false
            binding.logView.text = ""
        }.flowOn(Dispatchers.Main).map {
            Fitness.getSessionsClient(this@MainActivity, getAccount())
                .readSession(it)
                .await()
        }.flowOn(Dispatchers.IO).onEach {
            when {
                it.status.isSuccess -> "Success"
                it.status.isCanceled -> "Canceled"
                it.status.isInterrupted -> "Interrupted"
                else -> error("impossible")
            }.let { msg ->
                Log.i("FetSessionStatus", msg)
            }

            binding.sessionHistoryButton.isEnabled = true
        }.map { response ->
            if (response.status.isSuccess) {
                response.sessions.associateWith { response.getDataSet(it) }.toList()
            } else {
                emptyList()
            }
        }.flatMapMerge {
            it.asFlow()
        }.map { (session, dataSets) ->
            toString(session, dataSets)
        }.onEach {
            Log.i("Session History", it)
            val text = binding.logView.text.toString() + it
            binding.logView.text = text
        }.onEmpty {
            val log = "no session"
            Log.i("Session History", log)
            binding.logView.text = log
        }.launchIn(lifecycleScope)
    }

    private fun toString(session: Session, dataSets: List<DataSet>): String = buildString {
        appendLine("identifier:${session.identifier}")
        appendLine("name:${session.name}")
        appendLine("description:${session.description}")
        appendLine("activity:${session.activity}")
        appendLine("isOngoing:${session.isOngoing}")
        appendLine("appPackageName:${session.appPackageName}")
        appendLine("StartTime:${getOffsetDateTime(session.getStartTime(TimeUnit.SECONDS))}")
        appendLine("EndTime:${getOffsetDateTime(session.getEndTime(TimeUnit.SECONDS))}")
        appendLine("hasActiveTime:${session.hasActiveTime()}")

        if (session.hasActiveTime()) {
            appendLine("ActiveTime:${getOffsetDateTime(session.getActiveTime(TimeUnit.SECONDS))}")
        }

        appendLine()
        dataSets.forEach { toString(it) }
        appendLine()
    }

    private fun onDataSourceButtonClicked() = fetchDataSourceWithPermissionCheck()

    @RequiresApi(Build.VERSION_CODES.Q)
    @NeedsPermission(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.ACTIVITY_RECOGNITION,
        //Manifest.permission.BODY_SENSORS
    )
    fun fetchDataSource() {
        @OptIn(FlowPreview::class)
        flow {
            DataSourcesRequest.Builder()
                .setDataTypes(
                    DataType.TYPE_STEP_COUNT_DELTA,
                    DataType.TYPE_DISTANCE_DELTA,
                    DataType.TYPE_CALORIES_EXPENDED
                )
                //.setDataSourceTypes(DataSource.TYPE_RAW)
                .setDataSourceTypes(DataSource.TYPE_DERIVED)
                .build()
                .let { emit(it) }
        }.onStart {
            binding.dataSourceButton.isEnabled = false
            binding.logView.text = ""
        }.flowOn(Dispatchers.Main).map {
            Fitness.getSensorsClient(this@MainActivity, getAccount())
                .findDataSources(it)
                .await()
        }.flowOn(Dispatchers.IO).onEach {
            binding.dataSourceButton.isEnabled = true
        }.flatMapMerge {
            it.asFlow()
        }.map {
            toString(it)
        }.onEmpty {
            emit("no data source")
        }.onEach {
            Log.i("DataSource", it)
            val text = binding.logView.text.toString() + it
            binding.logView.text = text
        }.launchIn(lifecycleScope)
    }

    private fun toString(dataSource: DataSource): String = buildString {
        dataSource.run {
            appendLine("PackageName:$appPackageName")
            appendLine("DataType:$dataType")
            appendLine("Device:$device")
            appendLine("StreamIdentifier:$streamIdentifier")
            appendLine("StreamName:$streamName")
            appendLine("type:$type")
        }
    }

    private fun getOffsetDateTime(epochSeconds: Long): OffsetDateTime {
        return OffsetDateTime.ofInstant(Instant.ofEpochSecond(epochSeconds), ZoneOffset.UTC)
            .withOffsetSameInstant(ZoneOffset.ofHours(9))
    }

    private suspend fun getAccount(): GoogleSignInAccount {
        return GoogleSignIn.getLastSignedInAccount(this) ?: run {
            login()
            newAccount.single()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        onRequestPermissionsResult(requestCode, grantResults)
    }

    private companion object {
        const val GOOGLE_REQUEST_PERMISSIONS_REQUEST_CODE: Int = 999
        const val STREAM_NAME: String = "FitnessPractice"
    }
}
