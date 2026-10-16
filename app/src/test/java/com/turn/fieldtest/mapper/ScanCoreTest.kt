package com.turn.fieldtest.mapper

import com.turn.fieldtest.mapper.core.*
import com.turn.fieldtest.core.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.math.*

class ScanCoreTest {
    private fun close(a: P2,b: P2) {assertEquals(a.x,b.x,1e-8); assertEquals(a.y,b.y,1e-8)}
    private fun wall(a: P2,b: P2,id: String="w")=ScanWall(id,a,b,"TEST","s",status="CONFIRMED")
    private fun room(id: String,x: Double=0.0)=ScanRoom(id,id,listOf(P2(x,0.0),P2(x+4,0.0),P2(x+4,4.0),P2(x,4.0)).mapIndexed {i,p->ScanPoint("$id:$i",p,"MEASURED","s")},true)
    private fun doc(two: Boolean=false): ScanDocument {
        val a=room("a"); val b=room("b",4.0)
        return ScanDocument(venueId="v",floorId="f",rooms=if(two) listOf(a,b) else listOf(a),segments=listOf(ScanSegment("s","TEST")),
            doors=if(two) listOf(ScanDoor("door","a:1",P2(4.0,1.5),P2(4.0,2.5),listOf("a","b"))) else emptyList(),
            measurements=listOf(ScanMeasurement(pointA="a:0",pointB="a:1",measured=4.0,estimatedAtCapture=4.0,purpose="VALIDATION")))
    }
    @Test fun arForwardNegativeZMapsUpAndPositiveXMapsRightWithoutMirroring() {
        val f=FloorFrame.along(V3(2.0,1.0,3.0),V3(2.0,1.0,1.0))
        close(P2(1.0,2.0),f.project(V3(3.0,1.0,1.0)))
        assertEquals(V3(3.0,1.0,1.0),f.inverse(P2(1.0,2.0)))
        close(P2(0.0,0.0),f.project(V3(2.0,10.0,3.0)))
    }
    @Test fun arbitraryFloorFrameRoundTrips() {
        val f=FloorFrame.along(V3(5.0,-2.0,7.0),V3(7.0,-2.0,10.0)); val p=P2(-4.3,9.7)
        close(p,f.project(f.inverse(p,1.3)))
    }
    @Test fun alignmentFitsTwoRoomReferencesAndInverts() {
        val t=Alignment.between(P2(0.0,0.0),P2(0.0,2.0),P2(10.0,5.0),P2(13.0,5.0))
        close(P2(10.0,5.0),t.apply(P2(0.0,0.0))); close(P2(13.0,5.0),t.apply(P2(0.0,2.0)))
        close(P2(-3.0,4.0),t.inverse(t.apply(P2(-3.0,4.0))))
    }
    @Test fun transformCompositionPreservesCaptureFrameEvenForSmallScale() {
        val a=Alignment(.8,.7,5.0,-3.0); val b=Alignment(.5,-.2,-8.0,4.0); val p=P2(2.0,6.0)
        close(b.apply(a.apply(p)),b.after(a).apply(p))
        val d=doc().copy(segments=listOf(ScanSegment("s","TEST",alignment=a))).transformed(b)
        close(b.apply(a.apply(p)),d.segments.single().alignment.apply(p))
    }
    @Test fun undoKeepsCameraHistoryInTheRestoredMapFrame() {
        val p=PoseSample("s",100,V3(2.0,1.0,-1.0),listOf(0f,0f,0f,1f),"TRACKING",P2(2.0,1.0))
        val before=doc().copy(poses=listOf(p)); val moved=before.transformed(Alignment(.5,.6,10.0,4.0))
        val restored=moved.restoreGeometry(before.copy(poses=emptyList()))
        close(p.mapPoint!!,restored.poses.single().mapPoint!!)
        assertEquals(p.position,restored.poses.single().position); assertEquals(before.rooms,restored.rooms)
        assertEquals(100L,restored.poses.single().timestamp)
        val interrupted=moved.copy(segments=moved.segments.map {it.copy(interrupted=true,reviewedAfterLoss=false)}).restoreGeometry(before)
        assertTrue(interrupted.segments.single().interrupted)
        assertTrue(ScanGeometry.issues(interrupted).any {it.message.contains("Tracking was lost")})
    }
    @Test fun degenerateFramesAndReflectionsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {FloorFrame.along(V3(0.0,0.0,0.0),V3(.1,0.0,0.0))}
        assertThrows(IllegalArgumentException::class.java) {Alignment(scale=-1.0)}
        assertThrows(IllegalArgumentException::class.java) {FloorFrame(V3(Double.NaN,0.0,0.0),0.0,-1.0)}
    }
    @Test fun collinearWallsMergeButPreserveRealGaps() {
        val a=wall(P2(0.0,0.0),P2(2.0,0.0)); val b=wall(P2(2.0,0.0),P2(4.0,0.0),"b")
        assertEquals(1,ScanGeometry.mergeWalls(listOf(a,b),.01).size)
        assertEquals(2,ScanGeometry.mergeWalls(listOf(a,b.copy(a=P2(2.8,0.0))),.01).size)
        assertFalse(ScanGeometry.collinear(a,b.copy(b=P2(2.0,3.0)),.1))
    }
    @Test fun explicitCleanupSnapsEndpointsAndSplitsCrossingWalls() {
        val a=wall(P2(0.0,0.0),P2(2.0,0.0)); val b=wall(P2(2.05,.03),P2(2.0,2.0),"b")
        val snapped=ScanGeometry.cleanupWalls(listOf(a,b),emptySet(),.1)
        close(P2(2.0,0.0),snapped.first {it.id=="b"}.a)
        val crossing=ScanGeometry.cleanupWalls(listOf(a,wall(P2(1.0,-1.0),P2(1.0,1.0),"b")),emptySet(),.1)
        assertEquals(4,crossing.size)
    }
    @Test fun cleanupKeepsOpeningWallIdentityAndEndpoints() {
        val a=wall(P2(0.0,0.0),P2(2.0,0.0)); val b=wall(P2(1.0,-1.0),P2(1.0,1.0),"b")
        assertEquals(a,ScanGeometry.cleanupWalls(listOf(a,b),setOf("w"),.1).first {it.id=="w"})
    }
    @Test fun sharedBoundaryDoorOpensBothWallsAndAllowsRealAStarRoute() {
        val d=doc(true); assertTrue(ScanGeometry.issues(d).isEmpty())
        val generated=ScanGeometry.generate(d,"new")
        val a=generated.nodes.first {it.poiId=="a"}; val b=generated.nodes.first {it.poiId=="b"}
        val graph=NavigationGraph(generated.floor,generated.nodes,generated.edges)
        assertTrue(graph.validationErrors().isEmpty())
        val route=graph.route(a.point,b.id); assertNotNull(route)
        assertTrue(route!!.points.zipWithNext().all {(x,y)->!generated.floor.crossesWall(x,y) && generated.floor.isPathInsideWalkableSpace(x,y)})
        assertFalse(generated.floor.crossesWall(MetricPoint(3.25,2.25),MetricPoint(5.25,2.25)))
        assertTrue(generated.floor.crossesWall(MetricPoint(3.25,.75),MetricPoint(5.25,.75)))
    }
    @Test fun blockedDoorCannotManufactureConnectivityOrRoute() {
        val d=doc(true).let {it.copy(doors=it.doors.map {door->door.copy(walkable=false)})}
        assertTrue(ScanGeometry.issues(d).any {it.message.contains("disconnected")})
        assertThrows(IllegalArgumentException::class.java) {ScanGeometry.generate(d,"new")}
    }
    @Test fun invalidDoorWallAndRoomAlignmentAreActionable() {
        val d=doc(true); val bad=d.doors.single().copy(a=P2(1.0,1.0),b=P2(2.0,1.0))
        val issues=ScanGeometry.issues(d.copy(doors=listOf(bad))).map {it.message}
        assertTrue(issues.any {it.contains("endpoints")}); assertTrue(issues.any {it.contains("boundary")})
        assertTrue(ScanGeometry.issues(d.copy(doors=listOf(bad.copy(wallId="missing")))).any {it.message.contains("existing wall")})
        val shortened=d.copy(rooms=d.rooms.map {r->if(r.id=="b") r.copy(corners=r.corners.map {p->p.copy(p=p.p.copy(y=1.75+p.p.y*.125))}) else r})
        assertTrue(ScanGeometry.issues(shortened).any {it.message.contains("boundary of b")})
    }
    @Test fun nonRectangularRoomRemainsNonRectangularAndInvalidPolygonsStayDrafts() {
        val l=listOf(P2(0.0,0.0),P2(4.0,0.0),P2(4.0,2.0),P2(2.0,2.0),P2(2.0,4.0),P2(0.0,4.0))
        assertNull(ScanGeometry.outlineError(l))
        assertNotNull(ScanGeometry.outlineError(listOf(P2(0.0,0.0),P2(2.0,2.0),P2(0.0,2.0),P2(2.0,0.0))))
        assertNotNull(ScanGeometry.outlineError(l.take(2)))
        assertNotNull(ScanGeometry.outlineError(l+P2(Double.NaN,0.0)))
    }
    @Test fun overlappingAndIdenticalRoomsAreRejected() {
        val d=doc().copy(rooms=listOf(room("a"),room("b",2.0)))
        assertTrue(ScanGeometry.issues(d).any {it.message.contains("overlap")})
        assertTrue(ScanGeometry.issues(d.copy(rooms=listOf(room("a"),room("b")))).any {it.message.contains("overlap")})
    }
    @Test fun inferredWallsNeverBecomePublishedWallsUntilConfirmed() {
        val candidate=wall(P2(1.0,1.0),P2(3.0,1.0)).copy(status="CANDIDATE")
        val d=doc().copy(walls=listOf(candidate))
        assertEquals(4,ScanGeometry.collisionWalls(d).size)
        assertTrue(ScanGeometry.issues(d).any {!it.blocking})
        assertEquals(5,ScanGeometry.collisionWalls(d.copy(walls=listOf(candidate.copy(status="CONFIRMED")))).size)
    }
    @Test fun independentValidationCannotReuseCalibrationPair() {
        val d=doc(); val m=d.measurements.single().copy(purpose="CALIBRATION")
        assertTrue(ScanGeometry.issues(d.copy(measurements=d.measurements+m)).any {it.message.contains("calibration pair")})
    }
    @Test fun independentValidationFailsBadScaleAndReportsMissingEndpoints() {
        val d=doc(); val m=d.measurements.single()
        assertTrue(ScanGeometry.issues(d.copy(measurements=listOf(m.copy(measured=5.0)))).any {it.message.contains("disagrees")})
        assertTrue(ScanGeometry.issues(d.copy(measurements=listOf(m.copy(pointA="missing")))).any {it.message.contains("missing endpoints")})
    }
    @Test fun conflictingCalibrationWarnsOfNonuniformDrift() {
        val d=doc(); val calibration=d.measurements.single().copy(purpose="CALIBRATION")
        val other=calibration.copy(id="other",pointA="a:2",pointB="a:3",measured=5.0)
        assertTrue(ScanGeometry.issues(d.copy(measurements=listOf(calibration,other))).any {it.message.contains("inconsistent")})
    }
    @Test fun appliedCalibrationCannotBeErasedAndRelabelledAsIndependentValidation() {
        val d=doc(); val applied=d.measurements.single().copy(purpose="CALIBRATION",scaleApplied=1.0)
        val calibrated=d.copy(measurements=listOf(applied))
        assertTrue(applied.calibrationApplied)
        assertThrows(IllegalArgumentException::class.java) {calibrated.withoutMeasurement(applied.id)}
        assertThrows(IllegalArgumentException::class.java) {calibrated.requireRemovablePoints(setOf(applied.pointA))}
        assertTrue(calibrated.withoutMeasurement("unknown").measurements.contains(applied))
    }
    @Test fun lostOrUnalignedSectionsCannotPublishUntilReviewed() {
        val d=doc(); val s=d.segments.single()
        assertTrue(ScanGeometry.issues(d.copy(segments=listOf(s.copy(interrupted=true)))).any {it.message.contains("Tracking was lost")})
        assertTrue(ScanGeometry.issues(d.copy(segments=listOf(s.copy(aligned=false)))).any {it.message.contains("unresolved alignment")})
        assertTrue(ScanGeometry.issues(d.copy(segments=listOf(s.copy(interrupted=true,reviewedAfterLoss=true)))).isEmpty())
    }
    @Test fun captureRejectsDuplicateFramesAndLatchesTrackingLoss() {
        val g=CaptureGuard()
        assertEquals(CaptureGuard.State.LIMITED,g.observe(1,false,false,0))
        assertEquals(CaptureGuard.State.TRACKING,g.observe(2,true,false,0))
        assertEquals(CaptureGuard.State.STALE,g.observe(2,true,true,0))
        assertEquals(CaptureGuard.State.LOST,g.observe(3,false,true,0))
        assertEquals(CaptureGuard.State.LOST,g.observe(4,true,true,0))
        assertEquals(CaptureGuard.State.HOT,CaptureGuard().observe(1,true,false,4))
    }
    @Test fun optionalArFallbackPermissionAndInstallAreExplicit() {
        assertEquals(CaptureGuard.Companion.Entry.MEASURED_ONLY,CaptureGuard.entry(false,false,false))
        assertEquals(CaptureGuard.Companion.Entry.REQUEST_PERMISSION,CaptureGuard.entry(true,false,false))
        assertEquals(CaptureGuard.Companion.Entry.INSTALL_SERVICES,CaptureGuard.entry(true,true,false))
        assertEquals(CaptureGuard.Companion.Entry.START,CaptureGuard.entry(true,true,true))
    }
}
