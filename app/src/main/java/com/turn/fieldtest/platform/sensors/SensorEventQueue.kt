package com.turn.fieldtest.platform.sensors

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow

/** Single runtime consumer; foreground stop clears pending callbacks before another session starts. */
internal class SensorEventQueue {
    private val channel = Channel<SensorSample>(Channel.UNLIMITED)
    val samples = channel.receiveAsFlow()
    fun offer(sample: SensorSample) { check(channel.trySend(sample).isSuccess) }
    fun clear() { while (channel.tryReceive().isSuccess) { /* Drain ended foreground session. */ } }
}
