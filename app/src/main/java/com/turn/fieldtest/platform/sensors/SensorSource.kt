package com.turn.fieldtest.platform.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.turn.fieldtest.platform.PlatformClock
import com.turn.fieldtest.platform.SystemPlatformClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class TurnSensorType {
    STEP_DETECTOR,
    STEP_COUNTER,
    ACCELEROMETER,
    GYROSCOPE,
    GAME_ROTATION_VECTOR,
    ROTATION_VECTOR,
    BAROMETER,
}

enum class SensorSampleKind {
    STEP_DETECTED,
    STEP_COUNTER_TOTAL,
    ACCELERATION,
    ANGULAR_VELOCITY,
    RELATIVE_ROTATION_VECTOR,
    ROTATION_VECTOR,
    PRESSURE,
    ACCURACY_CHANGED,
}

data class SensorAvailability(
    val type: TurnSensorType,
    val available: Boolean,
    val name: String? = null,
    val vendor: String? = null,
    val powerMilliAmps: Float? = null,
)

data class SensorSourceState(
    val running: Boolean = false,
    val selectedStepSource: TurnSensorType? = null,
    val selectedHeadingSource: TurnSensorType? = null,
    val availability: List<SensorAvailability> = emptyList(),
)

data class SensorSample(
    val type: TurnSensorType,
    val kind: SensorSampleKind,
    val values: List<Float>,
    val sensorTimestampNanos: Long,
    val receivedAtEpochMillis: Long,
    val accuracy: Int,
    val simulated: Boolean,
)

data class SensorSourceConfig(
    val enableAccelerometerFallback: Boolean = false,
    val allowAbsoluteRotationVectorHeading: Boolean = false,
    val samplingPeriodUs: Int = SensorManager.SENSOR_DELAY_GAME,
)

interface SensorSource {
    val samples: SharedFlow<SensorSample>
    val state: StateFlow<SensorSourceState>
    fun start()
    fun stop()
}

/** Lifecycle adapter that keeps collection strictly foreground-only. */
class LifecycleSensorController(
    private val source: SensorSource,
) : DefaultLifecycleObserver {
    override fun onStart(owner: LifecycleOwner) = source.start()
    override fun onStop(owner: LifecycleOwner) = source.stop()
}

