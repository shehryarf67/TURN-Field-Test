package com.turn.fieldtest.mapper.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment as UiAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.text.font.FontWeight
import com.turn.fieldtest.mapper.WalkMapperViewModel
import com.turn.fieldtest.mapper.ar.ArScanView
import com.turn.fieldtest.mapper.core.*

/** Guided controls over the existing persisted scan document and ARCore capture service. */
@Composable
fun WalkMapperScreen(vm: WalkMapperViewModel, camera: ArScanView?, availability: String, arSupported: Boolean,
    onStartCamera: () -> Unit, onStopCamera: () -> Unit, onExit: () -> Unit,
    onPublish: () -> Unit, onJson: () -> Unit, onPng: () -> Unit, onCheckSupport: () -> Unit = {}) {
    var stage by rememberSaveable { mutableStateOf("Capture") }
    var details by rememberSaveable { mutableStateOf(false) }
    var roomDialog by remember { mutableStateOf(false) }
    var roomName by remember { mutableStateOf("") }
    var measured by rememberSaveable { mutableStateOf(false) }
    var tools by remember { mutableStateOf(false) }
    var outline by remember { mutableStateOf(false) }
    var leaving by remember { mutableStateOf(false) }
    var x by remember { mutableStateOf("") }; var y by remember { mutableStateOf("") }
    val doc = vm.doc
    LaunchedEffect(camera) { outline=false }
    fun attempt(action: () -> Unit) { runCatching(action).onFailure { vm.message(it.message ?: "Check entered values") } }
    if (details) {
        BackHandler { details = false }
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            TextButton(onClick = { details = false }) { Icon(Icons.Outlined.ArrowBack, null); Text("Back to guided capture") }
            Box(Modifier.weight(1f)) {
                MapperDetailsScreen(vm, null, availability, arSupported, onStartCamera = { details=false; onStartCamera() },
                    onStopCamera, onExit = { details=false }, onPublish, onJson, onPng, onCheckSupport, initialSection=stage)
            }
        }
        return
    }
    BackHandler { leaving = true }
    if (leaving) AlertDialog(onDismissRequest={leaving=false}, title={Text("Save and leave?")},
        text={Text("Your draft stays on this phone. Resume the camera using two saved physical corners.")},
        confirmButton={TextButton(onClick={onStopCamera(); onExit()}) {Text("Save and leave")}},
        dismissButton={TextButton(onClick={leaving=false}) {Text("Keep mapping")}})
    if (roomDialog) AlertDialog(onDismissRequest={roomDialog=false}, title={Text(if(vm.room==null) "Name your room" else "Add another room")},
        text={OutlinedTextField(roomName,{roomName=it},label={Text("Room name")},singleLine=true)},
        confirmButton={TextButton(onClick={attempt {vm.addRoom(roomName); roomName=""; roomDialog=false; stage="Capture"}}, enabled=roomName.isNotBlank() && !vm.busy) {Text("Create room")}},
        dismissButton={TextButton(onClick={roomDialog=false}) {Text("Cancel")}})
    val landscape = LocalConfiguration.current.screenWidthDp > LocalConfiguration.current.screenHeightDp
    Surface(Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=8.dp),verticalAlignment=UiAlignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Create a floor plan",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
                    Text(vm.room?.name ?: "Map a room with your phone",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick={onStopCamera(); details=true}) {Text("Advanced")}
                IconButton(onClick={leaving=true}) {Icon(Icons.Outlined.Close,"Close mapper")}
            }
            val steps = listOf("Capture", "Review", "Verify & save")
            Row(Modifier.fillMaxWidth().padding(horizontal=16.dp),horizontalArrangement=Arrangement.SpaceBetween) {
                steps.forEachIndexed { i, label -> FilterChip(selected=stage==label,
                    onClick={if(label!="Capture") onStopCamera(); stage=label},enabled=doc!=null,
                    label={Text("${i+1} · $label",style=MaterialTheme.typography.labelMedium)}) }
            }
            if(doc==null) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(vm.status,Modifier.padding(20.dp)); return@Column }
            val visual: @Composable (Modifier) -> Unit = { modifier ->
                Box(modifier.clip(MaterialTheme.shapes.extraLarge)) {
                    if(camera!=null) AndroidView(factory={camera},modifier=Modifier.fillMaxSize(),update={view ->
                        view.tool=vm.tool; view.overlay=doc.allWalls()+doc.walls.filter {it.status=="CANDIDATE"}
                        view.mapAlignment=doc.segments.firstOrNull {it.id==vm.activeSegment}?.alignment ?: Alignment()
                        view.thresholds=doc.thresholds
                    })
                    if(camera==null || outline) Surface(Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.surfaceContainerLow) {
                        if(doc.points().isEmpty()) Column(Modifier.padding(24.dp),verticalArrangement=Arrangement.Center,horizontalAlignment=UiAlignment.CenterHorizontally) {
                            Icon(Icons.Outlined.ViewInAr,null,Modifier.size(40.dp),tint=MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.height(12.dp)); Text("Your floor plan starts here",style=MaterialTheme.typography.titleMedium)
                            Text("Mark the room corners, review the outline, then check its measurements.",style=MaterialTheme.typography.bodyMedium)
                        } else ScanPreview(doc,vm.selectedPointId ?: vm.selectedWallId,false,Modifier.fillMaxSize(),
                            onPoint={vm.selectedPointId=it; vm.selectedWallId=null},onWall={vm.selectedWallId=it; vm.selectedPointId=null})
                    }
                    if(camera!=null && !outline) Icon(Icons.Outlined.Add,"Aim here",Modifier.align(UiAlignment.Center).size(32.dp),tint=androidx.compose.ui.graphics.Color.White)
                    if(camera!=null) FilledTonalButton(onClick={outline=!outline},modifier=Modifier.align(UiAlignment.TopStart).padding(10.dp)) {Text(if(outline) "Show camera" else "View outline")}
                }
            }
            val controls: @Composable (Modifier) -> Unit = { modifier ->
                Surface(modifier,shape=MaterialTheme.shapes.medium,color=MaterialTheme.colorScheme.surface) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    Text(if(camera!=null) vm.arStatus.text else availability,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    when(stage) {
                        "Capture" -> {
                            Text(if(vm.room==null) "Start with one room" else "${vm.room!!.corners.size} corners marked",style=MaterialTheme.typography.titleLarge)
                            if(camera==null) {
                                if(arSupported) {
                                    if(doc.points().isNotEmpty()) {
                                        Text("Resume at two saved corners",style=MaterialTheme.typography.titleMedium)
                                        Text("Choose corners you can find again, at least 0.5 m apart. Tap A as the floor origin, then B to align the camera.",style=MaterialTheme.typography.bodySmall)
                                        PointChoice("Corner A",vm.alignmentA,doc) {vm.alignmentA=it}
                                        PointChoice("Corner B",vm.alignmentB,doc) {vm.alignmentB=it}
                                    }
                                    Button(onClick={outline=false; onStartCamera()},enabled=vm.cameraMayStart && !vm.busy,shape=MaterialTheme.shapes.small,modifier=Modifier.fillMaxWidth().heightIn(min=52.dp)) {
                                        Icon(Icons.Outlined.PhotoCamera,null); Spacer(Modifier.width(8.dp)); Text("Start camera")
                                    }
                                } else {
                                    Text("AR is unavailable. You can still create this map using measured corners.",style=MaterialTheme.typography.bodyMedium)
                                    TextButton(onClick=onCheckSupport) {Text("Check AR support")}
                                }
                                OutlinedButton(onClick={measured=!measured},modifier=Modifier.fillMaxWidth()) {Text(if(measured) "Hide measured drawing" else "Use measured drawing")}
                            } else {
                                if(vm.floorDescription.isNotEmpty()) {
                                    Text("Is this the floor?",style=MaterialTheme.typography.titleMedium)
                                    Text(vm.floorDescription,style=MaterialTheme.typography.bodySmall)
                                    Button(onClick={camera.confirmFloor()},modifier=Modifier.fillMaxWidth()) {Text("Confirm floor")}
                                    TextButton(onClick={camera.rejectFloor()}) {Text("Choose again")}
                                } else {
                                    val aligned=doc.segments.any {it.id==vm.activeSegment && it.aligned && !it.interrupted}
                                    Text(if(aligned) "Aim at each corner where the wall meets the floor. Mark corners in order around the room."
                                        else "Tap the physical floor and confirm it. Then tap a point ahead to set map direction.",style=MaterialTheme.typography.bodyMedium)
                                    Button(onClick={outline=false; camera.markAtCentre()},enabled=aligned && vm.arStatus.tracking && vm.room!=null && (vm.tool!="CORNER" || vm.room?.confirmed==false) && !outline,
                                        shape=MaterialTheme.shapes.small,modifier=Modifier.fillMaxWidth().heightIn(min=52.dp)) {Text(if(vm.tool=="CORNER") "Mark corner" else if(vm.pendingEndpoint==null) "Mark first endpoint" else "Mark second endpoint")}
                                }
                                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(onClick=vm::undo,enabled=!vm.busy,modifier=Modifier.weight(1f)) {Text("Undo")}
                                    OutlinedButton(onClick={onStopCamera(); outline=false; stage="Review"},modifier=Modifier.weight(1f)) {Text("Pause & review")}
                                }
                            }
                            if(measured && camera==null) {
                                if(vm.room==null) Button(onClick={roomDialog=true}) {Text("Name room")}
                                Text("Enter measured metre coordinates in the same map frame.",style=MaterialTheme.typography.bodySmall)
                                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                    OutlinedTextField(x,{x=it},label={Text("X (m)")},singleLine=true,modifier=Modifier.weight(1f))
                                    OutlinedTextField(y,{y=it},label={Text("Y (m)")},singleLine=true,modifier=Modifier.weight(1f))
                                }
                                Button(onClick={attempt {vm.measuredPoint(x.toDouble(),y.toDouble())}},enabled=vm.room!=null && !vm.busy,modifier=Modifier.fillMaxWidth()) {Text("Add measured corner")}
                            }
                            Row(verticalAlignment=UiAlignment.CenterVertically) {
                                Box { OutlinedButton(onClick={tools=true}) {Text(if(vm.tool=="CORNER") "Corners ▾" else "More tools ▾")}
                                    DropdownMenu(tools,{tools=false}) {
                                        listOf("CORNER" to "Room corners","WALL" to "Wall endpoints","DOOR" to "Door endpoints","ENTRANCE" to "Entrance corner","BOUNDARY" to "Boundary corner").forEach { (id,label) ->
                                            DropdownMenuItem(text={Text(label)},onClick={vm.chooseTool(id); tools=false})
                                        }
                                    }
                                }
                                TextButton(onClick={roomDialog=true}) {Text(if(vm.room==null) "Name room" else "Add room")}
                            }
                            if(vm.tool=="DOOR") Text("Select a wall in View outline first. Mark both opening endpoints, then link its rooms under Advanced → Review.",style=MaterialTheme.typography.bodySmall)
                            if(doc.rooms.size>1) Choice("Room",vm.currentRoomId,doc.rooms.map {it.id to it.name},vm::chooseRoom)
                            if(camera==null && (vm.room?.corners?.size ?: 0)>=3) Button(onClick={stage="Review"},modifier=Modifier.fillMaxWidth()) {Text("Review outline")}
                        }
                        "Review" -> {
                            Text("Check your room outline",style=MaterialTheme.typography.titleLarge)
                            if(doc.rooms.size>1) Choice("Room",vm.currentRoomId,doc.rooms.map {it.id to it.name},vm::chooseRoom)
                            Text("Check that the corners follow the real room perimeter and the outline does not cross itself.",style=MaterialTheme.typography.bodyMedium)
                            if(doc.segments.any {it.interrupted && !it.reviewedAfterLoss}) {
                                Text("A camera section was paused or lost tracking. Check its saved corners and dimensions before continuing.",style=MaterialTheme.typography.bodySmall)
                                OutlinedButton(onClick=vm::reviewInterruptedSections,modifier=Modifier.fillMaxWidth()) {Text("I checked the interrupted sections")}
                            }
                            if(vm.room?.confirmed==true) {
                                Text("Outline confirmed",color=MaterialTheme.colorScheme.primary)
                                Button(onClick={stage="Verify & save"},shape=MaterialTheme.shapes.small,modifier=Modifier.fillMaxWidth()) {Text("Check measurements")}
                            } else Button(onClick={vm.finishRoom()},enabled=(vm.room?.corners?.size ?: 0)>=3 && !vm.busy,shape=MaterialTheme.shapes.small,modifier=Modifier.fillMaxWidth()) {Text("Confirm outline")}
                            OutlinedButton(onClick={details=true},modifier=Modifier.fillMaxWidth()) {Text("Edit corners, walls & doors")}
                            TextButton(onClick={stage="Capture"}) {Text("Back to capture")}
                        }
                        else -> GuidedVerification(vm,onPublish,onExit,onJson,onPng,onAdvanced={details=true})
                    }
                    Text(vm.status,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.primary)
                    Text(vm.saveStatus,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
                }
            }
            if(landscape) Row(Modifier.weight(1f).padding(8.dp)) {
                visual(Modifier.weight(1f).fillMaxHeight()); controls(Modifier.width(330.dp).fillMaxHeight())
            } else Column(Modifier.weight(1f)) {
                visual(Modifier.fillMaxWidth().weight(1f).padding(horizontal=12.dp)); controls(Modifier.fillMaxWidth().weight(1.1f))
            }
        }
    }
}

