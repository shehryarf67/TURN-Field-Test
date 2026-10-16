package com.turn.fieldtest.mapper.ar

import android.annotation.SuppressLint
import android.app.Activity
import android.opengl.*
import android.os.Build
import android.os.PowerManager
import android.view.MotionEvent
import com.google.ar.core.*
import com.google.ar.core.exceptions.NotYetAvailableException
import com.turn.fieldtest.mapper.core.*
import java.nio.*
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.*

data class ArStatus(val text: String,val tracking: Boolean=false,val depth: String="Depth unavailable",val planes: Int=0,val segment: String?=null)
interface ArScanSink {
    fun status(value: ArStatus)
    fun floorCandidate(description: String)
    fun reference(frame: FloorFrame,segmentId: String,length: Double)
    fun point(point: P2,id: String,source: String,timestamp: Long,segment: String)
    fun observations(walls: List<ScanWall>)
    fun pose(value: PoseSample,frame: FloorFrame)
    fun refined(points: Map<String,P2>,segment: String)
    fun interrupted(segment: String)
}

/** Owns ONLY ARCore's camera, on one GL thread. No CameraX camera is opened by this view. */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class ArScanView(private val activity: Activity, val session: Session, private val sink: ArScanSink,
    val segmentId: String, private val depthMode: Config.DepthMode) : GLSurfaceView(activity), GLSurfaceView.Renderer {
    @Volatile var tool="CORNER"
    @Volatile var overlay=emptyList<ScanWall>()
    @Volatile var mapAlignment=Alignment()
    @Volatile var thresholds=ScanThresholds()
    private var tap: Pair<Float,Float>?=null
    private var origin: Anchor?=null; private var ahead: Anchor?=null
    private var pendingFloor: HitResult?=null
    private var pendingFloorAt=0L
    @Volatile private var disposed=false
    private fun report(value: ArStatus) {if(!disposed) sink.status(value.copy(segment=segmentId))}
    private val anchors=linkedMapOf<String,Anchor>()
    private val work=Executors.newSingleThreadExecutor(); private val busy=AtomicBoolean(false)
    private val depthEvidence=DepthWallAccumulator()
    private val captureGuard=CaptureGuard()
    private var texture=0; private var backgroundProgram=0; private var lineProgram=0
    private var width=1; private var height=1
    private var lastStatus=0L; private var lastPose=0L; private var lastDepth=0L; private var lastRefine=0L
    @Volatile private var depthFrames=0
    private var rawDepthUnavailable=false
    private var trackingFrames=0; private var blocked=false
    private var referencePending=true
    private val quad=buffer(floatArrayOf(-1f,-1f,1f,-1f,-1f,1f,1f,1f)); private val uv=buffer(FloatArray(8))
    init {
        setEGLContextClientVersion(2); setEGLConfigChooser(8,8,8,8,16,0)
        preserveEGLContextOnPause=true; setRenderer(this); renderMode=RENDERMODE_CONTINUOUSLY
    }
    override fun onTouchEvent(event: MotionEvent): Boolean { if(event.action==MotionEvent.ACTION_UP) {val point=event.x to event.y; queueEvent { tap=point }}; return true }
    fun confirmFloor() = queueEvent {
        val hit=pendingFloor
        if(disposed || blocked || trackingFrames<20 || hit==null || System.nanoTime()-pendingFloorAt>10_000_000_000L || hit.trackable.trackingState!=TrackingState.TRACKING) report(ArStatus("Floor reference expired; tap the floor again"))
        else { origin?.detach(); origin=hit.createAnchor(); pendingFloor=null; sink.floorCandidate(""); report(ArStatus("Now tap a floor point ≥0.5 m ahead. This sets map Up; north is not assumed.",true)) }
    }
    fun rejectFloor()=queueEvent { pendingFloor=null; origin?.detach(); origin=null; ahead?.detach(); ahead=null; sink.floorCandidate("") }
    fun disposeNative() {
        disposed=true; work.shutdownNow(); anchors.values.forEach { it.detach() }; anchors.clear(); origin?.detach(); ahead?.detach()
        if(backgroundProgram!=0) GLES20.glDeleteProgram(backgroundProgram)
        if(lineProgram!=0) GLES20.glDeleteProgram(lineProgram)
        if(texture!=0) GLES20.glDeleteTextures(1,intArrayOf(texture),0)
    }
    private fun frame(): FloorFrame? {
        val a=origin ?: return null; val b=ahead ?: return null
        if(a.trackingState!=TrackingState.TRACKING || b.trackingState!=TrackingState.TRACKING) return null
        return runCatching { FloorFrame.along(a.pose.v(),b.pose.v()) }.getOrNull()
    }
    override fun onSurfaceCreated(gl: GL10?,config: EGLConfig?) {
        val ids=IntArray(1); GLES20.glGenTextures(1,ids,0); texture=ids[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,texture)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE)
        backgroundProgram=program("attribute vec2 p; attribute vec2 uv; varying vec2 t; void main(){gl_Position=vec4(p,0.,1.);t=uv;}",
            "#extension GL_OES_EGL_image_external : require\nprecision mediump float; uniform samplerExternalOES camera; varying vec2 t; void main(){gl_FragColor=texture2D(camera,t);}")
        lineProgram=program("attribute vec3 p; uniform mat4 vp; void main(){gl_Position=vp*vec4(p,1.);}",
            "precision mediump float; uniform vec4 color; void main(){gl_FragColor=color;}")
        session.setCameraTextureNames(intArrayOf(texture))
    }
    override fun onSurfaceChanged(gl: GL10?,w: Int,h: Int) { width=w; height=h; GLES20.glViewport(0,0,w,h) }
    override fun onDrawFrame(gl: GL10?) {
        if(disposed) return
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        try {
            @Suppress("DEPRECATION") val rotation=activity.windowManager.defaultDisplay.rotation
            session.setDisplayGeometry(rotation,width,height)
            val f=session.update(); if(f.timestamp==0L) return
            drawCamera(f)
            val tracked=f.camera.trackingState==TrackingState.TRACKING
            val thermal=if(Build.VERSION.SDK_INT>=29) activity.getSystemService(PowerManager::class.java).currentThermalStatus else 0
            val captureState=captureGuard.observe(f.timestamp,tracked,!referencePending,thermal)
            if(captureState==CaptureGuard.State.STALE) return
            if(captureState==CaptureGuard.State.LOST && !blocked) { blocked=true; sink.interrupted(segmentId) }
            if(!tracked) trackingFrames=0 else trackingFrames++
            val local=frame()
            val now=System.nanoTime()
            if(now-lastStatus>500_000_000L) {
                lastStatus=now
                val count=session.getAllTrackables(Plane::class.java).count { it.trackingState==TrackingState.TRACKING && it.subsumedBy==null }
                val label=when {
                    blocked->"Tracking lost in this section · restart camera and align to saved points"
                    !tracked->"Tracking limited: ${f.camera.trackingFailureReason.name.lowercase().replace('_',' ')}"
                    origin==null->"Looking for floor · move slowly, then tap the actual floor"
                    ahead==null->"Tap a floor point ahead to set the map direction"
                    local==null->"Floor anchors limited · wait or pause and align a new section"
                    else->"Tracking · ${if(count==0) "looking for surfaces" else "$count surfaces"}"
                }
                report(ArStatus(label,tracked&&!blocked,when {
                    depthMode==Config.DepthMode.DISABLED->"Depth unavailable · guided plane marking"
                    rawDepthUnavailable->"Raw depth unavailable · guided plane marking"
                    depthFrames==0->"Depth supported · waiting for usable raw frames"
                    else->"Depth available · $depthFrames fresh raw frames"
                },count))
            }
            if(!tracked || blocked) { tap=null; return }
            if(thermal>=4) { tap=null; report(ArStatus("Device hot · pause scanning and let it cool",false)); return }
            tap?.let { (x,y)->tap=null; acceptTap(f,x,y,local) }
            val floor=frame() ?: return
            if(referencePending) { referencePending=false; sink.reference(floor,segmentId,hypot((ahead!!.pose.tx()-origin!!.pose.tx()).toDouble(),(ahead!!.pose.tz()-origin!!.pose.tz()).toDouble())) }
            drawGeometry(f,floor)
            if(now-lastPose>250_000_000L) {
                lastPose=now; sink.pose(PoseSample(segmentId,f.timestamp,f.camera.pose.v(),f.camera.pose.rotationQuaternion.toList(),"TRACKING",floor.project(f.camera.pose.v())),floor)
            }
            if(now-lastRefine>1_000_000_000L) {
                lastRefine=now; sink.refined(anchors.filterValues { it.trackingState==TrackingState.TRACKING }.mapValues { floor.project(it.value.pose.v()) },segmentId)
            }
            val interval=if(thermal>=2) 1_500_000_000L else 500_000_000L
            if(now-lastDepth>=interval && busy.compareAndSet(false,true)) {
                lastDepth=now
                val planes=planeWalls(floor,f.timestamp)
                val payload=if(depthMode!=Config.DepthMode.DISABLED) acquireDepth(f) else null
                val frameTimestamp=f.timestamp
                val settings=thresholds
                work.execute {
                    try {
                        val depthWalls=if(payload!=null) {
                            val points=DepthGeometry.points(payload.depth,payload.confidence,payload.intrinsics,payload.matrix,floor)
                            if(depthEvidence.add(payload.timestamp,frameTimestamp,points)) {
                                if(points.isNotEmpty()) depthFrames++
                                depthEvidence.candidates(floor,segmentId,payload.timestamp,settings.lineInlierMetres).filter {it.a.distance(it.b)>=settings.minimumWallMetres}
                            } else emptyList()
                        } else emptyList()
                        if(!disposed) sink.observations(planes+depthWalls)
                    } catch(_: Exception) { report(ArStatus("Depth processing unavailable · use guided marking")) } finally { busy.set(false) }
                }
            }
        } catch(e: Exception) { report(ArStatus("AR capture unavailable: ${e.javaClass.simpleName}. Pause and retry; saved geometry remains.")) }
    }
    private fun acceptTap(f: Frame,x: Float,y: Float,floor: FloorFrame?) {
        val hit=f.hitTest(x,y).firstOrNull { h ->
            h.distance in 0.2f..8f && h.trackable.trackingState==TrackingState.TRACKING && when(val t=h.trackable) {
                is Plane -> t.isPoseInPolygon(h.hitPose) && t.subsumedBy==null &&
                    (if(origin==null || ahead==null) t.type==Plane.Type.HORIZONTAL_UPWARD_FACING else true)
                is DepthPoint -> floor!=null && depthFrames>0
                else -> false
            }
        }
        if(hit==null || trackingFrames<20) { report(ArStatus("No reliable surface at that tap. Move slowly, improve light, or enter measured coordinates.",true)); return }
        if(origin==null) {
            pendingFloor=hit; pendingFloorAt=System.nanoTime()
            val height=f.camera.pose.ty()-hit.hitPose.ty()
            if(height<0.3f) {pendingFloor=null; report(ArStatus("Aim at the floor below camera height",true)); return}
            sink.floorCandidate("Candidate %.2f m below camera. Confirm this is the floor, not furniture. This point is the origin.".format(height)); return
        }
        if(ahead==null) {
            if(abs(hit.hitPose.ty()-origin!!.pose.ty())>0.15f) { report(ArStatus("Choose another point on the SAME floor",true)); return }
            if(hypot((hit.hitPose.tx()-origin!!.pose.tx()).toDouble(),(hit.hitPose.tz()-origin!!.pose.tz()).toDouble())<0.5) { report(ArStatus("Choose a forward point at least 0.5 m away",true)); return }
            ahead=hit.createAnchor(); return
        }
        if(floor==null) return
        val height=hit.hitPose.ty()-floor.origin.y
        if(height !in -0.15..2.5) { report(ArStatus("Tap a floor/wall boundary or enter a measured point",true)); return }
        if(anchors.size>=96) { report(ArStatus("Section anchor limit reached. Pause and continue with an aligned new section.",true)); return }
        val id=scanId(); anchors[id]=hit.createAnchor()
        sink.point(floor.project(hit.hitPose.v()),id,if(hit.trackable is Plane) "AR_PLANE" else "DEPTH_HIT",f.timestamp,segmentId)
    }
    private fun planeWalls(floor: FloorFrame,time: Long): List<ScanWall> = session.getAllTrackables(Plane::class.java).filter {
        it.type==Plane.Type.VERTICAL && it.trackingState==TrackingState.TRACKING && it.subsumedBy==null
    }.take(40).mapNotNull { p ->
        val polygon=p.polygon.duplicate(); val points=mutableListOf<V3>()
        while(polygon.remaining()>=2) points.add(p.centerPose.transformPoint(floatArrayOf(polygon.get(),0f,polygon.get())).let { V3(it[0].toDouble(),it[1].toDouble(),it[2].toDouble()) })
        if(points.size<3 || points.maxOf { it.y }-points.minOf { it.y }<0.7 || points.minOf { it.y }-floor.origin.y>0.5) null
        else {
            val pair=points.flatMap { a->points.map { b->floor.project(a) to floor.project(b) } }.maxBy { it.first.distance(it.second) }
            if(pair.first.distance(pair.second)<thresholds.minimumWallMetres) null else ScanWall("plane:${System.identityHashCode(p)}",pair.first,pair.second,
                "VERTICAL_PLANE",segmentId,confidence=0.65,height=points.maxOf { it.y }-points.minOf { it.y },lastTimestamp=time)
        }
    }
    private data class Payload(val depth: DepthRaster,val confidence: DepthRaster,val intrinsics: DepthIntrinsics,val matrix: FloatArray,val timestamp: Long)
    private fun acquireDepth(f: Frame): Payload? = try {
        f.acquireRawDepthImage16Bits().use { d -> f.acquireRawDepthConfidenceImage().use { c ->
            if(d.timestamp!=f.timestamp || c.timestamp!=d.timestamp) null else {
                val k=f.camera.textureIntrinsics; val focal=k.focalLength; val centre=k.principalPoint; val dimensions=k.imageDimensions
                val matrix=FloatArray(16); f.camera.pose.toMatrix(matrix,0)
                Payload(raster(d),raster(c),DepthIntrinsics(focal[0].toDouble(),focal[1].toDouble(),centre[0].toDouble(),centre[1].toDouble(),dimensions[0],dimensions[1]),matrix,d.timestamp).also {rawDepthUnavailable=false}
            }
        } }
    } catch(_: NotYetAvailableException) { null }
      catch(_: Exception) { rawDepthUnavailable=true; null } // A raw-depth failure must not stall plane/guided capture.
    private fun raster(image: android.media.Image): DepthRaster {
        val p=image.planes[0]; val b=p.buffer.duplicate(); val bytes=ByteArray(b.remaining()); b.get(bytes)
        return DepthRaster(image.width,image.height,p.rowStride,p.pixelStride,bytes)
    }
    private fun drawCamera(frame: Frame) {
        quad.position(0); uv.position(0); frame.transformCoordinates2d(Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES,quad,Coordinates2d.TEXTURE_NORMALIZED,uv)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST); GLES20.glUseProgram(backgroundProgram)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0); GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,texture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(backgroundProgram,"camera"),0)
        attribute(backgroundProgram,"p",quad,2); attribute(backgroundProgram,"uv",uv,2)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4)
    }
    private fun drawGeometry(f: Frame,floor: FloorFrame) {
        val view=FloatArray(16); val projection=FloatArray(16); val vp=FloatArray(16)
        f.camera.getViewMatrix(view,0); f.camera.getProjectionMatrix(projection,0,0.1f,100f); Matrix.multiplyMM(vp,0,projection,0,view,0)
        GLES20.glUseProgram(lineProgram); GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(lineProgram,"vp"),1,false,vp,0); GLES20.glLineWidth(3f)
        overlay.take(150).forEach { wall ->
            val a=floor.inverse(mapAlignment.inverse(wall.a),0.03); val b=floor.inverse(mapAlignment.inverse(wall.b),0.03)
            attribute(lineProgram,"p",buffer(floatArrayOf(a.x.toFloat(),a.y.toFloat(),a.z.toFloat(),b.x.toFloat(),b.y.toFloat(),b.z.toFloat())),3)
            if(wall.status=="CONFIRMED") GLES20.glUniform4f(GLES20.glGetUniformLocation(lineProgram,"color"),0f,0.85f,1f,1f)
            else GLES20.glUniform4f(GLES20.glGetUniformLocation(lineProgram,"color"),1f,0.65f,0.15f,1f)
            GLES20.glDrawArrays(GLES20.GL_LINES,0,2)
        }
    }
    private fun attribute(program: Int,name: String,data: FloatBuffer,size: Int) { val loc=GLES20.glGetAttribLocation(program,name); data.position(0); GLES20.glEnableVertexAttribArray(loc); GLES20.glVertexAttribPointer(loc,size,GLES20.GL_FLOAT,false,0,data) }
    private fun program(vertex: String,fragment: String): Int {
        fun shader(kind: Int,text: String): Int { val s=GLES20.glCreateShader(kind); GLES20.glShaderSource(s,text); GLES20.glCompileShader(s); val ok=IntArray(1); GLES20.glGetShaderiv(s,GLES20.GL_COMPILE_STATUS,ok,0); check(ok[0]!=0) { "AR shader unavailable" }; return s }
        val v=shader(GLES20.GL_VERTEX_SHADER,vertex); val f=shader(GLES20.GL_FRAGMENT_SHADER,fragment); val p=GLES20.glCreateProgram()
        GLES20.glAttachShader(p,v); GLES20.glAttachShader(p,f); GLES20.glLinkProgram(p); GLES20.glDeleteShader(v); GLES20.glDeleteShader(f); val ok=IntArray(1); GLES20.glGetProgramiv(p,GLES20.GL_LINK_STATUS,ok,0); check(ok[0]!=0) {"AR renderer unavailable"}; return p
    }
    companion object { private fun buffer(values: FloatArray)=ByteBuffer.allocateDirect(values.size*4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(values); position(0) } }
}
private fun Pose.v()=V3(tx().toDouble(),ty().toDouble(),tz().toDouble())
