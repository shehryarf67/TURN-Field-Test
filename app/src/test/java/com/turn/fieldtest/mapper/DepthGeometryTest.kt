package com.turn.fieldtest.mapper

import com.turn.fieldtest.mapper.core.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class DepthGeometryTest {
    private val identity=floatArrayOf(1f,0f,0f,0f,0f,1f,0f,0f,0f,0f,1f,0f,0f,0f,0f,1f)
    private val frame=FloorFrame(V3(0.0,-1.0,0.0),0.0,-1.0)
    private fun depth(values: List<Int>,width: Int=values.size,height: Int=1,row: Int=width*2,pixel: Int=2): DepthRaster {
        val data=ByteArray(row*height); val b=ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        values.forEachIndexed {i,v->b.putShort(i/width*row+i%width*pixel,v.toShort())}
        return DepthRaster(width,height,row,pixel,data)
    }
    private fun confidence(values: List<Int>,width: Int=values.size,height: Int=1,row: Int=width,pixel: Int=1): DepthRaster {
        val data=ByteArray(row*height); values.forEachIndexed {i,v->data[i/width*row+i%width*pixel]=v.toByte()}
        return DepthRaster(width,height,row,pixel,data)
    }
    @Test fun pixelBackProjectionUsesMetricDepthAndCorrectCameraHandedness() {
        val k=DepthIntrinsics(100.0,100.0,50.0,40.0,100,80)
        val centred=DepthGeometry.backProject(25,20,2.0,50,40,k)
        assertEquals(0.0,centred.x,1e-12); assertEquals(0.0,centred.y,1e-12); assertEquals(-2.0,centred.z,1e-12)
        assertEquals(V3(1.0,-.4,-2.0),DepthGeometry.backProject(100,60,2.0,100,80,k))
        val moved=identity.copyOf().apply {this[12]=4f; this[13]=2f; this[14]=3f}
        assertEquals(V3(5.0,1.6,1.0),DepthGeometry.world(V3(1.0,-.4,-2.0),moved))
    }
    @Test fun zeroMissingFarAndLowConfidenceSamplesAreDiscarded() {
        val d=depth(listOf(0,1000,2000,65535,100))
        val c=confidence(listOf(255,0,255,255,255))
        val points=DepthGeometry.points(d,c,DepthIntrinsics(1000.0,1000.0,0.0,0.0,5,1),identity,frame)
        assertEquals(1,points.size); assertEquals(-2.0,points.single().z,0.0)
    }
    @Test fun paddedRowsAndIndependentPixelStridesAreRespected() {
        val d=depth(listOf(1000,2000,3000,4000),2,2,12,4)
        val c=confidence(listOf(255,0,0,255),2,2,8,2)
        val points=DepthGeometry.points(d,c,DepthIntrinsics(1000.0,1000.0,0.0,0.0,2,2),identity,frame)
        assertEquals(listOf(-1.0,-4.0),points.map {it.z})
    }
    @Test fun truncatedBuffersDoNotReadPastImageLimits() {
        val d=DepthRaster(2,2,8,2,byteArrayOf(0xE8.toByte(),3))
        val points=DepthGeometry.points(d,confidence(listOf(255,255,255,255),2,2),DepthIntrinsics(100.0,100.0,0.0,0.0,2,2),identity,frame)
        assertEquals(1,points.size)
    }
    @Test fun oldReprojectedDepthIsNotIndependentEvidence() {
        val a=DepthWallAccumulator(); val points=listOf(V3(0.0,1.0,-2.0))
        assertFalse(a.add(1,2,points)); assertTrue(a.add(2,2,points)); assertFalse(a.add(2,3,points)); assertFalse(a.add(1,1,points))
        assertTrue(a.add(3,3,points))
    }
    @Test fun quarterTurnCameraPoseAndPixelsPreserveWorldStructureWithoutMirroring() {
        val k=DepthIntrinsics(100.0,100.0,50.0,50.0,100,100)
        val unrotated=DepthGeometry.world(DepthGeometry.backProject(70,60,2.0,100,100,k),identity)
        val quarterTurn=floatArrayOf(0f,1f,0f,0f,-1f,0f,0f,0f,0f,0f,1f,0f,0f,0f,0f,1f)
        val rotated=DepthGeometry.world(DepthGeometry.backProject(40,70,2.0,100,100,k),quarterTurn)
        assertEquals(unrotated.x,rotated.x,1e-12); assertEquals(unrotated.y,rotated.y,1e-12); assertEquals(unrotated.z,rotated.z,1e-12)
        assertEquals(frame.project(unrotated),frame.project(rotated))
    }
    @Test fun knownPlaneSurvivesProjectionAndRobustFitDespiteOutliers() {
        val width=80; val height=50
        val points=DepthGeometry.points(depth(List(width*height) {2000},width,height),confidence(List(width*height) {255},width,height),
            DepthIntrinsics(70.0,70.0,40.0,25.0,width,height),identity,frame)
        assertTrue(points.size>100); assertTrue(points.all {it.z==-2.0})
        val a=DepthWallAccumulator(); a.add(1,1,points+List(40) {V3(it*.13,1.0,-1.0-it*.1)})
        val candidates=a.candidates(frame,"section",1,.06)
        val wall=candidates.maxByOrNull {it.a.distance(it.b)}
        assertNotNull(wall); assertEquals("CANDIDATE",wall!!.status)
        assertEquals(2.0,wall.a.y,.08); assertEquals(2.0,wall.b.y,.08); assertTrue(wall.height>=.6)
    }
    @Test fun floorAndCeilingPointsAreNotUsedAsStructuralWalls() {
        val lowFloor=FloorFrame(V3(0.0,0.0,0.0),0.0,-1.0)
        val points=DepthGeometry.points(depth(listOf(1000)),confidence(listOf(255)),DepthIntrinsics(100.0,100.0,0.0,0.0,1,1),identity,lowFloor)
        assertTrue(points.isEmpty())
        assertTrue(DepthWallAccumulator().candidates(frame,"s",1,.06).isEmpty())
    }
    @Test fun emptyOrUnrelatedNewDepthCannotRebrandOldWallEvidenceAsFresh() {
        val a=DepthWallAccumulator()
        val wall=(0..25).flatMap {x->(0..7).map {y->V3(x*.12,.4+y*.2,-2.0)}}
        assertTrue(a.add(1,1,wall)); assertTrue(a.candidates(frame,"s",1,.06).isNotEmpty())
        assertFalse(a.add(2,2,emptyList()))
        assertTrue(a.candidates(frame,"s",2,.06).isEmpty())
        assertTrue(a.add(3,3,listOf(V3(20.0,1.0,-20.0))))
        assertTrue(a.candidates(frame,"s",3,.06).isEmpty())
    }
}
