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
    var status by mutableStateOf("Opening your saved map…"); private set
    var arStatus by mutableStateOf(ArStatus("Camera paused")); private set
    /** Short-lived feedback about the last camera tap (for example why it was refused); cleared after a few seconds. */
    var arNotice by mutableStateOf<String?>(null); private set
    private var noticeJob: Job?=null
    var floorDescription by mutableStateOf(""); private set
    var tool by mutableStateOf("CORNER")
    var busy by mutableStateOf(false); private set
    var saveStatus by mutableStateOf("Not saved yet"); private set
    var alignmentA by mutableStateOf<String?>(null); var alignmentB by mutableStateOf<String?>(null)
    var activeSegment by mutableStateOf<String?>(null); private set
    var pendingEndpoint by mutableStateOf<ScanPoint?>(null); private set
    var availableDrafts by mutableStateOf<List<ScanDraftEntity>>(emptyList()); private set
    var draftRevision by mutableIntStateOf(0); private set
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
                doc=saved?.afterReopen() ?: ScanDocument(venueId=venue,floorId=floor)
                currentRoomId=doc!!.rooms.lastOrNull()?.id.orEmpty()
                status=if(saved==null) "Ready to start" else "Welcome back — your saved map is loaded"
                if(saved!=null) scheduleSave()
            } catch(e: Exception) { status="Couldn't open your saved map: ${e.message}" }
        }
    }
    fun message(text: String) { status=text }
    private fun update(undo: Boolean=true,change: (ScanDocument)->ScanDocument) {
        if(busy) return
        val before=doc ?: return
        runCatching {
            val after=change(before).copy(updatedAt=System.currentTimeMillis(),status="DRAFT")
            val positions=after.points().map {it.p}+after.walls.flatMap {listOf(it.a,it.b)}+after.doors.flatMap {listOf(it.a,it.b)}
            require(positions.all {it.finite && abs(it.x)<=1000 && abs(it.y)<=1000}) {"That would put points more than 1 km away — check the numbers you entered"}
            if(undo) { history.addLast(before.copy(poses=emptyList())); while(history.size>32) history.removeFirst() }
            doc=after; scheduleSave()
        }.onFailure { status=it.message?.takeUnless { m->m=="Failed requirement." } ?: "That change couldn't be made — check the values and try again" }
    }
    private fun scheduleSave() {
        if(saveJob?.isActive==true) return
        saveJob=viewModelScope.launch { delay(700); saveNow() }
    }
    suspend fun saveNow(): Boolean = writeMutex.withLock {
        val value=doc ?: return@withLock false
        if(value.updatedAt==0L && availableDrafts.none { it.id==value.id }) return@withLock true
        try { repository.save(value); saveStatus="Saved on this phone"; availableDrafts=repository.drafts(value.floorId); true }
        catch(e: Exception) { if(e is CancellationException) throw e; saveStatus="Couldn't save: ${e.message}. Keep TURN open and try again."; status=saveStatus; false }
    }
    fun save() { viewModelScope.launch { saveNow() } }
    fun name(value: String)=update(false) {it.copy(name=value.take(80))}
    fun chooseTool(value: String) {
        if(tool==value) return
        tool=value
        pendingEndpoint=null
        clearHiddenCorner()
    }
    /** "Hidden corner" tool: points tapped along the first wall, then (after [nextHiddenWall]) along the second. */
    var hiddenDraft by mutableStateOf(HiddenCornerDraft()); private set
    val hiddenWallA: List<P2> get()=hiddenDraft.wallA
    val hiddenWallB: List<P2>? get()=hiddenDraft.wallB
    private var hiddenSegment: String?=null
    var hiddenCornerFeedback by mutableStateOf<String?>(null); private set
    fun nextHiddenWall() {
        if(hiddenWallB!=null) return
        val line=runCatching { HiddenCorners.fitLine(hiddenWallA) }.getOrElse {
            hiddenCornerFeedback=it.message; return
        }
        if(line.rms>HiddenCorners.MAX_WALL_RMS_METRES) {
            hiddenCornerFeedback="The points don't line up. Undo a point or start over on the same wall face."
            return
        }
        hiddenDraft=hiddenDraft.next()
        hiddenCornerFeedback=null
    }
    fun clearHiddenCorner() { hiddenDraft=HiddenCornerDraft(); hiddenSegment=null; hiddenCornerFeedback=null }
    /** Places the corner where the two tapped walls meet (flagged as inferred in the draft and quality report). */
    fun placeHiddenCorner() {
        val r=room ?: run { status="Start a room first"; return }
        val b=hiddenWallB ?: run { status="Tap points along the first wall, then tap Next wall"; return }
        val segment=hiddenSegment ?: return
        if(r.confirmed) { status=ROOM_FINISHED; return }
        runCatching { HiddenCorners.cornerFromWalls(hiddenWallA,b) }.onFailure {
            hiddenCornerFeedback=it.message ?: "Those two walls don't meet. Tap points along two different walls."
        }.onSuccess { (corner,inference) ->
            if(r.corners.any { it.p.distance(corner)<0.04 }) { hiddenCornerFeedback="There's already a corner there"; return }
            val point=ScanPoint(p=corner,source="INFERRED_FROM_WALLS",segmentId=segment,timestamp=System.currentTimeMillis(),
                provenance=CornerProvenance.INFERRED,inference=inference.copy(wallPointSources=hiddenDraft.sources))
            update { d->d.copy(rooms=d.rooms.map { if(it.id==r.id) it.copy(corners=it.corners+point) else it }) }
            selectedPointId=point.id; clearHiddenCorner()
            status="Corner ${r.corners.size+1} added where the two walls meet (walls %.0f° apart) — check it with a tape later".format(inference.angleDegrees)
        }
    }
    fun chooseRoom(id: String) {if(id!=currentRoomId && doc?.rooms?.any {it.id==id}==true) {currentRoomId=id; pendingEndpoint=null; clearHiddenCorner()}}
    fun removeMeasurement(id: String)=update {it.withoutMeasurement(id)}
    fun cleanupWalls()=update { d->d.copy(walls=ScanGeometry.cleanupWalls(d.walls,d.doors.map {it.wallId}.toSet(),d.thresholds.snapMetres)) }
    fun undo() {
        // While hidden-corner wall points are pending, Undo removes the last wall point, never a saved corner.
        if(tool=="WALL_CORNER" && !hiddenDraft.isEmpty && !busy) {
            hiddenDraft=hiddenDraft.undo()
            hiddenCornerFeedback=null
            status=if(hiddenWallB==null) "Wall 1: ${hiddenWallA.size} points" else "Wall 2: ${hiddenWallB!!.size} points"
            return
        }
        if(history.isEmpty() || busy) return
        val previous=history.peekLast() ?: return
        if(activeSegment!=null && previous.segments.associate {it.id to it.alignment}!=doc!!.segments.associate {it.id to it.alignment}) {
            status="Tap Pause first — undoing this changes how the camera lines up with the map"; return
        }
        doc=doc!!.restoreGeometry(history.removeLast()).copy(updatedAt=System.currentTimeMillis(),status="DRAFT"); scheduleSave()
    }
    fun newDraft(name: String = "Mapped floor") = manageDraft("Couldn't start a floor plan") { current ->
        val next=ScanDocument(venueId=current.venueId,floorId=current.floorId,name=name.trim().take(80).ifBlank { "Mapped floor" },
            updatedAt=maxOf(System.currentTimeMillis(),current.updatedAt+1))
        if (current.updatedAt>0 || availableDrafts.any { it.id==current.id }) repository.save(current)
        repository.save(next)
        setActiveDraft(next, "New floor plan ready")
    }

    fun openDraft(id: String) = manageDraft("Couldn't open the floor plan") { current ->
        if (id==current.id) return@manageDraft
        val previous=requireNotNull(repository.load(id)) { "This draft no longer exists" }
        require(previous.floorId==current.floorId && previous.venueId==current.venueId) { "This draft belongs to another floor" }
        if (current.updatedAt>0 || availableDrafts.any { it.id==current.id }) repository.save(current)
        val next=previous.afterReopen().copy(updatedAt=maxOf(System.currentTimeMillis(),current.updatedAt+1))
        repository.save(next)
        setActiveDraft(next, "Opened ${next.name}")
    }

    fun deleteDraft(id: String) = manageDraft("Couldn't delete the draft") { current ->
        repository.deleteDraft(id,current.floorId)
        if (id==current.id) {
            setActiveDraft(ScanDocument(venueId=current.venueId,floorId=current.floorId), "Draft deleted. Start a new plan or open a previous one.",
                saved=false)
        } else status="Draft deleted"
    }

    fun clearDraft() = manageDraft("Couldn't clear the floor plan") { current ->
        val cleared=ScanDocument(id=current.id,venueId=current.venueId,floorId=current.floorId,name=current.name,
            updatedAt=System.currentTimeMillis())
        repository.save(cleared)
        setActiveDraft(cleared, "Floor plan cleared. Set a new starting point or enter measurements.")
    }

    private fun manageDraft(error: String, action: suspend (ScanDocument) -> Unit) {
        if (busy || activeSegment!=null || doc==null) return
        busy=true
        viewModelScope.launch {
            try {
                writeMutex.withLock {
                    val current=doc ?: return@withLock
                    action(current)
                    availableDrafts=repository.drafts(current.floorId)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                status="$error: ${e.message.orEmpty()}"
            } finally { busy=false }
        }
    }

    private fun ScanDocument.afterReopen() = copy(segments=segments.map { segment ->
        if(segment.source=="ARCORE" && !segment.interrupted) segment.copy(interrupted=true,reviewedAfterLoss=false) else segment
    })

    private fun setActiveDraft(next: ScanDocument, message: String, saved: Boolean = true) {
        doc=next
        draftRevision++
        currentRoomId=next.rooms.lastOrNull()?.id.orEmpty()
        selectedPointId=null; selectedWallId=null; alignmentA=null; alignmentB=null
        tool="CORNER"; pendingEndpoint=null; clearHiddenCorner()
        history.clear()
        saveStatus=if(saved) "Saved on this phone" else "Not saved yet"
        status=message
    }
    fun addRoom(name: String) { require(name.isNotBlank()); val r=ScanRoom(name=name.trim().take(80)); update { it.copy(rooms=it.rooms+r) }; chooseRoom(r.id) }
    fun renameRoom(name: String) = update { it.copy(rooms=it.rooms.map { r->if(r.id==currentRoomId) r.copy(name=name.trim().take(80)) else r }) }
    fun finishRoom() = update { d -> val r=room ?: error("Start a room first"); require(ScanGeometry.outlineError(r.corners.map { it.p })==null) { ScanGeometry.outlineError(r.corners.map { it.p }).orEmpty() }; d.copy(rooms=d.rooms.map { if(it.id==r.id) it.copy(confirmed=true) else it }) }
    fun measuredPoint(x: Double,y: Double) {
        require(x.isFinite() && y.isFinite() && abs(x)<1000 && abs(y)<1000) { "Enter distances in metres (less than 1000)" }
        // Numeric input is already in the CURRENT map frame, never in a previously scaled AR frame.
        val segment=doc!!.segments.lastOrNull {it.source=="MANUAL_MEASURED" && it.alignment==Alignment()}?.id ?: "manual:${scanId()}"
        if(doc!!.segments.none { it.id==segment }) update(false) { it.copy(segments=it.segments+ScanSegment(segment,"MANUAL_MEASURED")) }
        receivePoint(P2(x,y),scanId(),"MANUAL_MEASURED",System.currentTimeMillis(),segment)
    }
    private fun receivePoint(p: P2,id: String,source: String,time: Long,segment: String,height: Double?=null) {
        if(!p.finite) { status="That tap couldn't be measured — try again"; return }
        val r=room ?: run { status="Start a room first"; return }
        val s=doc!!.segments.firstOrNull { it.id==segment } ?: return
        if(!s.aligned || s.interrupted) { status="Finish setting the starting point and direction (steps 1 and 2) before marking corners"; return }
        val point=s.alignment.apply(p)
        when(tool) {
            "CORNER","BOUNDARY","ENTRANCE","ABOVE_CORNER" -> {
                if(r.confirmed) { status=ROOM_FINISHED; return }
                if(r.corners.any { it.p.distance(point)<0.04 }) { status="There's already a corner there"; return }
                val provenance=HiddenCorners.provenanceForHeight(height)
                update { d->d.copy(rooms=d.rooms.map { if(it.id==r.id) it.copy(corners=it.corners+ScanPoint(id,point,source,segment,time,provenance=provenance,heightAboveFloor=height)) else it }) }
                selectedPointId=id; status="Corner ${r.corners.size+1} added"+
                    if(provenance==CornerProvenance.PROJECTED) " (measured from %.1f m up the wall — check it with a tape later)".format(height) else ""
            }
            "WALL_CORNER" -> {
                if(r.confirmed) { status=ROOM_FINISHED; return }
                val samples=hiddenWallB ?: hiddenWallA
                if(samples.any { it.distance(point)<0.08 }) {
                    hiddenCornerFeedback="Move the aim sideways along this wall. Those taps land in nearly the same spot on the floor."
                    return
                }
                hiddenDraft=hiddenDraft.add(point,source)
                hiddenCornerFeedback=null
                hiddenSegment=segment
                status=if(hiddenWallB==null) "First wall: ${hiddenWallA.size} points — tap more along it, then Next wall"
                    else "Second wall: ${hiddenWallB!!.size} points — tap more along it, then Place corner"
            }
            "WALL","DOOR" -> {
                val first=pendingEndpoint
                if(first==null) {
                    pendingEndpoint=ScanPoint(id,point,source,segment,time,heightAboveFloor=height)
                    status="First end marked — now mark the other end"
                    return
                }
                pendingEndpoint=null
                val a=first.p
                val sources=listOf(first.source,source)
                if(tool=="WALL") {
                    if(a.distance(point)<0.4) { status="Wall must be at least 0.4 m long"; return }
                    val wall=ScanWall(a=a,b=point,source="GUIDED_$source",segmentId=segment,status="CONFIRMED",endpointSources=sources)
                    update { it.copy(walls=it.walls+wall) }; selectedWallId=wall.id
                } else {
                    val w=doc!!.allWalls().firstOrNull { it.id==selectedWallId }
                    if(w==null) { status="First tap the wall with the doorway on the map, then mark both sides of the opening"; return }
                    if(a.distance(ScanGeometry.projection(a,w.a,w.b))>doc!!.thresholds.doorToleranceMetres || point.distance(ScanGeometry.projection(point,w.a,w.b))>doc!!.thresholds.doorToleranceMetres) { status="Those points are too far from the chosen wall — tap both sides of the opening on that wall"; return }
                    val door=ScanDoor(wallId=w.id,a=ScanGeometry.projection(a,w.a,w.b),b=ScanGeometry.projection(point,w.a,w.b),roomIds=listOf(r.id),endpointSources=sources)
                    update { it.copy(doors=it.doors+door) }
                }
                status=if(tool=="WALL") "Wall added" else "Doorway added"
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
    fun splitWall(id: String) = update { d -> val w=d.walls.first { it.id==id }; require(d.doors.none { it.wallId==id }) { "Remove the doorway on this wall before splitting it" }; val middle=P2((w.a.x+w.b.x)/2,(w.a.y+w.b.y)/2)
        d.copy(walls=d.walls.filterNot { it.id==id }+listOf(
            w.copy(id=scanId(),b=middle,endpointSources=listOf(w.endpointSources.getOrElse(0) { "" }, "DERIVED")),
            w.copy(id=scanId(),a=middle,endpointSources=listOf("DERIVED", w.endpointSources.getOrElse(1) { "" })))) }
    fun joinWalls(first: String,second: String) = update { d -> require(d.doors.none { it.wallId==first || it.wallId==second }) { "Remove the doorways on these walls before joining them" }
        val a=d.walls.first { it.id==first }; val b=d.walls.first { it.id==second }; val merged=ScanGeometry.mergeWalls(listOf(a,b),d.thresholds.snapMetres)
        require(merged.size==1) { "Only walls that touch and run in a straight line can be joined" }; d.copy(walls=d.walls.filterNot { it.id==first || it.id==second }+merged) }
    fun door(id: String,a: P2,b: P2,roomIds: List<String>,walkable: Boolean)=update { d->require(a.finite && b.finite); d.copy(doors=d.doors.map { if(it.id==id) it.copy(a=a,b=b,roomIds=roomIds,walkable=walkable) else it }) }
    fun removeDoor(id: String)=update { it.copy(doors=it.doors.filterNot { d->d.id==id }) }
    fun measurement(a: String,b: String,length: Double,purpose: String) = update { d ->
        require(length.isFinite() && length>0 && a!=b) { "Pick two different corners and type the length you measured" }
        val points=d.points().associateBy { it.id }; val estimated=points.getValue(a).p.distance(points.getValue(b).p)
        require(estimated>0.01)
        d.copy(measurements=d.measurements+ScanMeasurement(pointA=a,pointB=b,measured=length,estimatedAtCapture=estimated,purpose=purpose))
    }
    fun applyCalibration(id: String) = update { d ->
        val m=d.measurements.first { it.id==id && it.purpose=="CALIBRATION" }; require(!m.calibrationApplied) { "This size fix is already applied" }
        val points=d.points().associateBy { it.id }; val current=points.getValue(m.pointA).p.distance(points.getValue(m.pointB).p)
        val scale=m.measured/current; require(scale in 0.5..2.0) { "That would change the map size by more than 2× — check you typed metres, or map the room again" }
        d.transformed(Alignment(scale=scale)).copy(measurements=d.measurements.map { if(it.id==id) it.copy(scaleApplied=scale) else it })
    }
    fun rotate(degrees: Double)=update { require(degrees.isFinite()); it.transformed(Alignment(radians=Math.toRadians(degrees))) }
    fun transformSection(segment: String,t: Alignment)=update { d ->
        val sourceRoomIds=d.rooms.filter { it.corners.any { p->p.segmentId==segment } }.map { it.id }
        val old=d.segments.first { it.id==segment }
        d.copy(rooms=d.rooms.map { r->r.copy(corners=r.corners.map { if(it.segmentId==segment) it.moved(t).copy(adjusted=true) else it }) },
            walls=d.walls.map { if(it.segmentId==segment) it.copy(a=t.apply(it.a),b=t.apply(it.b)) else it },
            doors=d.doors.map { door->if(sourceRoomIds.any { door.wallId.startsWith("$it:") } || d.walls.any { it.id==door.wallId && it.segmentId==segment }) door.copy(a=t.apply(door.a),b=t.apply(door.b)) else door },
            poses=d.poses.map {if(it.segmentId==segment) it.copy(mapPoint=it.mapPoint?.let(t::apply)) else it},
            segments=d.segments.map { if(it.id==segment) it.copy(alignment=t.after(old.alignment),aligned=true,reviewedAfterLoss=true) else it })
    }
    fun reviewInterruptedSections()=update { it.copy(segments=it.segments.map { s->s.copy(reviewedAfterLoss=true) }) }
    fun thresholds(snap: Double,tolerance: Double,inlier: Double,minimumWall: Double)=update { require(snap in 0.001..0.3 && tolerance in 0.01..0.2 && inlier in .01..0.2 && minimumWall in .4..2.0) {
            "Allowed ranges: snap 0.001–0.3 m, error 1–20 %, wall-detection 0.01–0.2 m, shortest wall 0.4–2 m" }; it.copy(thresholds=it.thresholds.copy(snapMetres=snap,validationRelativeTolerance=tolerance,lineInlierMetres=inlier,minimumWallMetres=minimumWall)) }
    fun publish(done: (String)->Unit) {
        val value=doc ?: return; if(busy) return
        busy=true
        viewModelScope.launch { try { writeMutex.withLock { saveJob?.cancel(); repository.save(value); val floor=repository.publish(value); doc=repository.load(value.id); status="Floor plan saved"; done(floor) } }
            catch(e: Exception) { status="Couldn't save the floor plan: ${e.message}" } finally { busy=false } }
    }
    fun cameraStarted(id: String) { clearHiddenCorner(); activeSegment=id; pendingEndpoint=null; floorDescription=""; arNotice=null; arStatus=ArStatus("Starting the camera…") }
    fun cameraStopped() { activeSegment?.let(::interrupted); activeSegment=null; floorDescription=""; arNotice=null; save() }
    private fun main(block: ()->Unit) { viewModelScope.launch(Dispatchers.Main.immediate) { if(!busy) block() } }
    override fun status(value: ArStatus)=main {
        if(value.segment!=null && value.segment!=activeSegment) return@main
        if(!value.notice) {
            if(arStatus.tracking && !value.tracking) { arNotice=null; noticeJob?.cancel() }
            arStatus=value
            return@main
        }
        // A tap notice is shown for a few seconds over the coaching line; it never replaces the tracking state.
        arNotice=value.text; arStatus=arStatus.copy(originSet=value.originSet)
        noticeJob?.cancel(); noticeJob=viewModelScope.launch { delay(4000); arNotice=null }
    }
    override fun floorCandidate(description: String)=main { if(activeSegment!=null) floorDescription=description }
    override fun reference(frame: FloorFrame,segmentId: String,length: Double) = main {
        if(segmentId!=activeSegment) return@main
        val d=doc ?: return@main
        // A redone starting point re-sends the reference; a session that is already lined up keeps its first alignment.
        if(d.segments.any { it.id==segmentId }) return@main
        val a=d.points().firstOrNull { it.id==alignmentA }; val b=d.points().firstOrNull { it.id==alignmentB }
        val alignment=if(d.points().isEmpty()) Alignment() else if(a!=null && b!=null)
            runCatching { Alignment.between(P2(0.0,0.0),P2(0.0,length),a.p,b.p) }.getOrElse { status=it.message.orEmpty(); return@main }
            else { status="Pick two saved corners first, so the camera can line up with your map"; return@main }
        if(alignment.scale !in 0.8..1.25) { status="The two spots you tapped are a different distance apart than the saved corners A and B. Tap Pause and try again, tapping exactly on those corners."; return@main }
        update(false) { it.copy(segments=it.segments+ScanSegment(segmentId,"ARCORE",frame,alignment,referencePointIds=listOfNotNull(a?.id,b?.id))) }
        floorDescription=""; status=if(a==null) "Map direction set — now mark the corners of the room" else "Lined up with your saved map — carry on marking corners"
    }
    override fun point(point: P2,id: String,source: String,timestamp: Long,segment: String,heightAboveFloor: Double)=main { receivePoint(point,id,source,timestamp,segment,heightAboveFloor) }
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
        // A confirmed outline was validated as drawn; anchor drift must not reshape it behind the user's back.
        update(false) { d->d.copy(rooms=d.rooms.map { r->if(r.confirmed) r else r.copy(corners=r.corners.map { p->if(!p.adjusted && p.segmentId==segment && points.containsKey(p.id)) p.copy(p=s.alignment.apply(points.getValue(p.id))) else p }) }) }
    }
    override fun interrupted(segment: String)=main {
        val id=activeSegment?.takeIf {it==segment} ?: return@main
        update(false) { it.copy(segments=it.segments.map { s->if(s.id==id) s.copy(interrupted=true,reviewedAfterLoss=false) else s }) }
        status="Camera stopped. Your work is saved. To carry on, start the camera again and line it up with two saved corners."
    }
    private companion object { const val ROOM_FINISHED="This room is marked finished — tap Reopen room to add more corners" }
}
