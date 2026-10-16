package com.turn.fieldtest.mapper.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.turn.fieldtest.mapper.WalkMapperViewModel
import com.turn.fieldtest.mapper.ar.ArScanView
import com.turn.fieldtest.mapper.core.*
import kotlin.math.abs

@Composable
fun WalkMapperScreen(vm: WalkMapperViewModel,camera: ArScanView?,availability: String,arSupported: Boolean,
    onStartCamera: ()->Unit,onStopCamera: ()->Unit,onExit: ()->Unit,onPublish: ()->Unit,onJson: ()->Unit,onPng: ()->Unit,onCheckSupport: ()->Unit={}) {
    val doc=vm.doc
    var tab by remember { mutableStateOf("Capture") }
    var preview by remember { mutableStateOf(false) }; var debug by remember { mutableStateOf(false) }
    var roomName by remember { mutableStateOf("") }
    var x by remember { mutableStateOf("") }; var y by remember { mutableStateOf("") }
    var exitDialog by remember { mutableStateOf(false) }
    fun attempt(action: ()->Unit) { runCatching(action).onFailure { vm.message(it.message ?: "Check entered values") } }
    BackHandler { exitDialog=true }
    if(exitDialog) AlertDialog(onDismissRequest={exitDialog=false},title={Text("Save and leave mapper?")},
        text={Text("Your geometry is kept locally. A new camera session must be aligned to two saved corners.")},
        confirmButton={TextButton(onClick={ onStopCamera(); onExit(); exitDialog=false }) { Text("Save and leave") }},
        dismissButton={TextButton(onClick={exitDialog=false}) { Text("Keep editing") }})
    Surface(Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
            val surfaceHeight=(maxHeight*0.38f).coerceIn(140.dp,440.dp)
            val compact=maxHeight<600.dp
            val panelHeight=maxHeight*.8f
            Column(Modifier.fillMaxSize().then(if(compact) Modifier.verticalScroll(rememberScrollState()) else Modifier).padding(horizontal=12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text("TURN Walk Mapper",style=MaterialTheme.typography.titleLarge)
                        Text(vm.room?.name ?: "Create a room to begin",style=MaterialTheme.typography.labelMedium)
                    }
                    TextButton(onClick={exitDialog=true}) { Text("Close") }
                }
                Text(if(camera!=null) vm.arStatus.text else availability,style=MaterialTheme.typography.bodySmall,maxLines=2)
                if(camera!=null) Text(vm.arStatus.depth,style=MaterialTheme.typography.labelSmall)
                if(doc==null) { Text(vm.status); return@Column }
                Box(Modifier.fillMaxWidth().height(surfaceHeight)) {
                    if(camera!=null) AndroidView(factory={camera},modifier=Modifier.fillMaxSize(),update={view->
                        view.tool=vm.tool; view.overlay=doc.allWalls()+doc.walls.filter { it.status=="CANDIDATE" }
                        view.mapAlignment=doc.segments.firstOrNull { it.id==vm.activeSegment }?.alignment ?: Alignment()
                        view.thresholds=doc.thresholds
                    })
                    if(camera==null || preview) ScanPreview(doc,vm.selectedPointId ?: vm.selectedWallId,debug,Modifier.fillMaxSize(),
                        onPoint={vm.selectedPointId=it; vm.selectedWallId=null},onWall={vm.selectedWallId=it; vm.selectedPointId=null})
                }
                if(camera!=null && vm.floorDescription.isNotEmpty()) {
                    Text(vm.floorDescription,style=MaterialTheme.typography.bodySmall)
                    ActionRow { Button(onClick={camera.confirmFloor()}) { Text("Confirm floor / origin") }; TextButton(onClick={camera.rejectFloor()}) { Text("Reject") } }
                }
                ActionRow {
                    if(camera!=null) {
                        Button(onClick=onStopCamera) { Text("Pause / review") }
                        FilterChip(preview,{preview=!preview},label={Text("2D preview")})
                    } else OutlinedButton(onClick=onStartCamera,enabled=arSupported && vm.cameraMayStart && vm.room!=null) { Text("Start camera") }
                    TextButton(onClick={debug=!debug}) { Text(if(debug) "Hide path" else "Show path") }
                    TextButton(onClick=vm::undo,enabled=!vm.busy) { Text("Undo") }
                }
                Text(vm.status,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.primary,maxLines=3)
                ActionRow { listOf("Capture","Review","Verify & save").forEach { title->FilterChip(tab==title,{tab=title},label={Text(title)}) } }
                Column((if(compact) Modifier.height(panelHeight) else Modifier.weight(1f)).fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom=16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                    when(tab) {
                        "Capture" -> {
                            Text("AR or measured drawing",style=MaterialTheme.typography.titleMedium)
                            if(camera==null) TextButton(onClick=onCheckSupport) {Text("Check AR support again")}
                            Text("Camera and geometry stay on this phone. AR surfaces are estimates; verify dimensions with a tape. Without ARCore, enter measured corners below.",style=MaterialTheme.typography.bodySmall)
                            Choice("Current room",vm.currentRoomId,doc.rooms.map { it.id to it.name },vm::chooseRoom)
                            OutlinedTextField(roomName,{roomName=it},label={Text("Room name")},modifier=Modifier.fillMaxWidth(),singleLine=true)
                            ActionRow {
                                Button(onClick={attempt { vm.addRoom(roomName); roomName="" }},enabled=roomName.isNotBlank() && !vm.busy) { Text("Start next room") }
                                TextButton(onClick={vm.renameRoom(roomName)},enabled=roomName.isNotBlank() && vm.room!=null) { Text("Rename") }
                                OutlinedButton(onClick=vm::finishRoom,enabled=vm.room!=null && !vm.busy) { Text("Confirm outline") }
                            }
                            vm.room?.let { r ->
                                val points=r.corners.map { it.p }
                                Text("${r.corners.size} corners · ${if(r.confirmed) "outline confirmed" else "outline draft"}",style=MaterialTheme.typography.labelMedium)
                                if(points.size>=2) Text("Extent %.2f × %.2f m · observation coverage unknown".format(points.maxOf { it.x }-points.minOf { it.x },points.maxOf { it.y }-points.minOf { it.y }),style=MaterialTheme.typography.bodySmall)
                            }
                            ActionRow { listOf("CORNER" to "Corner","WALL" to "Wall endpoints","DOOR" to "Door endpoints","ENTRANCE" to "Entrance corner","BOUNDARY" to "Boundary corner").forEach { (id,label)->
                                FilterChip(vm.tool==id,{vm.chooseTool(id)},label={Text(label)})
                            } }
                            Text(if(vm.tool=="DOOR") "Select a confirmed wall in 2D preview, then mark its two opening endpoints. Link the rooms in Review."
                                else "Mark corners in perimeter order. A wall needs two endpoints. Entrance and boundary corners belong to the room outline.",style=MaterialTheme.typography.bodySmall)
                            if(camera==null) {
                                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                    NumberField("X (m)",x,{x=it},Modifier.weight(1f)); NumberField("Y (m)",y,{y=it},Modifier.weight(1f))
                                }
                                Button(onClick={attempt { vm.measuredPoint(x.toDouble(),y.toDouble()) }},enabled=vm.room!=null && !vm.busy) { Text("Add measured point") }
                            }
                            if(camera==null && doc.points().isNotEmpty()) {
                                Text("Align the next camera section",style=MaterialTheme.typography.titleMedium)
                                Text("Choose two existing physical floor corners ≥0.5 m apart. In camera view, tap A as the floor origin, confirm it, then tap B as the forward point. Use the exact same physical locations.",style=MaterialTheme.typography.bodySmall)
                                PointChoice("Reference A / origin",vm.alignmentA,doc) { vm.alignmentA=it }
                                PointChoice("Reference B / forward",vm.alignmentB,doc) { vm.alignmentB=it }
                            }
                            if(camera!=null) Text("Move slowly with good light. Tap the actual floor, confirm it, then tap a point ahead. Tap real room corners or wall bases; no reliable hit means no point is stored.",style=MaterialTheme.typography.bodySmall)
                        }
                        "Review" -> if(camera!=null) Text("Pause the camera to edit or correct geometry.") else {
                            Text("Room outlines",style=MaterialTheme.typography.titleMedium)
                            Choice("Room to edit",vm.currentRoomId,doc.rooms.map { it.id to it.name },vm::chooseRoom)
                            TextButton(onClick=vm::unconfirmRoom,enabled=vm.room!=null) { Text("Reopen outline for editing") }
                            TextButton(onClick=vm::removeRoom,enabled=vm.room!=null) {Text("Delete current room (undo available)")}
                            vm.room?.corners?.forEachIndexed { i,p -> key(p.id) {
                                PointEditor("Corner ${i+1} · ${p.id.take(6)}",p.p,selected=vm.selectedPointId==p.id,onSelect={vm.selectedPointId=p.id; vm.selectedWallId=null},
                                    onApply={vm.movePoint(p.id,it)},onDelete={vm.deletePoint(p.id)})
                            } }
                            Text("Walls · ${doc.walls.count { it.status=="CANDIDATE" }} candidates",style=MaterialTheme.typography.titleMedium)
                            Choice("Select wall",vm.selectedWallId,doc.allWalls().map { it.id to "${it.source.lowercase()} · ${it.id.take(6)}" }+
                                doc.walls.filter { it.status=="CANDIDATE" }.map { it.id to "Candidate · ${it.id.take(6)}" }) {vm.selectedWallId=it; vm.selectedPointId=null}
                            doc.walls.firstOrNull { it.id==vm.selectedWallId }?.let { w -> key(w.id,w.a,w.b) {
                                Text("${w.source} · ${w.observations} observations · quality %.0f%% · height %.2f m".format(w.confidence*100,w.height),style=MaterialTheme.typography.bodySmall)
                                EndpointEditor(w.a,w.b,onApply={a,b->vm.editWall(w.id,a,b)})
                                ActionRow {
                                    Button(onClick={vm.wallDecision(w.id,true)}) { Text("Confirm wall") }
                                    TextButton(onClick={vm.wallDecision(w.id,false)}) { Text("Reject / remove") }
                                    TextButton(onClick={vm.splitWall(w.id)}) { Text("Split midpoint") }
                                }
                                var other by remember { mutableStateOf<String?>(null) }
                                Choice("Join with",other,doc.walls.filter { it.id!=w.id && it.status!="REJECTED" }.map { it.id to it.id.take(8) }) {other=it}
                                OutlinedButton(onClick={other?.let {vm.joinWalls(w.id,it)}},enabled=other!=null) {Text("Join touching collinear walls")}
                            } }
                            if(vm.selectedWallId?.contains(':')==true && doc.walls.none {it.id==vm.selectedWallId}) Text("Outline wall: adjust its room corners above. Use Door endpoints to open it.",style=MaterialTheme.typography.bodySmall)
                            OutlinedButton(onClick=vm::cleanupWalls) {Text("Snap and split confirmed wall intersections")}
                            Text("Cleanup uses the configured metre tolerance; doorway walls keep their IDs and opening gaps. Undo restores the draft.",style=MaterialTheme.typography.bodySmall)
                            Text("Doorways",style=MaterialTheme.typography.titleMedium)
                            doc.doors.forEach { door->key(door.id) { DoorEditor(door,doc,vm) } }
                            Text("Alignment and corrections",style=MaterialTheme.typography.titleMedium)
                            Text("Pause capture before editing. Moving corners reopens their outline; confirm it again after checking the doorways.",style=MaterialTheme.typography.bodySmall)
                            var section by remember { mutableStateOf<String?>(null) }
                            Choice("Capture section",section,doc.segments.map { it.id to "${it.source} · ${it.id.take(6)}${if(it.interrupted) " · interrupted" else ""}" }) { section=it }
                            var dx by remember {mutableStateOf("0")}; var dy by remember {mutableStateOf("0")}; var angle by remember {mutableStateOf("0")}; var scale by remember {mutableStateOf("1")}
                            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {NumberField("Shift X (m)",dx,{dx=it},Modifier.weight(1f)); NumberField("Shift Y (m)",dy,{dy=it},Modifier.weight(1f))}
                            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {NumberField("Rotate (°)",angle,{angle=it},Modifier.weight(1f)); NumberField("Scale",scale,{scale=it},Modifier.weight(1f))}
                            OutlinedButton(onClick={attempt {vm.transformSection(requireNotNull(section),Alignment(scale.toDouble(),Math.toRadians(angle.toDouble()),dx.toDouble(),dy.toDouble()))}},enabled=section!=null) {Text("Apply section correction")}
                            OutlinedButton(onClick={attempt {vm.rotate(angle.toDouble())}}) {Text("Rotate entire map")}
                            if(doc.segments.any { it.interrupted && !it.reviewedAfterLoss }) OutlinedButton(onClick=vm::reviewInterruptedSections) {Text("I checked interrupted sections against measurements")}
                        }
                        else -> {
                            Text("Measured verification",style=MaterialTheme.typography.titleMedium)
                            var a by remember {mutableStateOf<String?>(null)}; var b by remember {mutableStateOf<String?>(null)}
                            var length by remember {mutableStateOf("")}; var purpose by remember {mutableStateOf("VALIDATION")}
                            PointChoice("Length point A",a,doc) {a=it}; PointChoice("Length point B",b,doc) {b=it}
                            NumberField("Physical measured distance (m)",length,{length=it},Modifier.fillMaxWidth())
                            Choice("Purpose",purpose,listOf("VALIDATION" to "Independent validation","CALIBRATION" to "Scale calibration")) {purpose=it}
                            Button(onClick={attempt {vm.measurement(requireNotNull(a),requireNotNull(b),length.toDouble(),purpose)}},enabled=a!=null && b!=null && camera==null) {Text("Record measurement")}
                            Text("Use a different, independently measured pair for validation. Calibration is not evidence of accuracy.",style=MaterialTheme.typography.bodySmall)
                            doc.measurements.forEach { m ->
                                val points=doc.points().associateBy {it.id}; val estimate=points[m.pointA]?.p?.let {p->points[m.pointB]?.p?.let(p::distance)}
                                Text("${m.purpose.lowercase()} · measured %.2f m · captured %.2f m".format(m.measured,m.estimatedAtCapture),style=MaterialTheme.typography.bodySmall)
                                if(estimate!=null) Text("Now %.2f m · error %.2f m / %.1f%% · %s".format(estimate,abs(estimate-m.measured),100*abs(estimate-m.measured)/m.measured,if(m.calibrationApplied) "scale ×%.3f".format(m.scaleApplied) else "no scale applied"),style=MaterialTheme.typography.labelSmall)
                                ActionRow { if(m.purpose=="CALIBRATION") TextButton(onClick={vm.applyCalibration(m.id)},enabled=!m.calibrationApplied && camera==null) {Text("Apply scale correction")}
                                    TextButton(onClick={vm.removeMeasurement(m.id)},enabled=camera==null && !m.calibrationApplied) {Text("Remove measurement")} }
                            }
                            var snap by remember {mutableStateOf(doc.thresholds.snapMetres.toString())}; var tolerance by remember {mutableStateOf((doc.thresholds.validationRelativeTolerance*100).toString())}
                            var inlier by remember {mutableStateOf(doc.thresholds.lineInlierMetres.toString())}; var minimumWall by remember {mutableStateOf(doc.thresholds.minimumWallMetres.toString())}
                            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {NumberField("Snap (m)",snap,{snap=it},Modifier.weight(1f)); NumberField("Check tolerance (%)",tolerance,{tolerance=it},Modifier.weight(1f))}
                            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {NumberField("Line inlier (m)",inlier,{inlier=it},Modifier.weight(1f)); NumberField("Min. wall (m)",minimumWall,{minimumWall=it},Modifier.weight(1f))}
                            TextButton(onClick={attempt {vm.thresholds(snap.toDouble(),tolerance.toDouble()/100,inlier.toDouble(),minimumWall.toDouble())}},enabled=camera==null) {Text("Apply thresholds")}
                            Text("Quality checks",style=MaterialTheme.typography.titleMedium)
                            val issues=remember(doc.rooms,doc.doors,doc.walls,doc.segments,doc.measurements,doc.thresholds) {ScanGeometry.issues(doc)}
                            if(issues.isEmpty()) Text("Geometry and measured checks passed. Review the proposed paths after saving.")
                            issues.forEach { Text("${if(it.blocking) "Fix" else "Note"}: ${it.message}",style=MaterialTheme.typography.bodySmall,color=if(it.blocking) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }
                            OutlinedTextField(doc.name,{vm.name(it)},label={Text("New mapped floor name")},modifier=Modifier.fillMaxWidth(),singleLine=true)
                            Button(onClick=onPublish,enabled=camera==null && !vm.busy && issues.none {it.blocking} && doc.name.isNotBlank(),modifier=Modifier.fillMaxWidth()) {Text(if(vm.busy) "Generating map…" else "Save as new TURN project")}
                            if(doc.publishedFloorId!=null) OutlinedButton(onClick=onExit) {Text("Open saved floor in TURN")}
                            doc.publicationIssues.forEach {Text(it,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error)}
                            Text("Creates a separate mapped project, PNG, walkable regions, walls, destinations and safe path proposals. Your current floor and fingerprints are preserved. Complete any missing paths in Map Setup.",style=MaterialTheme.typography.bodySmall)
                            ActionRow {OutlinedButton(onClick=vm::save) {Text("Save draft")}; TextButton(onClick=onJson) {Text("Export scan JSON")}; TextButton(onClick=onPng,enabled=doc.publishedFloorId!=null) {Text("Export PNG")} }
                            Text(vm.saveStatus,style=MaterialTheme.typography.labelSmall)
                            if(camera==null) {
                                OutlinedButton(onClick=vm::newDraft,enabled=!vm.busy) {Text("Start another draft")}
                                vm.availableDrafts.forEach { draft->TextButton(onClick={vm.openDraft(draft.id)}) {Text("Open draft ${draft.id.take(8)} · ${draft.status.lowercase()}")} }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun DoorEditor(d: ScanDoor,doc: ScanDocument,vm: WalkMapperViewModel) {
    var a by remember(d.a) { mutableStateOf(d.a) }; var b by remember(d.b) {mutableStateOf(d.b)}
    var width by remember(d.a,d.b) {mutableStateOf("%.3f".format(java.util.Locale.US,d.a.distance(d.b)))}
    Text("Opening ${d.id.take(6)} · %.2f m".format(d.a.distance(d.b)),style=MaterialTheme.typography.labelLarge)
    EndpointEditor(d.a,d.b) {x,y->a=x; b=y; vm.door(d.id,x,y,d.roomIds,d.walkable)}
    NumberField("Measured width (m)",width,{width=it},Modifier.fillMaxWidth())
    TextButton(onClick={runCatching {val w=width.toDouble(); require(w in .45..3.0); val length=a.distance(b); require(length>.01); val end=P2(a.x+(b.x-a.x)*w/length,a.y+(b.y-a.y)*w/length); vm.door(d.id,a,end,d.roomIds,d.walkable)}.onFailure {vm.message("Check opening width and endpoints")}}) {Text("Set width from first endpoint")}
    doc.rooms.forEach { r->Row {Checkbox(r.id in d.roomIds,{checked->vm.door(d.id,d.a,d.b,if(checked) (d.roomIds+r.id).distinct() else d.roomIds-r.id,d.walkable)}); Text(r.name,Modifier.padding(top=12.dp))} }
    Row {Switch(d.walkable,{vm.door(d.id,d.a,d.b,d.roomIds,it)}); Text("Walkable opening",Modifier.padding(12.dp))}
    TextButton(onClick={vm.removeDoor(d.id)}) {Text("Delete opening")}
}
@Composable private fun PointEditor(label: String,p: P2,selected: Boolean,onSelect: ()->Unit,onApply: (P2)->Unit,onDelete: ()->Unit) {
    var x by remember(p) {mutableStateOf(p.x.toString())}; var y by remember(p) {mutableStateOf(p.y.toString())}
    TextButton(onClick=onSelect) {Text(if(selected) "$label · selected" else label)}
    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {NumberField("X (m)",x,{x=it},Modifier.weight(1f)); NumberField("Y (m)",y,{y=it},Modifier.weight(1f))}
    ActionRow {TextButton(onClick={val a=x.toDoubleOrNull(); val b=y.toDoubleOrNull(); if(a!=null && b!=null) onApply(P2(a,b))}) {Text("Move point")}; TextButton(onClick=onDelete) {Text("Delete point")} }
}
@Composable private fun EndpointEditor(a: P2,b: P2,onApply: (P2,P2)->Unit) {
    var ax by remember(a) {mutableStateOf(a.x.toString())}; var ay by remember(a) {mutableStateOf(a.y.toString())}
    var bx by remember(b) {mutableStateOf(b.x.toString())}; var byy by remember(b) {mutableStateOf(b.y.toString())}
    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {NumberField("A x (m)",ax,{ax=it},Modifier.weight(1f)); NumberField("A y (m)",ay,{ay=it},Modifier.weight(1f))}
    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {NumberField("B x (m)",bx,{bx=it},Modifier.weight(1f)); NumberField("B y (m)",byy,{byy=it},Modifier.weight(1f))}
    TextButton(onClick={val values=listOf(ax,ay,bx,byy).map {it.toDoubleOrNull()}; if(values.all {it!=null}) onApply(P2(values[0]!!,values[1]!!),P2(values[2]!!,values[3]!!))}) {Text("Apply endpoints")}
}
@Composable internal fun Choice(label: String,selected: String?,items: List<Pair<String,String>>,onSelect: (String)->Unit) {
    var open by remember {mutableStateOf(false)}
    Box {OutlinedButton(onClick={open=true},enabled=items.isNotEmpty()) {Text("$label: ${items.firstOrNull {it.first==selected}?.second ?: "Choose"}")}
        DropdownMenu(open,{open=false}) {items.forEach {(id,title)->DropdownMenuItem(text={Text(title)},onClick={open=false; onSelect(id)})}} }
}
@Composable private fun PointChoice(label: String,selected: String?,doc: ScanDocument,onSelect: (String)->Unit) =
    Choice(label,selected,doc.rooms.flatMap {r->r.corners.mapIndexed {i,p->p.id to "${r.name} ${i+1} · %.2f, %.2f m".format(p.p.x,p.p.y)}} ,onSelect)
@Composable private fun NumberField(label: String,value: String,onChange: (String)->Unit,modifier: Modifier)=
    OutlinedTextField(value,onChange,label={Text(label)},modifier=modifier,singleLine=true)
@Composable private fun ActionRow(content: @Composable RowScope.()->Unit) =
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp),content=content)
