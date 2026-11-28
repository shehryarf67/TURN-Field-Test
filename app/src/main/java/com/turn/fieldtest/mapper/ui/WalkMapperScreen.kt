package com.turn.fieldtest.mapper.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment as ComposeAlignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.turn.fieldtest.mapper.WalkMapperViewModel
import com.turn.fieldtest.mapper.ar.ArScanView
import com.turn.fieldtest.mapper.core.*
import com.turn.fieldtest.ui.components.AdvancedSection
import com.turn.fieldtest.ui.components.ConfirmDialog
import kotlin.math.abs

internal const val DETAIL_MARK = "Mark"
internal const val DETAIL_EDIT = "Edit"
internal const val DETAIL_SAVE = "Measure & save"

/** The Advanced editor: every mapper tool on one page, for correcting details the step-by-step flow does not cover. */
@Composable
internal fun MapperDetailsScreen(vm: WalkMapperViewModel,camera: ArScanView?,availability: String,arSupported: Boolean,
    onStartCamera: ()->Unit,onStopCamera: ()->Unit,onExit: ()->Unit,onPublish: ()->Unit,onJson: ()->Unit,onPng: ()->Unit,onCheckSupport: ()->Unit={}, initialSection: String=DETAIL_MARK) {
    val doc=vm.doc
    val keyboard = LocalSoftwareKeyboardController.current
    var tab by remember { mutableStateOf(initialSection) }
    var preview by remember { mutableStateOf(false) }; var debug by remember { mutableStateOf(false) }
    LaunchedEffect(camera) { preview=false }
    var roomName by remember { mutableStateOf("") }
    var x by remember { mutableStateOf("") }; var y by remember { mutableStateOf("") }
    var exitDialog by remember { mutableStateOf(false) }
    var deleteRoom by remember { mutableStateOf(false) }
    var showFloorPlans by remember { mutableStateOf(false) }
    fun attempt(action: ()->Unit) { runCatching(action).onFailure { vm.message(it.message ?: "Check the values you entered") } }
    BackHandler { exitDialog=true }
    if(exitDialog) AlertDialog(onDismissRequest={exitDialog=false},title={Text("Leave the advanced editor?")},
        text={Text("Your changes are saved on this phone.")},
        confirmButton={TextButton(onClick={ onStopCamera(); onExit(); exitDialog=false }) { Text("Save and leave") }},
        dismissButton={TextButton(onClick={exitDialog=false}) { Text("Keep editing") }})
    if(deleteRoom) ConfirmDialog("Delete ${vm.room?.name ?: "this room"}?","Its corners, doorway links and measurements are removed. You can bring it back with Undo.",
        "Delete room",onConfirm=vm::removeRoom,onDismiss={deleteRoom=false})
    if(showFloorPlans) FloorPlanDrafts(vm,onStopCamera) { showFloorPlans=false }
    Surface(Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
            val surfaceHeight=(maxHeight*0.38f).coerceIn(140.dp,440.dp)
            val compact=maxHeight<600.dp
            val scrollPage=compact || camera==null
            Column(Modifier.fillMaxSize().then(if(scrollPage) Modifier.verticalScroll(rememberScrollState()) else Modifier).padding(horizontal=12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text("Advanced editor",style=MaterialTheme.typography.titleLarge)
                        Text("Fix corners, walls, doorways and settings by hand. ${vm.room?.name?.let { "Room: $it" } ?: "Add a room to begin."}",style=MaterialTheme.typography.labelMedium)
                    }
                    TextButton(onClick={exitDialog=true}) { Text("Close") }
                }
                Surface(shape=MaterialTheme.shapes.large,color=MaterialTheme.colorScheme.secondaryContainer) {
                    Text(if(camera!=null) vm.arNotice ?: vm.arStatus.text else availability,
                        modifier=Modifier.padding(12.dp),style=MaterialTheme.typography.bodyMedium)
                }
                if(camera!=null) Text(vm.arStatus.depth,style=MaterialTheme.typography.labelSmall)
                if(doc==null) { Text(vm.status); return@Column }
                Box(Modifier.fillMaxWidth().height(surfaceHeight).clip(MaterialTheme.shapes.extraLarge)) {
                    if(camera!=null) AndroidView(factory={camera},modifier=Modifier.fillMaxSize(),update={view->
                        view.tool=vm.tool; view.overlay=doc.allWalls()+doc.walls.filter { it.status=="CANDIDATE" }
                        view.mapAlignment=doc.segments.firstOrNull { it.id==vm.activeSegment }?.alignment ?: Alignment()
                        view.thresholds=doc.thresholds
                    })
                    if(camera==null && doc.points().isEmpty()) Surface(color=MaterialTheme.colorScheme.surfaceContainerLow,modifier=Modifier.fillMaxSize()) {
                        Column(Modifier.fillMaxSize().padding(24.dp),verticalArrangement=Arrangement.Center,horizontalAlignment=ComposeAlignment.CenterHorizontally) {
                            Text(if(arSupported) "Your floor plan starts here" else "Map with measurements",style=MaterialTheme.typography.titleLarge)
                            Spacer(Modifier.height(12.dp))
                            Text(if(arSupported) "Start the camera to mark the floor and room corners. The outline appears here as you go."
                                else "This phone can't map with the camera: add a room and type in measured corners below.",style=MaterialTheme.typography.bodyMedium)
                        }
                    } else if(camera==null || preview) ScanPreview(doc,vm.selectedPointId ?: vm.selectedWallId,debug,Modifier.fillMaxSize(),
                        onPoint={vm.selectedPointId=it; vm.selectedWallId=null},onWall={vm.selectedWallId=it; vm.selectedPointId=null})
                }
                if(camera!=null && vm.floorDescription.isNotEmpty()) {
                    Text(vm.floorDescription,style=MaterialTheme.typography.bodySmall)
                    ActionRow { Button(onClick={camera.confirmFloor()},enabled=vm.arStatus.tracking) { Text("Yes, this is the floor") }; TextButton(onClick={camera.rejectFloor()}) { Text("No, try again") } }
                }
                if(camera==null) Button(onClick=onStartCamera,enabled=arSupported && !vm.busy,
                    modifier=Modifier.fillMaxWidth().heightIn(min=52.dp)) { Text("Start camera") }
                ActionRow {
                    if(camera!=null) {
                        Button(onClick=onStopCamera) { Text("Pause camera") }
                        FilterChip(preview,{preview=!preview},label={Text("Show map")})
                    }
                    TextButton(onClick={debug=!debug}) { Text(if(debug) "Hide walked path" else "Show walked path") }
                    TextButton(onClick=vm::undo,enabled=!vm.busy) { Text("Undo") }
                }
                Text(vm.status,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.primary,maxLines=3)
                if(camera==null && arSupported) Text(
                    if(doc.points().isEmpty()) "1 · Start camera   2 · Tap the floor, then a point ahead   3 · Mark room corners"
                    else if(!vm.cameraMayStart) "Before starting the camera again: pick Saved corner A and B in the Mark tab so it can line up with your map."
                    else "Ready · start the camera, then tap the floor at saved corner A and then B.",
                    style=MaterialTheme.typography.bodySmall)
                val sections=listOf(DETAIL_MARK,DETAIL_EDIT,DETAIL_SAVE)
                TabRow(selectedTabIndex=sections.indexOf(tab).coerceAtLeast(0)) {
                    sections.forEach { title -> Tab(selected=tab==title,onClick={tab=title},text={Text(title)}) }
                }
                Column((if(scrollPage) Modifier else Modifier.weight(1f).verticalScroll(rememberScrollState())).fillMaxWidth().padding(bottom=16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                    when(tab) {
                        DETAIL_MARK -> {
                            Text("Rooms and corners",style=MaterialTheme.typography.titleMedium)
                            if(camera==null) TextButton(onClick=onCheckSupport) {Text("Check camera support again")}
                            Text("Everything stays on this phone. Camera measurements are estimates — check sizes with a tape. Without camera support, type measured corners below.",style=MaterialTheme.typography.bodySmall)
                            Choice("Current room",vm.currentRoomId,doc.rooms.map { it.id to it.name },vm::chooseRoom)
                            OutlinedTextField(roomName,{roomName=it},label={Text("Room name")},modifier=Modifier.fillMaxWidth(),singleLine=true,
                                keyboardOptions=KeyboardOptions(imeAction=ImeAction.Done), keyboardActions=KeyboardActions(onDone={keyboard?.hide()}))
                            ActionRow {
                                Button(onClick={attempt { vm.addRoom(roomName); roomName="" }},enabled=roomName.isNotBlank() && !vm.busy) { Text("Start new room") }
                                TextButton(onClick={vm.renameRoom(roomName)},enabled=roomName.isNotBlank() && vm.room!=null) { Text("Rename") }
                                OutlinedButton(onClick=vm::finishRoom,enabled=vm.room!=null && !vm.busy) { Text("Finish room") }
                            }
                            vm.room?.let { r ->
                                val points=r.corners.map { it.p }
                                Text("${r.corners.size} corners · ${if(r.confirmed) "finished" else "not finished"}",style=MaterialTheme.typography.labelMedium)
                                if(points.size>=2) Text("Size about %.2f × %.2f m".format(points.maxOf { it.x }-points.minOf { it.x },points.maxOf { it.y }-points.minOf { it.y }),style=MaterialTheme.typography.bodySmall)
                            }
                            ActionRow { MapperTools.forEach { (id,label)->
                                FilterChip(vm.tool==id,{vm.chooseTool(id)},label={Text(label)})
                            } }
                            Text(if(vm.tool=="DOOR") "Doorway: tap the wall on the map, then mark the two sides of the opening. Tick its rooms in the Edit tab."
                                else "Mark corners in order around the room. A wall needs two ends. Entrance and open-side corners are part of the room outline. If furniture hides a corner, tap the wall or ceiling above it, or use Corner behind furniture.",style=MaterialTheme.typography.bodySmall)
                            if(vm.tool=="WALL_CORNER") HiddenCornerControls(vm)
                            if(camera==null) {
                                Text("X is metres to the right of your starting corner, Y is metres forward.",style=MaterialTheme.typography.bodySmall)
                                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                    NumberField("X (m)",x,{x=it},Modifier.weight(1f)); NumberField("Y (m)",y,{y=it},Modifier.weight(1f))
                                }
                                Button(onClick={attempt { vm.measuredPoint(x.toDouble(),y.toDouble()) }},enabled=vm.room!=null && !vm.busy) { Text("Add measured corner") }
                            }
                            if(camera==null && doc.points().isNotEmpty()) {
                                Text("Line the camera up with your saved map",style=MaterialTheme.typography.titleMedium)
                                Text("Pick two corners you've already marked, at least half a metre apart. With the camera on, tap the floor exactly at corner A, confirm it, then tap corner B.",style=MaterialTheme.typography.bodySmall)
                                PointChoice("Saved corner A",vm.alignmentA,doc) { vm.alignmentA=it }
                                PointChoice("Saved corner B",vm.alignmentB,doc) { vm.alignmentB=it }
                            }
                            if(camera!=null) Text("Move slowly in good light. Tap the floor, confirm it, then tap a floor point ahead. Then tap real room corners. If a tap finds no surface, nothing is saved.",style=MaterialTheme.typography.bodySmall)
                        }
                        DETAIL_EDIT -> if(camera!=null) Text("Pause the camera to edit.") else {
                            Text("Room outlines",style=MaterialTheme.typography.titleMedium)
                            Choice("Room to edit",vm.currentRoomId,doc.rooms.map { it.id to it.name },vm::chooseRoom)
                            ActionRow {
                                TextButton(onClick=vm::unconfirmRoom,enabled=vm.room!=null) { Text("Reopen room") }
                                TextButton(onClick={deleteRoom=true},enabled=vm.room!=null) {Text("Delete room…")}
                            }
                            vm.room?.corners?.forEachIndexed { i,p -> key(p.id) {
                                PointEditor("Corner ${i+1}",p.p,selected=vm.selectedPointId==p.id,onSelect={vm.selectedPointId=p.id; vm.selectedWallId=null},
                                    onApply={vm.movePoint(p.id,it)},onDelete={vm.deletePoint(p.id)})
                            } }
                            val suggested=doc.walls.count { it.status=="CANDIDATE" }
                            Text("Walls",style=MaterialTheme.typography.titleMedium)
                            Text(if(suggested==0) "Room outlines already make walls. Walls the camera spots appear here as suggestions to confirm or reject."
                                else "$suggested suggested by the camera — confirm real walls, reject furniture, mirrors and glass.",style=MaterialTheme.typography.bodySmall)
                            Choice("Wall",vm.selectedWallId,doc.allWalls().map { it.id to "${wallKind(it.source)} · ${it.id.take(6)}" }+
                                doc.walls.filter { it.status=="CANDIDATE" }.map { it.id to "Suggested · ${it.id.take(6)}" }) {vm.selectedWallId=it; vm.selectedPointId=null}
                            doc.walls.firstOrNull { it.id==vm.selectedWallId }?.let { w -> key(w.id,w.a,w.b) {
                                Text("${wallKind(w.source)} · seen ${w.observations}× · confidence %.0f%% · height %.2f m".format(w.confidence*100,w.height),style=MaterialTheme.typography.bodySmall)
                                EndpointEditor(w.a,w.b,onApply={a,b->vm.editWall(w.id,a,b)})
                                ActionRow {
                                    Button(onClick={vm.wallDecision(w.id,true)}) { Text("Confirm wall") }
                                    TextButton(onClick={vm.wallDecision(w.id,false)}) { Text("Reject / remove") }
                                    TextButton(onClick={vm.splitWall(w.id)}) { Text("Split in half") }
                                }
                                var other by remember { mutableStateOf<String?>(null) }
                                Choice("Join with",other,doc.walls.filter { it.id!=w.id && it.status!="REJECTED" }.map { it.id to it.id.take(8) }) {other=it}
                                OutlinedButton(onClick={other?.let {vm.joinWalls(w.id,it)}},enabled=other!=null) {Text("Join walls in a straight line")}
                            } }
                            if(vm.selectedWallId?.contains(':')==true && doc.walls.none {it.id==vm.selectedWallId}) Text("This wall is part of a room outline: move its room corners above. Use Doorway to put an opening in it.",style=MaterialTheme.typography.bodySmall)
                            OutlinedButton(onClick=vm::cleanupWalls) {Text("Tidy up where walls cross")}
                            Text("Snaps nearby wall ends together and splits walls where they cross. Doorways are kept. Undo restores the previous version.",style=MaterialTheme.typography.bodySmall)
                            Text("Doorways",style=MaterialTheme.typography.titleMedium)
                            if(doc.doors.isEmpty()) Text("No doorways yet — use the Doorway tool while marking.",style=MaterialTheme.typography.bodySmall)
                            doc.doors.forEach { door->key(door.id) { DoorEditor(door,doc,vm) } }
                            if(doc.segments.any { it.interrupted && !it.reviewedAfterLoss }) OutlinedButton(onClick=vm::reviewInterruptedSections) {Text("I've checked the rooms still line up")}
                            AdvancedSection(title="Move, turn or resize the map",subtitle="For lining up parts mapped in different camera sessions") {
                                Text("Pause the camera first. Moving corners reopens their room; finish it again after checking its doorways.",style=MaterialTheme.typography.bodySmall)
                                var section by remember { mutableStateOf<String?>(null) }
                                Choice("Camera session",section,doc.segments.map { it.id to "${sessionKind(it.source)} · ${it.id.take(6)}${if(it.interrupted) " · stopped" else ""}" }) { section=it }
                                var dx by remember {mutableStateOf("0")}; var dy by remember {mutableStateOf("0")}; var angle by remember {mutableStateOf("0")}; var scale by remember {mutableStateOf("1")}
                                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {NumberField("Move right (m)",dx,{dx=it},Modifier.weight(1f)); NumberField("Move up (m)",dy,{dy=it},Modifier.weight(1f))}
                                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {NumberField("Turn (° anticlockwise)",angle,{angle=it},Modifier.weight(1f)); NumberField("Resize (×)",scale,{scale=it},Modifier.weight(1f))}
                                OutlinedButton(onClick={attempt {vm.transformSection(requireNotNull(section) { "Pick a camera session first" },Alignment(scale.toDouble(),Math.toRadians(angle.toDouble()),dx.toDouble(),dy.toDouble()))}},enabled=section!=null) {Text("Apply to this camera session")}
                                OutlinedButton(onClick={attempt {vm.rotate(angle.toDouble())}}) {Text("Turn the whole map")}
                            }
                        }
                        else -> {
                            Text("Tape measurements",style=MaterialTheme.typography.titleMedium)
                            var a by remember {mutableStateOf<String?>(null)}; var b by remember {mutableStateOf<String?>(null)}
                            var length by remember {mutableStateOf("")}; var purpose by remember {mutableStateOf("VALIDATION")}
                            PointChoice("From corner",a,doc) {a=it}; PointChoice("To corner",b,doc) {b=it}
                            NumberField("Length you measured (m)",length,{length=it},Modifier.fillMaxWidth())
                            Choice("Use it to",purpose,listOf("VALIDATION" to "Check the size","CALIBRATION" to "Fix the size")) {purpose=it}
                            Button(onClick={attempt {vm.measurement(requireNotNull(a),requireNotNull(b),length.toDouble(),purpose)}},enabled=a!=null && b!=null && camera==null) {Text("Save measurement")}
                            Text("Check with a different wall than the one used to fix the size. Fixing the size does not prove the map is accurate.",style=MaterialTheme.typography.bodySmall)
                            doc.measurements.forEach { m ->
                                val points=doc.points().associateBy {it.id}; val estimate=points[m.pointA]?.p?.let {p->points[m.pointB]?.p?.let(p::distance)}
                                Text("${if(m.purpose=="CALIBRATION") "Size fix" else "Check"} · ${cornerName(doc,m.pointA)} → ${cornerName(doc,m.pointB)} · measured %.2f m · map said %.2f m".format(m.measured,m.estimatedAtCapture),style=MaterialTheme.typography.bodySmall)
                                if(estimate!=null) Text("Map now %.2f m · off by %.2f m (%.1f%%) · %s".format(estimate,abs(estimate-m.measured),100*abs(estimate-m.measured)/m.measured,if(m.calibrationApplied) "size ×%.3f applied".format(m.scaleApplied) else "no size fix applied"),style=MaterialTheme.typography.labelSmall)
                                ActionRow { if(m.purpose=="CALIBRATION") TextButton(onClick={vm.applyCalibration(m.id)},enabled=!m.calibrationApplied && camera==null) {Text("Apply size fix")}
                                    TextButton(onClick={vm.removeMeasurement(m.id)},enabled=camera==null && !m.calibrationApplied) {Text("Remove measurement")} }
                            }
                            Text("Checks before saving",style=MaterialTheme.typography.titleMedium)
                            val issues=remember(doc.rooms,doc.doors,doc.walls,doc.segments,doc.measurements,doc.thresholds,doc.name) {ScanGeometry.issues(doc)}
                            if(issues.isEmpty()) Text("Everything checks out. Look over the suggested walking paths after saving.")
                            issues.forEach { Text("${if(it.blocking) "Fix" else "Note"}: ${it.message}",style=MaterialTheme.typography.bodySmall,color=if(it.blocking) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }
                            OutlinedTextField(doc.name,{vm.name(it)},label={Text("Floor name")},modifier=Modifier.fillMaxWidth(),singleLine=true)
                            Button(onClick=onPublish,enabled=camera==null && !vm.busy && issues.none {it.blocking} && doc.name.isNotBlank(),modifier=Modifier.fillMaxWidth()) {Text(if(vm.busy) "Saving…" else "Save floor plan")}
                            if(doc.publishedFloorId!=null) OutlinedButton(onClick=onExit) {Text("Open saved floor")}
                            doc.publicationIssues.forEach {Text(it,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error)}
                            Text("Saves a separate new project with the floor plan image, rooms, walls, doorways, destinations and suggested walking paths. Your existing maps and recordings are not changed. Finish any missing paths in Map Setup.",style=MaterialTheme.typography.bodySmall)
                            ActionRow {OutlinedButton(onClick=vm::save) {Text("Save draft now")}; TextButton(onClick=onJson) {Text("Export map data (JSON)")}; TextButton(onClick=onPng,enabled=doc.publishedFloorId!=null) {Text("Export image (PNG)")} }
                            Text(vm.saveStatus,style=MaterialTheme.typography.labelSmall)
                            AdvancedSection(title="Fine-tuning",subtitle="Detection and checking settings — the defaults suit most homes") {
                                var snap by remember {mutableStateOf(doc.thresholds.snapMetres.toString())}; var tolerance by remember {mutableStateOf((doc.thresholds.validationRelativeTolerance*100).toString())}
                                var inlier by remember {mutableStateOf(doc.thresholds.lineInlierMetres.toString())}; var minimumWall by remember {mutableStateOf(doc.thresholds.minimumWallMetres.toString())}
                                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {NumberField("Corner snap distance (m)",snap,{snap=it},Modifier.weight(1f)); NumberField("Allowed size error (%)",tolerance,{tolerance=it},Modifier.weight(1f))}
                                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {NumberField("Wall-detection tolerance (m)",inlier,{inlier=it},Modifier.weight(1f)); NumberField("Shortest detected wall (m)",minimumWall,{minimumWall=it},Modifier.weight(1f))}
                                Text("Wall-detection tolerance is how far depth points may stray from a straight line (RANSAC inlier distance).",style=MaterialTheme.typography.bodySmall)
                                TextButton(onClick={attempt {vm.thresholds(snap.toDouble(),tolerance.toDouble()/100,inlier.toDouble(),minimumWall.toDouble())}},enabled=camera==null) {Text("Apply settings")}
                            }
                            OutlinedButton(onClick={showFloorPlans=true},enabled=!vm.busy) {Text("Floor plans")}
                        }
                    }
                }
            }
        }
    }
}