@Composable
private fun GuidedVerification(vm: WalkMapperViewModel,onPublish: ()->Unit,onExit: ()->Unit,onJson: ()->Unit,onPng: ()->Unit,onAdvanced: ()->Unit) {
    val doc=vm.doc ?: return
    var a by remember {mutableStateOf<String?>(null)}; var b by remember {mutableStateOf<String?>(null)}
    var length by remember {mutableStateOf("")}; var calibration by remember {mutableStateOf(false)}
    Text("Measure, then save",style=MaterialTheme.typography.titleLarge)
    Text("Measure between two captured corners with a tape. Use a separate pair to check the result after any scale correction.",style=MaterialTheme.typography.bodyMedium)
    PointChoice("From corner",a,doc) {a=it}; PointChoice("To corner",b,doc) {b=it}
    OutlinedTextField(length,{length=it},label={Text("Measured distance (m)")},singleLine=true,modifier=Modifier.fillMaxWidth())
    Row(verticalAlignment=UiAlignment.CenterVertically) {Checkbox(calibration,{calibration=it}); Text("Use to correct map scale")}
    Button(onClick={runCatching {vm.measurement(requireNotNull(a),requireNotNull(b),length.toDouble(),if(calibration) "CALIBRATION" else "VALIDATION")}.onFailure {vm.message(it.message ?: "Check measured length")}},
        enabled=a!=null && b!=null && a!=b && length.toDoubleOrNull()?.let {it.isFinite() && it>0}==true && !vm.busy,modifier=Modifier.fillMaxWidth()) {Text("Record measurement")}
    doc.measurements.forEach {m ->
        Text("${if(m.purpose=="CALIBRATION") "Scale reference" else "Independent check"} · %.2f m".format(m.measured),style=MaterialTheme.typography.bodyMedium)
        if(m.purpose=="CALIBRATION" && !m.calibrationApplied) OutlinedButton(onClick={vm.applyCalibration(m.id)}) {Text("Apply scale correction")}
    }
    val issues=remember(doc) {ScanGeometry.issues(doc)}
    issues.forEach {Text(it.message,style=MaterialTheme.typography.bodySmall,color=if(it.blocking) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)}
    OutlinedTextField(doc.name,vm::name,label={Text("Floor name")},singleLine=true,modifier=Modifier.fillMaxWidth())
    Button(onClick=onPublish,enabled=!vm.busy && doc.name.isNotBlank() && issues.none {it.blocking},shape=MaterialTheme.shapes.small,modifier=Modifier.fillMaxWidth().heightIn(min=52.dp)) {Text(if(vm.busy) "Saving…" else "Save floor plan")}
    doc.publicationIssues.forEach {Text(it,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error)}
    if(doc.publishedFloorId!=null) Button(onClick=onExit,modifier=Modifier.fillMaxWidth()) {Text("Open saved floor")}
    TextButton(onClick=onAdvanced) {Text("Edit checks & advanced settings")}
    Row {TextButton(onClick=onJson) {Text("Export JSON")}; if(doc.publishedFloorId!=null) TextButton(onClick=onPng) {Text("Export image")}}
}
