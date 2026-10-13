package com.turn.fieldtest.platform.sensors

import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class SensorEventQueueTest {
    private fun sample(time: Long, step: Boolean = false) = SensorSample(
        if (step) TurnSensorType.STEP_DETECTOR else TurnSensorType.GAME_ROTATION_VECTOR,
        if (step) SensorSampleKind.STEP_DETECTED else SensorSampleKind.RELATIVE_ROTATION_VECTOR,
        listOf(0f, 0f, 0f), time, time / 1_000_000, 3, simulated = true)

    @Test fun busyConsumerDoesNotLoseStepEventsBehindMoreThan128HeadingFrames() = runBlocking {
        val queue = SensorEventQueue()
        val sent = (1L..600L).map { sample(it, it % 100 == 0L) }
        sent.forEach(queue::offer)
        val received = queue.samples.take(sent.size).toList()
        assertEquals(sent, received)
        assertEquals(6, received.count { it.kind == SensorSampleKind.STEP_DETECTED })
    }

    @Test fun endedForegroundSessionCannotReplayCallbacksIntoNextSession() = runBlocking {
        val queue = SensorEventQueue()
        queue.offer(sample(1, true)); queue.clear(); queue.offer(sample(2, true))
        assertEquals(2L, queue.samples.take(1).toList().single().sensorTimestampNanos)
    }
}
