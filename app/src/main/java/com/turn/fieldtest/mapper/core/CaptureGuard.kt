package com.turn.fieldtest.mapper.core

/** A fresh camera session has a fresh guard. Tracking recovery never silently rejoins a lost section. */
class CaptureGuard {
    enum class State { STALE, LIMITED, TRACKING, LOST, HOT }
    private var timestamp=0L
    private var lost=false
    fun observe(time: Long,tracking: Boolean,referenceEstablished: Boolean,thermal: Int): State {
        if(time<=timestamp || time<=0) return State.STALE
        timestamp=time
        if(!tracking && referenceEstablished) lost=true
        return when {lost->State.LOST; !tracking->State.LIMITED; thermal>=4->State.HOT; else->State.TRACKING}
    }
    companion object {
        enum class Entry { MEASURED_ONLY, REQUEST_PERMISSION, INSTALL_SERVICES, START }
        fun entry(supported: Boolean,permission: Boolean,servicesInstalled: Boolean)=when {
            !supported->Entry.MEASURED_ONLY; !permission->Entry.REQUEST_PERMISSION; !servicesInstalled->Entry.INSTALL_SERVICES; else->Entry.START
        }
    }
}