class AndroidSensorSource(
    context: Context,
    private val config: SensorSourceConfig = SensorSourceConfig(),
    private val sensorManager: SensorManager = context.applicationContext.getSystemService(SensorManager::class.java),
    private val clock: PlatformClock = SystemPlatformClock,
) : SensorSource, SensorEventListener {
    private val mutableSamples = MutableSharedFlow<SensorSample>(extraBufferCapacity = 128)
    private val mutableState = MutableStateFlow(SensorSourceState())
    private val sensorsByAndroidType: Map<Int, Sensor?> = sensorTypes.associate { (_, androidType) ->
        androidType to sensorManager.getDefaultSensor(androidType)
    }

    override val samples: SharedFlow<SensorSample> = mutableSamples.asSharedFlow()
    override val state: StateFlow<SensorSourceState> = mutableState.asStateFlow()

    init {
        mutableState.value = stateFromSensors(running = false)
    }

    override fun start() {
        if (mutableState.value.running) return
        val selectedStep = selectedStepSource()
        sensorsToRegister(selectedStep).forEach { sensor ->
            sensorManager.registerListener(this, sensor, config.samplingPeriodUs)
        }
        mutableState.value = stateFromSensors(running = true)
    }

    override fun stop() {
        sensorManager.unregisterListener(this)
        mutableState.value = stateFromSensors(running = false)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val mapped = event.sensor.type.toTurnSensorAndKind() ?: return
        mutableSamples.tryEmit(
            SensorSample(
                type = mapped.first,
                kind = mapped.second,
                values = event.values.copyOf().toList(),
                sensorTimestampNanos = event.timestamp,
                receivedAtEpochMillis = clock.epochMillis(),
                accuracy = event.accuracy,
                simulated = false,
            ),
        )
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
        val type = sensor.type.toTurnSensorAndKind()?.first ?: return
        mutableSamples.tryEmit(
            SensorSample(
                type = type,
                kind = SensorSampleKind.ACCURACY_CHANGED,
                values = emptyList(),
                sensorTimestampNanos = clock.elapsedRealtimeNanos(),
                receivedAtEpochMillis = clock.epochMillis(),
                accuracy = accuracy,
                simulated = false,
            ),
        )
    }

    private fun sensorsToRegister(selectedStep: TurnSensorType?): List<Sensor> = buildList {
        sensorTypes.forEach { (turnType, androidType) ->
            val sensor = sensorsByAndroidType[androidType] ?: return@forEach
            val shouldRegister = when (turnType) {
                TurnSensorType.ACCELEROMETER ->
                    selectedStep == TurnSensorType.ACCELEROMETER || config.enableAccelerometerFallback
                else -> true
            }
            if (shouldRegister) add(sensor)
        }
    }

    private fun selectedStepSource(): TurnSensorType? = when {
        sensorsByAndroidType[Sensor.TYPE_STEP_DETECTOR] != null -> TurnSensorType.STEP_DETECTOR
        config.enableAccelerometerFallback && sensorsByAndroidType[Sensor.TYPE_ACCELEROMETER] != null ->
            TurnSensorType.ACCELEROMETER
        else -> null
    }

    private fun selectedHeadingSource(): TurnSensorType? = when {
        sensorsByAndroidType[Sensor.TYPE_GAME_ROTATION_VECTOR] != null -> TurnSensorType.GAME_ROTATION_VECTOR
        sensorsByAndroidType[Sensor.TYPE_GYROSCOPE] != null -> TurnSensorType.GYROSCOPE
        config.allowAbsoluteRotationVectorHeading && sensorsByAndroidType[Sensor.TYPE_ROTATION_VECTOR] != null ->
            TurnSensorType.ROTATION_VECTOR
        else -> null
    }

    private fun stateFromSensors(running: Boolean) = SensorSourceState(
        running = running,
        selectedStepSource = selectedStepSource(),
        selectedHeadingSource = selectedHeadingSource(),
        availability = sensorTypes.map { (turnType, androidType) ->
            val sensor = sensorsByAndroidType[androidType]
            SensorAvailability(
                type = turnType,
                available = sensor != null,
                name = sensor?.name,
                vendor = sensor?.vendor,
                powerMilliAmps = sensor?.power,
            )
        },
    )

    private fun Int.toTurnSensorAndKind(): Pair<TurnSensorType, SensorSampleKind>? = when (this) {
        Sensor.TYPE_STEP_DETECTOR -> TurnSensorType.STEP_DETECTOR to SensorSampleKind.STEP_DETECTED
        Sensor.TYPE_STEP_COUNTER -> TurnSensorType.STEP_COUNTER to SensorSampleKind.STEP_COUNTER_TOTAL
        Sensor.TYPE_ACCELEROMETER -> TurnSensorType.ACCELEROMETER to SensorSampleKind.ACCELERATION
        Sensor.TYPE_GYROSCOPE -> TurnSensorType.GYROSCOPE to SensorSampleKind.ANGULAR_VELOCITY
        Sensor.TYPE_GAME_ROTATION_VECTOR -> TurnSensorType.GAME_ROTATION_VECTOR to SensorSampleKind.RELATIVE_ROTATION_VECTOR
        Sensor.TYPE_ROTATION_VECTOR -> TurnSensorType.ROTATION_VECTOR to SensorSampleKind.ROTATION_VECTOR
        Sensor.TYPE_PRESSURE -> TurnSensorType.BAROMETER to SensorSampleKind.PRESSURE
        else -> null
    }

    companion object {
        private val sensorTypes = listOf(
            TurnSensorType.STEP_DETECTOR to Sensor.TYPE_STEP_DETECTOR,
            TurnSensorType.STEP_COUNTER to Sensor.TYPE_STEP_COUNTER,
            TurnSensorType.ACCELEROMETER to Sensor.TYPE_ACCELEROMETER,
            TurnSensorType.GYROSCOPE to Sensor.TYPE_GYROSCOPE,
            TurnSensorType.GAME_ROTATION_VECTOR to Sensor.TYPE_GAME_ROTATION_VECTOR,
            TurnSensorType.ROTATION_VECTOR to Sensor.TYPE_ROTATION_VECTOR,
            TurnSensorType.BAROMETER to Sensor.TYPE_PRESSURE,
        )
    }
}

/** Prerecorded emulator source; every emitted sample is explicitly labelled simulated. */
class ReplaySensorSource(
    private val scope: CoroutineScope,
    private val trace: List<ReplaySensorSample>,
    private val clock: PlatformClock = SystemPlatformClock,
) : SensorSource {
    private val mutableSamples = MutableSharedFlow<SensorSample>(extraBufferCapacity = 128)
    private val mutableState = MutableStateFlow(
        SensorSourceState(
            availability = TurnSensorType.entries.map { SensorAvailability(it, true, name = "SIMULATED ${it.name}") },
            selectedStepSource = TurnSensorType.STEP_DETECTOR,
            selectedHeadingSource = TurnSensorType.GAME_ROTATION_VECTOR,
        ),
    )
    private var replayJob: Job? = null

    override val samples: SharedFlow<SensorSample> = mutableSamples.asSharedFlow()
    override val state: StateFlow<SensorSourceState> = mutableState.asStateFlow()

    override fun start() {
        if (replayJob?.isActive == true) return
        mutableState.value = mutableState.value.copy(running = true)
        replayJob = scope.launch {
            var previousOffset = 0L
            for (item in trace.sortedBy(ReplaySensorSample::offsetMillis)) {
                if (!isActive) break
                delay((item.offsetMillis - previousOffset).coerceAtLeast(0L))
                previousOffset = item.offsetMillis
                mutableSamples.emit(
                    SensorSample(
                        type = item.type,
                        kind = item.kind,
                        values = item.values,
                        sensorTimestampNanos = clock.elapsedRealtimeNanos(),
                        receivedAtEpochMillis = clock.epochMillis(),
                        accuracy = item.accuracy,
                        simulated = true,
                    ),
                )
            }
            mutableState.value = mutableState.value.copy(running = false)
        }
    }

    override fun stop() {
        replayJob?.cancel()
        replayJob = null
        mutableState.value = mutableState.value.copy(running = false)
    }
}

data class ReplaySensorSample(
    val offsetMillis: Long,
    val type: TurnSensorType,
    val kind: SensorSampleKind,
    val values: List<Float>,
    val accuracy: Int = SensorManager.SENSOR_STATUS_ACCURACY_HIGH,
)