private fun wallKind(source: String)=when {
    source=="OUTLINE" || source.contains("OUTLINE") -> "Room outline"
    source=="VERTICAL_PLANE" -> "Seen by camera"
    source.contains("DEPTH") -> "Seen by depth sensor"
    source=="MANUAL_CLEANUP" -> "Tidied up"
    source.startsWith("GUIDED") -> "Marked by you"
    source=="MANUAL_ADJUSTED" -> "Edited by you"
    else -> source.lowercase().replace('_',' ').replaceFirstChar { it.uppercase() }
}
private fun sessionKind(source: String)=when(source) { "ARCORE"->"Camera"; "MANUAL_MEASURED"->"Typed measurements"; else->source.lowercase().replace('_',' ') }

@Composable private fun DoorEditor(d: ScanDoor,doc: ScanDocument,vm: WalkMapperViewModel) {
    var a by remember(d.a) { mutableStateOf(d.a) }; var b by remember(d.b) {mutableStateOf(d.b)}
    var width by remember(d.a,d.b) {mutableStateOf("%.3f".format(java.util.Locale.US,d.a.distance(d.b)))}
    Text("Doorway ${d.id.take(6)} · %.2f m wide".format(d.a.distance(d.b)),style=MaterialTheme.typography.labelLarge)
    EndpointEditor(d.a,d.b) {x,y->a=x; b=y; vm.door(d.id,x,y,d.roomIds,d.walkable)}
    NumberField("Measured width (m)",width,{width=it},Modifier.fillMaxWidth())
    TextButton(onClick={runCatching {val w=width.toDouble(); require(w in .45..3.0); val length=a.distance(b); require(length>.01); val end=P2(a.x+(b.x-a.x)*w/length,a.y+(b.y-a.y)*w/length); vm.door(d.id,a,end,d.roomIds,d.walkable)}.onFailure {vm.message("Width must be 0.45–3 m and the doorway needs two different ends")}}) {Text("Set width from first end")}
    Text("Rooms this doorway connects:",style=MaterialTheme.typography.bodySmall)
    doc.rooms.forEach { r->Row {Checkbox(r.id in d.roomIds,{checked->vm.door(d.id,d.a,d.b,if(checked) (d.roomIds+r.id).distinct() else d.roomIds-r.id,d.walkable)}); Text(r.name,Modifier.padding(top=12.dp))} }
    Row {Switch(d.walkable,{vm.door(d.id,d.a,d.b,d.roomIds,it)}); Text("People can walk through",Modifier.padding(12.dp))}
    TextButton(onClick={vm.removeDoor(d.id)}) {Text("Delete doorway")}
}
@Composable private fun PointEditor(label: String,p: P2,selected: Boolean,onSelect: ()->Unit,onApply: (P2)->Unit,onDelete: ()->Unit) {
    var x by remember(p) {mutableStateOf(p.x.toString())}; var y by remember(p) {mutableStateOf(p.y.toString())}
    TextButton(onClick=onSelect) {Text(if(selected) "$label · selected" else label)}
    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {NumberField("X (m)",x,{x=it},Modifier.weight(1f)); NumberField("Y (m)",y,{y=it},Modifier.weight(1f))}
    ActionRow {TextButton(onClick={val a=x.toDoubleOrNull(); val b=y.toDoubleOrNull(); if(a!=null && b!=null) onApply(P2(a,b))}) {Text("Move corner")}; TextButton(onClick=onDelete) {Text("Delete corner")} }
}
@Composable private fun EndpointEditor(a: P2,b: P2,onApply: (P2,P2)->Unit) {
    var ax by remember(a) {mutableStateOf(a.x.toString())}; var ay by remember(a) {mutableStateOf(a.y.toString())}
    var bx by remember(b) {mutableStateOf(b.x.toString())}; var byy by remember(b) {mutableStateOf(b.y.toString())}
    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {NumberField("End 1 X (m)",ax,{ax=it},Modifier.weight(1f)); NumberField("End 1 Y (m)",ay,{ay=it},Modifier.weight(1f))}
    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {NumberField("End 2 X (m)",bx,{bx=it},Modifier.weight(1f)); NumberField("End 2 Y (m)",byy,{byy=it},Modifier.weight(1f))}
    TextButton(onClick={val values=listOf(ax,ay,bx,byy).map {it.toDoubleOrNull()}; if(values.all {it!=null}) onApply(P2(values[0]!!,values[1]!!),P2(values[2]!!,values[3]!!))}) {Text("Apply ends")}
}
@Composable internal fun Choice(label: String,selected: String?,items: List<Pair<String,String>>,onSelect: (String)->Unit) {
    var open by remember {mutableStateOf(false)}
    Box {OutlinedButton(onClick={open=true},enabled=items.isNotEmpty()) {Text("$label: ${items.firstOrNull {it.first==selected}?.second ?: "Choose"}")}
        DropdownMenu(open,{open=false}) {items.forEach {(id,title)->DropdownMenuItem(text={Text(title)},onClick={open=false; onSelect(id)})}} }
}
@Composable internal fun PointChoice(label: String,selected: String?,doc: ScanDocument,onSelect: (String)->Unit) =
    Choice(label,selected,doc.rooms.flatMap {r->r.corners.mapIndexed {i,p->p.id to "${r.name} corner ${i+1}"}} ,onSelect)
@Composable private fun NumberField(label: String,value: String,onChange: (String)->Unit,modifier: Modifier) {
    val keyboard=LocalSoftwareKeyboardController.current
    OutlinedTextField(value,onChange,label={Text(label)},modifier=modifier,singleLine=true,
        keyboardOptions=KeyboardOptions(imeAction=ImeAction.Done),keyboardActions=KeyboardActions(onDone={keyboard?.hide()}))
}
@Composable private fun ActionRow(content: @Composable RowScope.()->Unit) =
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp),content=content)
