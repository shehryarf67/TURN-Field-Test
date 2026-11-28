package com.turn.fieldtest.mapper.core

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sqrt

/** Brief tracking gaps can recover. A confirmed frame break requires a new section. */
class CaptureGuard(private val graceNanos: Long = 3_000_000_000L) {
    enum class State { STALE, LIMITED, RECOVERING, TRACKING, LOST, HOT }
    data class CameraPose(val position: V3, val quaternion: FloatArray) {
        fun jumpsFrom(previous: CameraPose): Boolean {
            val a = position
            val b = previous.position
            if (hypot(hypot(a.x - b.x, a.y - b.y), a.z - b.z) > 1.0) return true
            val dot = quaternion.indices.sumOf { quaternion[it].toDouble() * previous.quaternion[it] }
            val norm = sqrt(quaternion.sumOf { it.toDouble() * it } *
                previous.quaternion.sumOf { it.toDouble() * it })
            // q and -q describe the same rotation.
            return norm > 0 && abs(dot) / norm < cos(Math.toRadians(30.0) / 2)
        }
    }

    private var timestamp=0L
    private var lost=false
    private var lostSince: Long? = null
    private var lastPose: CameraPose? = null

    fun observe(time: Long, tracking: Boolean, referenceEstablished: Boolean, thermal: Int, pose: CameraPose? = null): State {
        if(time<=timestamp || time<=0) return State.STALE
        timestamp=time
        if (lost) return State.LOST
        if (!referenceEstablished) {
            lostSince = null
        } else {
            if (!tracking && lostSince == null) lostSince = time
            val started = lostSince
            if (started != null) {
                lost = time - started >= graceNanos ||
                    (tracking && lastPose?.let { pose?.jumpsFrom(it) } == true)
                if (lost) return State.LOST
                if (!tracking) return State.RECOVERING
                lostSince = null
            }
        }
        if (!tracking) return State.LIMITED
        lastPose = pose
        return if (thermal >= 4) State.HOT else State.TRACKING
    }
    companion object {
        enum class Entry { MEASURED_ONLY, REQUEST_PERMISSION, INSTALL_SERVICES, START }
        fun entry(supported: Boolean,permission: Boolean,servicesInstalled: Boolean)=when {
            !supported->Entry.MEASURED_ONLY; !permission->Entry.REQUEST_PERMISSION; !servicesInstalled->Entry.INSTALL_SERVICES; else->Entry.START
        }
    }
}
