package com.turn.fieldtest.mapper.core

import com.turn.fieldtest.core.*
import kotlinx.serialization.Serializable
import kotlin.math.*
import java.util.UUID

fun scanId() = UUID.randomUUID().toString()
@Serializable data class P2(val x: Double, val y: Double) {
    fun metric() = MetricPoint(x, y)
    fun distance(b: P2) = hypot(x - b.x, y - b.y)
    val finite get() = x.isFinite() && y.isFinite()
}
@Serializable data class V3(val x: Double, val y: Double, val z: Double) { val finite get()=x.isFinite() && y.isFinite() && z.isFinite() }
/** +x right, +y operator's declared forward. AR's +Y is vertical; forward camera Z is negative. */
@Serializable data class FloorFrame(val origin: V3, val forwardX: Double, val forwardZ: Double) {
    init { require(origin.finite && abs(hypot(forwardX, forwardZ) - 1.0) < 1e-5) }
    fun project(p: V3) = P2(-(p.x-origin.x)*forwardZ + (p.z-origin.z)*forwardX,
        (p.x-origin.x)*forwardX + (p.z-origin.z)*forwardZ)
    fun inverse(p: P2, height: Double = 0.0) = V3(origin.x - p.x*forwardZ + p.y*forwardX,
        origin.y + height, origin.z + p.x*forwardX + p.y*forwardZ)
    companion object {
        fun along(origin: V3, ahead: V3): FloorFrame {
            val length = hypot(ahead.x-origin.x, ahead.z-origin.z)
            require(length >= 0.5) { "Choose a forward point at least 0.5 m from the origin" }
            return FloorFrame(origin, (ahead.x-origin.x)/length, (ahead.z-origin.z)/length)
        }
    }
}
@Serializable data class Alignment(val scale: Double = 1.0, val radians: Double = 0.0, val tx: Double = 0.0, val ty: Double = 0.0) {
    init { require(scale.isFinite() && scale > 0 && radians.isFinite() && tx.isFinite() && ty.isFinite()) }
    fun apply(p: P2) = P2(scale*(cos(radians)*p.x-sin(radians)*p.y)+tx, scale*(sin(radians)*p.x+cos(radians)*p.y)+ty)
    fun inverse(p: P2): P2 { val x=(p.x-tx)/scale; val y=(p.y-ty)/scale; return P2(cos(radians)*x+sin(radians)*y, -sin(radians)*x+cos(radians)*y) }
    fun after(previous: Alignment): Alignment {val offset=apply(P2(previous.tx,previous.ty)); return Alignment(scale*previous.scale,radians+previous.radians,offset.x,offset.y)}
    companion object {
        fun between(a: P2,b: P2,targetA: P2,targetB: P2): Alignment {
            require(a.distance(b) >= 0.5 && targetA.distance(targetB) >= 0.5) { "Alignment references must be at least 0.5 m apart" }
            val angle=atan2(targetB.y-targetA.y,targetB.x-targetA.x)-atan2(b.y-a.y,b.x-a.x)
            val s=targetA.distance(targetB)/a.distance(b)
            val base=Alignment(s,angle); val moved=base.apply(a)
            return base.copy(tx=targetA.x-moved.x,ty=targetA.y-moved.y)
        }
    }
}
@Serializable data class ScanPoint(val id: String = scanId(), val p: P2, val source: String,
    val segmentId: String, val timestamp: Long = 0, val adjusted: Boolean = false)
@Serializable data class ScanRoom(val id: String = scanId(), val name: String, val corners: List<ScanPoint> = emptyList(), val confirmed: Boolean = false)
@Serializable data class ScanWall(val id: String = scanId(), val a: P2, val b: P2, val source: String,
    val segmentId: String, val observations: Int = 1, val confidence: Double = 0.5, val height: Double = 0.0,
    val lastTimestamp: Long = 0, val status: String = "CANDIDATE")
@Serializable data class ScanDoor(val id: String = scanId(), val wallId: String, val a: P2, val b: P2,
    val roomIds: List<String>, val walkable: Boolean = true)
@Serializable data class ScanSegment(val id: String, val source: String, val frame: FloorFrame? = null,
    val alignment: Alignment = Alignment(), val aligned: Boolean = true, val interrupted: Boolean = false,
    val referencePointIds: List<String> = emptyList(), val reviewedAfterLoss: Boolean = false)
