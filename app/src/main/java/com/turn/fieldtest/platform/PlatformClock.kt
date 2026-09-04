package com.turn.fieldtest.platform

import android.os.SystemClock

interface PlatformClock {
    fun epochMillis(): Long
    fun elapsedRealtimeMillis(): Long
    fun elapsedRealtimeNanos(): Long
}

object SystemPlatformClock : PlatformClock {
    override fun epochMillis(): Long = System.currentTimeMillis()
    override fun elapsedRealtimeMillis(): Long = SystemClock.elapsedRealtime()
    override fun elapsedRealtimeNanos(): Long = SystemClock.elapsedRealtimeNanos()
}
