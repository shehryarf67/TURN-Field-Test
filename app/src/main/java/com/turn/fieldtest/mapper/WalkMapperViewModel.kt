package com.turn.fieldtest.mapper

import android.app.Application
import androidx.compose.runtime.*
import androidx.lifecycle.*
import com.turn.fieldtest.TurnApplication
import com.turn.fieldtest.mapper.core.*
import com.turn.fieldtest.mapper.ar.*
import com.turn.fieldtest.mapper.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.*

class WalkMapperViewModel @JvmOverloads constructor(app: Application,state: SavedStateHandle,
    private val repository: ScanRepository=ScanRepository((app as TurnApplication).database,app)): AndroidViewModel(app),ArScanSink {
    private val writeMutex=Mutex(); private var saveJob: Job?=null
    private val history=java.util.ArrayDeque<ScanDocument>()
    var doc by mutableStateOf<ScanDocument?>(null); private set
    var currentRoomId by mutableStateOf("")
    var selectedWallId by mutableStateOf<String?>(null)
    var selectedPointId by mutableStateOf<String?>(null)
    var status by mutableStateOf("Opening local scan drafts…"); private set
    var arStatus by mutableStateOf(ArStatus("Camera paused")); private set
    var floorDescription by mutableStateOf(""); private set
    var tool by mutableStateOf("CORNER")
    var busy by mutableStateOf(false); private set
    var saveStatus by mutableStateOf("Draft not saved yet"); private set
    var alignmentA by mutableStateOf<String?>(null); var alignmentB by mutableStateOf<String?>(null)
    var activeSegment by mutableStateOf<String?>(null); private set
    var pendingEndpoint by mutableStateOf<P2?>(null); private set
    var availableDrafts by mutableStateOf<List<ScanDraftEntity>>(emptyList()); private set
    val room get()=doc?.rooms?.firstOrNull { it.id==currentRoomId }
    val cameraMayStart get(): Boolean {
        val d=doc ?: return false; if(busy) return false; if(d.points().isEmpty()) return true
        val a=d.points().firstOrNull {it.id==alignmentA}; val b=d.points().firstOrNull {it.id==alignmentB}
        return a!=null && b!=null && a.id!=b.id && a.p.distance(b.p)>=.5
    }
    init {
        viewModelScope.launch {
            try {
                val venue=requireNotNull(state.get<String>("venueId")); val floor=requireNotNull(state.get<String>("floorId"))
                availableDrafts=repository.drafts(floor)
                val saved=availableDrafts.firstOrNull()?.let { repository.load(it.id) }
                doc=saved?.copy(segments=saved.segments.map { s->if(s.source=="ARCORE" && !s.interrupted) s.copy(interrupted=true,reviewedAfterLoss=false) else s }) ?: ScanDocument(venueId=venue,floorId=floor)
                currentRoomId=doc!!.rooms.lastOrNull()?.id.orEmpty()
                status=if(saved==null) "Choose AR scanning or measured drawing" else "Draft restored. New AR capture requires two saved alignment references."
                if(saved!=null) scheduleSave()
            } catch(e: Exception) { status="Could not open scan: ${e.message}" }
        }
    }
    fun message(text: String) { status=text }
    private fun update(undo: Boolean=true,change: (ScanDocument)->ScanDocument) {
        if(busy) return
        val before=doc ?: return
        runCatching {
            val after=change(before).copy(updatedAt=System.currentTimeMillis(),status="DRAFT")
            val positions=after.points().map {it.p}+after.walls.flatMap {listOf(it.a,it.b)}+after.doors.flatMap {listOf(it.a,it.b)}
            require(positions.all {it.finite && abs(it.x)<=1000 && abs(it.y)<=1000}) {"Coordinates must stay within ±1000 m; check units and alignment"}
            if(undo) { history.addLast(before.copy(poses=emptyList())); while(history.size>32) history.removeFirst() }
            doc=after; scheduleSave()
        }.onFailure { status=it.message ?: "Geometry change rejected" }
    }
    private fun scheduleSave() {
        if(saveJob?.isActive==true) return
        saveJob=viewModelScope.launch { delay(700); saveNow() }
    }
    suspend fun saveNow(): Boolean = writeMutex.withLock {
        val value=doc ?: return@withLock false
        try { repository.save(value); saveStatus="Draft saved on this phone"; availableDrafts=repository.drafts(value.floorId); true }
        catch(e: Exception) { if(e is CancellationException) throw e; saveStatus="Draft save failed: ${e.message}. Keep TURN open and retry."; status=saveStatus; false }
    }
    fun save() { viewModelScope.launch { saveNow() } }
    fun name(value: String)=update(false) {it.copy(name=value.take(80))}
    fun chooseTool(value: String) {tool=value; pendingEndpoint=null}
    fun chooseRoom(id: String) {if(doc?.rooms?.any {it.id==id}==true) {currentRoomId=id; pendingEndpoint=null}}
    fun removeMeasurement(id: String)=update {it.withoutMeasurement(id)}
    fun cleanupWalls()=update { d->d.copy(walls=ScanGeometry.cleanupWalls(d.walls,d.doors.map {it.wallId}.toSet(),d.thresholds.snapMetres)) }
    fun undo() {
        if(history.isEmpty() || busy) return
        val previous=history.peekLast() ?: return
        if(activeSegment!=null && previous.segments.associate {it.id to it.alignment}!=doc!!.segments.associate {it.id to it.alignment}) {
            status="Pause the camera before undoing capture-frame or room setup changes"; return
        }
        doc=doc!!.restoreGeometry(history.removeLast()).copy(updatedAt=System.currentTimeMillis(),status="DRAFT"); scheduleSave()
    }
    fun newDraft() { viewModelScope.launch { if(!saveNow()) return@launch; val d=doc ?: return@launch; doc=ScanDocument(venueId=d.venueId,floorId=d.floorId); currentRoomId=""; activeSegment=null; alignmentA=null; alignmentB=null; history.clear(); save() } }
    fun openDraft(id: String) { viewModelScope.launch { try { if(!saveNow()) return@launch; doc=repository.load(id); currentRoomId=doc?.rooms?.lastOrNull()?.id.orEmpty(); activeSegment=null; alignmentA=null; alignmentB=null; history.clear() } catch(e: Exception) { status=e.message.orEmpty() } } }
    fun addRoom(name: String) { require(name.isNotBlank()); val r=ScanRoom(name=name.trim().take(80)); update { it.copy(rooms=it.rooms+r) }; currentRoomId=r.id; pendingEndpoint=null }
    fun renameRoom(name: String) = update { it.copy(rooms=it.rooms.map { r->if(r.id==currentRoomId) r.copy(name=name.trim().take(80)) else r }) }
    fun finishRoom() = update { d -> val r=room ?: error("Start a room first"); require(ScanGeometry.outlineError(r.corners.map { it.p })==null) { ScanGeometry.outlineError(r.corners.map { it.p }).orEmpty() }; d.copy(rooms=d.rooms.map { if(it.id==r.id) it.copy(confirmed=true) else it }) }
    fun measuredPoint(x: Double,y: Double) {
        require(x.isFinite() && y.isFinite() && abs(x)<1000 && abs(y)<1000) { "Enter finite measured metre coordinates below 1000 m" }
        // Numeric input is already in the CURRENT map frame, never in a previously scaled AR frame.
        val segment=doc!!.segments.lastOrNull {it.source=="MANUAL_MEASURED" && it.alignment==Alignment()}?.id ?: "manual:${scanId()}"
        if(doc!!.segments.none { it.id==segment }) update(false) { it.copy(segments=it.segments+ScanSegment(segment,"MANUAL_MEASURED")) }
        receivePoint(P2(x,y),scanId(),"MANUAL_MEASURED",System.currentTimeMillis(),segment)
    }
    private fun receivePoint(p: P2,id: String,source: String,time: Long,segment: String) {
        if(!p.finite) { status="Invalid surface coordinate rejected"; return }
        val r=room ?: run { status="Start and name a room first"; return }
        val s=doc!!.segments.firstOrNull { it.id==segment } ?: return
        if(!s.aligned || s.interrupted) { status="Section needs alignment before capture"; return }
        val point=s.alignment.apply(p)
        when(tool) {
            "CORNER","BOUNDARY","ENTRANCE" -> {
                if(r.confirmed) { status="Outline confirmed; unconfirm it in review before adding corners"; return }
                if(r.corners.any { it.p.distance(point)<0.04 }) { status="Corner duplicates an existing point"; return }
                update { d->d.copy(rooms=d.rooms.map { if(it.id==r.id) it.copy(corners=it.corners+ScanPoint(id,point,source,segment,time)) else it }) }
                selectedPointId=id; status="Corner ${r.corners.size+1} placed · ${"%.2f, %.2f m".format(point.x,point.y)}"
            }
            "WALL","DOOR" -> {
                val a=pendingEndpoint
                if(a==null) { pendingEndpoint=point; status="First endpoint selected · mark the second"; return }
                pendingEndpoint=null
                if(tool=="WALL") {
                    if(a.distance(point)<0.4) { status="Wall must be at least 0.4 m long"; return }
                    val wall=ScanWall(a=a,b=point,source="GUIDED_$source",segmentId=segment,status="CONFIRMED")
                    update { it.copy(walls=it.walls+wall) }; selectedWallId=wall.id
                } else {
                    val w=doc!!.allWalls().firstOrNull { it.id==selectedWallId }
                    if(w==null) { status="Select a confirmed wall in preview before marking a doorway"; return }
                    if(a.distance(ScanGeometry.projection(a,w.a,w.b))>doc!!.thresholds.doorToleranceMetres || point.distance(ScanGeometry.projection(point,w.a,w.b))>doc!!.thresholds.doorToleranceMetres) { status="Door endpoints are too far from the selected wall; edit measured coordinates in review"; return }
                    val door=ScanDoor(wallId=w.id,a=ScanGeometry.projection(a,w.a,w.b),b=ScanGeometry.projection(point,w.a,w.b),roomIds=listOf(r.id))
                    update { it.copy(doors=it.doors+door) }
                }
                status="${tool.lowercase().replaceFirstChar { it.uppercase() }} saved in draft"
            }
        }
    }
    fun movePoint(id: String,p: P2) = update { d -> require(p.finite); d.copy(rooms=d.rooms.map { r->if(r.corners.any {it.id==id}) r.copy(confirmed=false,corners=r.corners.map { if(it.id==id) it.copy(p=p,adjusted=true) else it }) else r }) }
    fun deletePoint(id: String) = update { d->d.requireRemovablePoints(setOf(id)); d.copy(rooms=d.rooms.map { r->if(r.corners.any {it.id==id}) r.copy(confirmed=false,corners=r.corners.filterNot { it.id==id }) else r }) }
    fun unconfirmRoom() = update { d->d.copy(rooms=d.rooms.map { if(it.id==currentRoomId) it.copy(confirmed=false) else it }) }
    fun removeRoom() {
        val id=currentRoomId; val points=room?.corners?.map {it.id}?.toSet().orEmpty()
        update {d->d.requireRemovablePoints(points); d.copy(rooms=d.rooms.filterNot {it.id==id},doors=d.doors.map {it.copy(roomIds=it.roomIds-id)}.filter {it.roomIds.isNotEmpty()},
            measurements=d.measurements.filterNot {it.pointA in points || it.pointB in points})}
        currentRoomId=doc?.rooms?.firstOrNull()?.id.orEmpty(); selectedPointId=null; selectedWallId=null
    }
    fun wallDecision(id: String,accept: Boolean) = update { d->d.copy(walls=d.walls.map { if(it.id==id) it.copy(status=if(accept) "CONFIRMED" else "REJECTED") else it }) }
    fun editWall(id: String,a: P2,b: P2) = update { d-> require(a.finite && b.finite && a.distance(b)>=0.1); d.copy(walls=d.walls.map { if(it.id==id) it.copy(a=a,b=b,source="MANUAL_ADJUSTED",status="CONFIRMED") else it }) }
    fun splitWall(id: String) = update { d -> val w=d.walls.first { it.id==id }; require(d.doors.none { it.wallId==id }) { "Remove/reassign openings before splitting this wall" }; val middle=P2((w.a.x+w.b.x)/2,(w.a.y+w.b.y)/2)
        d.copy(walls=d.walls.filterNot { it.id==id }+listOf(w.copy(id=scanId(),b=middle),w.copy(id=scanId(),a=middle))) }
    fun joinWalls(first: String,second: String) = update { d -> require(d.doors.none { it.wallId==first || it.wallId==second }) { "Reassign doorways before joining walls" }
        val a=d.walls.first { it.id==first }; val b=d.walls.first { it.id==second }; val merged=ScanGeometry.mergeWalls(listOf(a,b),d.thresholds.snapMetres)
        require(merged.size==1) { "Walls must be near-collinear and touching" }; d.copy(walls=d.walls.filterNot { it.id==first || it.id==second }+merged) }
    fun door(id: String,a: P2,b: P2,roomIds: List<String>,walkable: Boolean)=update { d->require(a.finite && b.finite); d.copy(doors=d.doors.map { if(it.id==id) it.copy(a=a,b=b,roomIds=roomIds,walkable=walkable) else it }) }
    fun removeDoor(id: String)=update { it.copy(doors=it.doors.filterNot { d->d.id==id }) }
    fun measurement(a: String,b: String,length: Double,purpose: String) = update { d ->
        require(length.isFinite() && length>0 && a!=b) { "Choose two points and a positive measured length" }
        val points=d.points().associateBy { it.id }; val estimated=points.getValue(a).p.distance(points.getValue(b).p)
        require(estimated>0.01)
        d.copy(measurements=d.measurements+ScanMeasurement(pointA=a,pointB=b,measured=length,estimatedAtCapture=estimated,purpose=purpose))
    }
    fun applyCalibration(id: String) = update { d ->
        val m=d.measurements.first { it.id==id && it.purpose=="CALIBRATION" }; require(!m.calibrationApplied) { "This calibration was already applied" }
        val points=d.points().associateBy { it.id }; val current=points.getValue(m.pointA).p.distance(points.getValue(m.pointB).p)
        val scale=m.measured/current; require(scale in 0.5..2.0) { "Scale correction over 2×: check units or rescan" }
        d.transformed(Alignment(scale=scale)).copy(measurements=d.measurements.map { if(it.id==id) it.copy(scaleApplied=scale) else it })
    }
    fun rotate(degrees: Double)=update { require(degrees.isFinite()); it.transformed(Alignment(radians=Math.toRadians(degrees))) }
    fun transformSection(segment: String,t: Alignment)=update { d ->
        val sourceRoomIds=d.rooms.filter { it.corners.any { p->p.segmentId==segment } }.map { it.id }
        val old=d.segments.first { it.id==segment }
        d.copy(rooms=d.rooms.map { r->r.copy(corners=r.corners.map { if(it.segmentId==segment) it.copy(p=t.apply(it.p),adjusted=true) else it }) },
            walls=d.walls.map { if(it.segmentId==segment) it.copy(a=t.apply(it.a),b=t.apply(it.b)) else it },
            doors=d.doors.map { door->if(sourceRoomIds.any { door.wallId.startsWith("$it:") } || d.walls.any { it.id==door.wallId && it.segmentId==segment }) door.copy(a=t.apply(door.a),b=t.apply(door.b)) else door },
            poses=d.poses.map {if(it.segmentId==segment) it.copy(mapPoint=it.mapPoint?.let(t::apply)) else it},
            segments=d.segments.map { if(it.id==segment) it.copy(alignment=t.after(old.alignment),aligned=true,reviewedAfterLoss=true) else it })
    }
    fun reviewInterruptedSections()=update { it.copy(segments=it.segments.map { s->s.copy(reviewedAfterLoss=true) }) }
    fun thresholds(snap: Double,tolerance: Double,inlier: Double,minimumWall: Double)=update { require(snap in 0.001..0.3 && tolerance in 0.01..0.2 && inlier in .01..0.2 && minimumWall in .4..2.0); it.copy(thresholds=it.thresholds.copy(snapMetres=snap,validationRelativeTolerance=tolerance,lineInlierMetres=inlier,minimumWallMetres=minimumWall)) }
    fun publish(done: (String)->Unit) {
        val value=doc ?: return; if(busy) return
        busy=true
        viewModelScope.launch { try { writeMutex.withLock { saveJob?.cancel(); repository.save(value); val floor=repository.publish(value); doc=repository.load(value.id); status="Scan saved as a new TURN floor"; done(floor) } }
            catch(e: Exception) { status="Map not published: ${e.message}" } finally { busy=false } }
    }
    fun cameraStarted(id: String) { activeSegment=id; pendingEndpoint=null; floorDescription=""; arStatus=ArStatus("Initializing ARCore") }
    fun cameraStopped() { activeSegment?.let(::interrupted); activeSegment=null; floorDescription=""; save() }
    private fun main(block: ()->Unit) { viewModelScope.launch(Dispatchers.Main.immediate) { if(!busy) block() } }
    override fun status(value: ArStatus)=main { if(value.segment==null || value.segment==activeSegment) arStatus=value }
    override fun floorCandidate(description: String)=main { if(activeSegment!=null) floorDescription=description }
    override fun reference(frame: FloorFrame,segmentId: String,length: Double) = main {
        if(segmentId!=activeSegment) return@main
        val d=doc ?: return@main
        val a=d.points().firstOrNull { it.id==alignmentA }; val b=d.points().firstOrNull { it.id==alignmentB }
        val alignment=if(d.points().isEmpty()) Alignment() else if(a!=null && b!=null)
            runCatching { Alignment.between(P2(0.0,0.0),P2(0.0,length),a.p,b.p) }.getOrElse { status=it.message.orEmpty(); return@main }
            else { status="Two saved alignment references are required"; return@main }
        if(alignment.scale !in 0.8..1.25) { status="Reference scale disagrees substantially: remeasure/restart capture"; return@main }
        update(false) { it.copy(segments=it.segments+ScanSegment(segmentId,"ARCORE",frame,alignment,referencePointIds=listOfNotNull(a?.id,b?.id))) }
        floorDescription=""; status="Floor and orientation confirmed · mark room corners in order"
    }
    override fun point(point: P2,id: String,source: String,timestamp: Long,segment: String)=main { receivePoint(point,id,source,timestamp,segment) }
    override fun pose(value: PoseSample,frame: FloorFrame)=main {
        if(value.segmentId!=activeSegment) return@main
        val s=doc?.segments?.firstOrNull { it.id==value.segmentId } ?: return@main
        if(!s.aligned || s.interrupted) return@main
        if(!value.position.finite || value.mapPoint?.finite!=true || value.quaternion.size!=4 || value.quaternion.any {!it.isFinite()} ||
            value.timestamp<=0 || value.timestamp<=(doc!!.poses.lastOrNull {it.segmentId==s.id}?.timestamp ?: 0)) return@main
        update(false) { it.copy(poses=(it.poses+value.copy(mapPoint=value.mapPoint?.let(s.alignment::apply))).takeLast(4000),
            segments=it.segments.map { seg->if(seg.id==s.id) seg.copy(frame=frame) else seg }) }
    }
    override fun observations(walls: List<ScanWall>)=main {
        if(walls.isEmpty()) return@main
        val s=doc?.segments?.firstOrNull { it.id==walls.first().segmentId && !it.interrupted } ?: return@main
        update(false) { d ->
            val combined=d.walls.toMutableList()
            walls.forEach { raw ->
                val w=raw.copy(a=s.alignment.apply(raw.a),b=s.alignment.apply(raw.b))
                val i=combined.indexOfFirst { it.segmentId==s.id && (it.id==w.id || ScanGeometry.collinear(it,w,0.12) &&
                    minOf(it.a.distance(w.a),it.b.distance(w.b),it.a.distance(w.b),it.b.distance(w.a))<0.5) }
                if(i<0 && combined.size<300) combined.add(w)
                else if(i>=0 && combined[i].status=="CANDIDATE" && combined[i].lastTimestamp!=w.lastTimestamp)
                    combined[i]=w.copy(id=combined[i].id,observations=combined[i].observations+1)
            }
            d.copy(walls=combined)
        }
    }
    override fun refined(points: Map<String,P2>,segment: String)=main {
        val s=doc?.segments?.firstOrNull { it.id==segment && !it.interrupted } ?: return@main
        update(false) { d->d.copy(rooms=d.rooms.map { r->r.copy(corners=r.corners.map { p->if(!p.adjusted && p.segmentId==segment && points.containsKey(p.id)) p.copy(p=s.alignment.apply(points.getValue(p.id))) else p }) }) }
    }
    override fun interrupted(segment: String)=main {
        val id=activeSegment?.takeIf {it==segment} ?: return@main
        update(false) { it.copy(segments=it.segments.map { s->if(s.id==id) s.copy(interrupted=true,reviewedAfterLoss=false) else s }) }
        status="Capture interrupted. Saved geometry remains; align any new AR section to saved points."
    }
}