@Serializable data class PoseSample(val segmentId: String, val timestamp: Long, val position: V3,
    val quaternion: List<Float>, val tracking: String, val mapPoint: P2?)
@Serializable data class ScanMeasurement(val id: String = scanId(), val pointA: String, val pointB: String,
    val measured: Double, val estimatedAtCapture: Double, val purpose: String, val scaleApplied: Double = 0.0) {
    val calibrationApplied get()=purpose=="CALIBRATION" && scaleApplied>0
}
@Serializable data class ScanThresholds(val snapMetres: Double = 0.10, val doorToleranceMetres: Double = 0.12,
    val lineInlierMetres: Double = 0.06, val minimumWallMetres: Double = 0.4,
    val validationRelativeTolerance: Double = 0.05, val validationAbsoluteTolerance: Double = 0.15)
@Serializable data class ScanDocument(val version: Int = 1, val id: String = scanId(), val venueId: String,
    val floorId: String, val name: String = "Mapped floor", val rooms: List<ScanRoom> = emptyList(),
    val walls: List<ScanWall> = emptyList(), val doors: List<ScanDoor> = emptyList(),
    val segments: List<ScanSegment> = emptyList(), val measurements: List<ScanMeasurement> = emptyList(),
    val poses: List<PoseSample> = emptyList(), val thresholds: ScanThresholds = ScanThresholds(),
    val publishedFloorId: String? = null, val publicationTransform: Alignment? = null,
    val status: String = "DRAFT", val updatedAt: Long = 0,
    val coordinateSystem: String = "METRES_X_RIGHT_Y_UP_OPERATOR_FORWARD",
    val publicationIssues: List<String> = emptyList(), val publishedVenueId: String? = null) {
    fun points() = rooms.flatMap { it.corners }
    fun withoutMeasurement(id: String): ScanDocument {
        require(measurements.none {it.id==id && it.calibrationApplied}) {"Applied calibration is retained as lineage. Undo its scale correction before removing it."}
        return copy(measurements=measurements.filterNot {it.id==id})
    }
    fun requireRemovablePoints(ids: Set<String>) {
        require(measurements.none {it.calibrationApplied && (it.pointA in ids || it.pointB in ids)}) {"These points belong to an applied calibration. Undo the calibration before deleting them."}
    }
    /** Keep captured history when undoing edits, expressed in the restored geometry's frame. */
    fun restoreGeometry(snapshot: ScanDocument): ScanDocument {
        require(snapshot.id==id && snapshot.floorId==floorId && snapshot.venueId==venueId)
        val current=segments.associateBy {it.id}; val restored=snapshot.segments.associateBy {it.id}
        val historySafe=snapshot.segments.map {s->current[s.id]?.let {live->s.copy(frame=live.frame ?: s.frame,
            interrupted=s.interrupted || live.interrupted,reviewedAfterLoss=s.reviewedAfterLoss && live.reviewedAfterLoss)} ?: s}
        return snapshot.copy(segments=historySafe,poses=poses.mapNotNull {p->
            val from=current[p.segmentId] ?: return@mapNotNull null
            val to=restored[p.segmentId] ?: return@mapNotNull null
            p.copy(mapPoint=p.mapPoint?.let {to.alignment.apply(from.alignment.inverse(it))})
        })
    }
    fun allWalls(): List<ScanWall> = rooms.filter { it.confirmed }.flatMap { r ->
        r.corners.indices.map { i -> ScanWall("${r.id}:$i",r.corners[i].p,r.corners[(i+1)%r.corners.size].p,
            "CONFIRMED_OUTLINE",r.corners[i].segmentId,status="CONFIRMED") }
    } + walls.filter { it.status == "CONFIRMED" }
    fun transformed(t: Alignment) = copy(rooms=rooms.map { r -> r.copy(corners=r.corners.map { it.copy(p=t.apply(it.p)) }) },
        walls=walls.map { it.copy(a=t.apply(it.a),b=t.apply(it.b)) }, doors=doors.map { it.copy(a=t.apply(it.a),b=t.apply(it.b)) },
        poses=poses.map { it.copy(mapPoint=it.mapPoint?.let(t::apply)) },
        segments=segments.map { s -> s.copy(alignment=t.after(s.alignment)) })
}
