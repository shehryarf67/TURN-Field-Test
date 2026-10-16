package com.turn.fieldtest.mapper.core

import kotlin.math.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random

data class DepthRaster(val width: Int, val height: Int, val rowStride: Int, val pixelStride: Int, val bytes: ByteArray)
data class DepthIntrinsics(val fx: Double,val fy: Double,val cx: Double,val cy: Double,val width: Int,val height: Int)

object DepthGeometry {
    /** Camera image coordinates are unrotated: right/down pixels -> AR camera right/up/back. */
    fun backProject(u: Int,v: Int,metres: Double,depthWidth: Int,depthHeight: Int,k: DepthIntrinsics): V3 {
        require(k.fx>0 && k.fy>0 && k.width>0 && k.height>0 && depthWidth>0 && depthHeight>0 && metres>0)
        val sx=depthWidth.toDouble()/k.width; val sy=depthHeight.toDouble()/k.height
        return V3((u-k.cx*sx)*metres/(k.fx*sx),-(v-k.cy*sy)*metres/(k.fy*sy),-metres)
    }
    fun world(p: V3,m: FloatArray): V3 {
        require(m.size==16 && m.all { it.isFinite() })
        return V3(m[0]*p.x+m[4]*p.y+m[8]*p.z+m[12],m[1]*p.x+m[5]*p.y+m[9]*p.z+m[13],m[2]*p.x+m[6]*p.y+m[10]*p.z+m[14])
    }
    fun points(depth: DepthRaster,confidence: DepthRaster,k: DepthIntrinsics,matrix: FloatArray,
        floor: FloorFrame,minimumConfidence: Int=160,maxPoints: Int=768): List<V3> {
        require(depth.width>0 && depth.height>0 && maxPoints>0 && depth.rowStride>=depth.width*depth.pixelStride && confidence.rowStride>=confidence.width*confidence.pixelStride &&
            depth.width==confidence.width && depth.height==confidence.height && depth.pixelStride>=2 && confidence.pixelStride>=1)
        val out=mutableListOf<V3>(); val d=ByteBuffer.wrap(depth.bytes).order(ByteOrder.LITTLE_ENDIAN)
        val stride=max(1,ceil(sqrt(depth.width.toDouble()*depth.height/maxPoints)).toInt())
        for(v in 0 until depth.height step stride) for(u in 0 until depth.width step stride) {
            val di=v*depth.rowStride+u*depth.pixelStride; val ci=v*confidence.rowStride+u*confidence.pixelStride
            if(di+1>=depth.bytes.size || ci>=confidence.bytes.size) continue
            if((confidence.bytes[ci].toInt() and 255)<minimumConfidence) continue
            val mm=d.getShort(di).toInt() and 65535; if(mm !in 200..8000) continue
            val w=world(backProject(u,v,mm/1000.0,depth.width,depth.height,k),matrix)
            if(w.y-floor.origin.y in 0.35..2.3) out.add(w)
        }
        return out.take(maxPoints)
    }
}

/** Sparse, bounded wall evidence. Furniture remains a candidate until an operator confirms it. */
class DepthWallAccumulator {
    private val cells=linkedMapOf<String,Pair<V3,Long>>()
    private var lastTimestamp=0L
    fun clear() { cells.clear(); lastTimestamp=0L }
    fun add(timestamp: Long,frameTimestamp: Long,points: List<V3>): Boolean {
        if(timestamp<=lastTimestamp || timestamp!=frameTimestamp) return false
        val valid=points.filter {it.finite}; if(valid.isEmpty()) return false
        lastTimestamp=timestamp
        valid.forEach { p->cells["${floor(p.x/0.10).toInt()}:${floor(p.z/0.10).toInt()}:${floor(p.y/0.2).toInt()}"]=p to timestamp }
        while(cells.size>4096) cells.remove(cells.keys.first())
        return true
    }
    fun candidates(frame: FloorFrame,segment: String,timestamp: Long,tolerance: Double): List<ScanWall> {
        if(timestamp!=lastTimestamp) return emptyList()
        val fresh=cells.values.filter {it.second==timestamp}.mapTo(hashSetOf()) {it.first}
        var remaining=cells.values.map {it.first}; val result=mutableListOf<ScanWall>(); val random=Random(17)
        repeat(6) {
            if(remaining.size<18) return@repeat
            var best=emptyList<V3>(); var axis: Pair<P2,P2>?=null
            repeat(48) trial@ {
                val a=frame.project(remaining[random.nextInt(remaining.size)]); val b=frame.project(remaining[random.nextInt(remaining.size)])
                val length=a.distance(b); if(length<0.4) return@trial
                val inliers=remaining.filter { p->val q=frame.project(p); abs((b.x-a.x)*(q.y-a.y)-(b.y-a.y)*(q.x-a.x))/length<=tolerance }
                if(inliers.size>best.size) { best=inliers; axis=a to b }
            }
            val pair=axis ?: return@repeat
            if(best.size<18) return@repeat
            remaining=remaining-best.toSet()
            if(best.maxOf { it.y }-best.minOf { it.y }<0.6 || best.count {it in fresh}<6) return@repeat
            val dx=pair.second.x-pair.first.x; val dy=pair.second.y-pair.first.y; val len=hypot(dx,dy)
            val positions=best.map { frame.project(it) }.map { ((it.x-pair.first.x)*dx+(it.y-pair.first.y)*dy)/len }.sorted()
            val lo=positions[(positions.size*0.05).toInt()]; val hi=positions[(positions.size*0.95).toInt().coerceAtMost(positions.lastIndex)]
            if(hi-lo>=0.4) result.add(ScanWall(a=P2(pair.first.x+lo*dx/len,pair.first.y+lo*dy/len),
                b=P2(pair.first.x+hi*dx/len,pair.first.y+hi*dy/len),source="RAW_DEPTH",segmentId=segment,
                observations=1,confidence=min(0.8,best.size.toDouble()/remaining.size),height=best.maxOf { it.y }-best.minOf { it.y },lastTimestamp=timestamp))
        }
        return result
    }
}
