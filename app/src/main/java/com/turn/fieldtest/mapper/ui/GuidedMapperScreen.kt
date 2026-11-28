package com.turn.fieldtest.mapper.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.turn.fieldtest.mapper.WalkMapperViewModel
import com.turn.fieldtest.mapper.ar.ArScanView
import com.turn.fieldtest.mapper.ar.Reticle
import com.turn.fieldtest.mapper.core.*
import com.turn.fieldtest.ui.components.AdvancedSection
import com.turn.fieldtest.ui.components.HelpTip
import com.turn.fieldtest.ui.components.StepHeader
import kotlin.math.abs
import kotlin.math.max

/** The wizard's pages. Capture covers steps 1–3 (chosen from the camera state); the others are one step each. */
internal enum class MapperStage { CAPTURE, REVIEW, MEASURE, SAVE }
internal const val MAPPER_STEPS = 6

/** Plain names for the capture tools, shared by the wizard and the Advanced editor. */
internal val MapperTools = listOf(
    "CORNER" to "Corner",
    "WALL_CORNER" to "Corner behind furniture",
    "DOOR" to "Doorway",
    "ABOVE_CORNER" to "Above corner",
    "WALL" to "Wall (two ends)",
    "ENTRANCE" to "Entrance corner",
    "BOUNDARY" to "Open-side corner",
)

/** Readable name of a saved corner, matching the numbers drawn on the map: "Kitchen corner 2". */
internal fun cornerName(doc: ScanDocument, id: String?): String =
    doc.rooms.firstNotNullOfOrNull { r -> r.corners.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.let { "${r.name} corner ${it + 1}" } } ?: "a saved corner"

private const val FLOOR_HELP = "Why the floor? The camera sees the room in 3D. To draw a flat floor plan, TURN needs to know where the floor is: " +
    "every corner you tap later is dropped straight down onto it, so distances are measured flat along the ground, like a tape measure. " +
    "Pick plain, well-lit floor — not a table, bed or sofa. No photos or video are kept; only the points you tap are saved, on this phone."
internal const val TRACKING_LOST_HINT = "Tracking was lost, so this camera session can't add more points. Tap Pause camera, " +
    "then Continue with camera to line up with your map again — everything you've marked is saved."
private const val ALIGN_WHY = "Tapping two corners you have already marked lets the camera line up with your saved map, so new corners land in the right place."

/** Step-by-step mapper over the persisted scan document and the ARCore capture view. */
@Composable
fun WalkMapperScreen(vm: WalkMapperViewModel, camera: ArScanView?, availability: String, arSupported: Boolean,
    onStartCamera: () -> Unit, onStopCamera: () -> Unit, onExit: () -> Unit,
    onPublish: () -> Unit, onJson: () -> Unit, onPng: () -> Unit, onCheckSupport: () -> Unit = {}) {
    var stage by rememberSaveable { mutableStateOf(MapperStage.CAPTURE) }
    var details by rememberSaveable { mutableStateOf(false) }
    var roomDialog by remember { mutableStateOf<String?>(null) } // "new" or "rename"
    var measured by rememberSaveable { mutableStateOf(false) }
    var outline by remember { mutableStateOf(false) }
    var leaving by remember { mutableStateOf(false) }
    var showFloorPlans by rememberSaveable { mutableStateOf(false) }
    var wizardDraftId by rememberSaveable { mutableStateOf<String?>(null) }
    var wizardDraftRevision by rememberSaveable { mutableIntStateOf(vm.draftRevision) }
    val doc = vm.doc
    LaunchedEffect(camera) { outline = false }
    LaunchedEffect(doc?.id,vm.draftRevision) {
        val id = doc?.id ?: return@LaunchedEffect
        if (wizardDraftId != null && (wizardDraftId != id || wizardDraftRevision != vm.draftRevision)) {
            stage = MapperStage.CAPTURE
            measured = false
            outline = false
            details = false
            roomDialog = null
        }
        wizardDraftId = id
        wizardDraftRevision = vm.draftRevision
    }
    if (details) {
        BackHandler { details = false }
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            TextButton(onClick = { details = false }) { Icon(Icons.Outlined.ArrowBack, null); Text("Back to step-by-step") }
            Box(Modifier.weight(1f)) {
                MapperDetailsScreen(vm, null, availability, arSupported, onStartCamera = { details = false; onStartCamera() },
                    onStopCamera, onExit = { details = false }, onPublish, onJson, onPng, onCheckSupport,
                    initialSection = when (stage) { MapperStage.CAPTURE -> DETAIL_MARK; MapperStage.REVIEW -> DETAIL_EDIT; else -> DETAIL_SAVE })
            }
        }
        return
    }
    BackHandler { leaving = true }
    if (leaving) AlertDialog(onDismissRequest = { leaving = false }, title = { Text("Leave the mapper?") },
        text = { Text("Everything you've marked is saved on this phone. Next time, you'll line the camera up with two corners you've already marked, then carry on.") },
        confirmButton = { TextButton(onClick = { onStopCamera(); onExit() }) { Text("Save and leave") } },
        dismissButton = { TextButton(onClick = { leaving = false }) { Text("Keep mapping") } })
    roomDialog?.let { mode -> RoomNameDialog(vm, mode) { roomDialog = null } }
    if (showFloorPlans) FloorPlanDrafts(vm, onStopCamera) { showFloorPlans = false }
    fun goTo(next: MapperStage) { if (next != MapperStage.CAPTURE) onStopCamera(); outline = false; stage = next }
    val landscape = LocalConfiguration.current.screenWidthDp > LocalConfiguration.current.screenHeightDp
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp), verticalAlignment = UiAlignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Map a floor", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    val plan = doc?.name?.ifBlank { "Mapped floor" } ?: "Opening floor plan…"
                    Text(listOfNotNull(plan, vm.room?.name).joinToString(" · "), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { showFloorPlans = true }, enabled = doc != null && !vm.busy) { Text("Floor plans") }
                TextButton(onClick = { onStopCamera(); details = true }, enabled = doc != null) { Text("Advanced") }
                IconButton(onClick = { leaving = true }) { Icon(Icons.Outlined.Close, "Close mapper") }
            }
            if (doc == null) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(vm.status, Modifier.padding(20.dp)); return@Column }
            val resuming = doc.points().isNotEmpty()
            // Once the camera session is lined up with the map it stays on step 3, even if tracking is later lost;
            // losing tracking only disables marking (see CornersStep), it never sends the user back to step 2.
            val started = camera != null && doc.segments.any { it.id == vm.activeSegment }
            val aligned = started && doc.segments.any { it.id == vm.activeSegment && it.aligned && !it.interrupted }
            val step = wizardStep(stage, cameraOn = camera != null, sessionStarted = started, originSet = vm.arStatus.originSet,
                floorPending = vm.floorDescription.isNotEmpty(), hasPoints = resuming, measured = measured)
            LinearProgressIndicator(progress = { step / MAPPER_STEPS.toFloat() }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp))
            val visual: @Composable (Modifier) -> Unit = { modifier ->
                Box(modifier.clip(MaterialTheme.shapes.extraLarge)) {
                    if (camera != null) AndroidView(factory = { camera }, modifier = Modifier.fillMaxSize(), update = { view ->
                        view.tool = vm.tool; view.overlay = doc.allWalls()
                        view.mapAlignment = doc.segments.firstOrNull { it.id == vm.activeSegment }?.alignment ?: Alignment()
                        view.thresholds = doc.thresholds
                    })
                    if (camera == null || outline) Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                        if (doc.points().isEmpty() && vm.hiddenDraft.isEmpty) Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = UiAlignment.CenterHorizontally) {
                            Icon(Icons.Outlined.ViewInAr, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.height(12.dp)); Text("Your floor plan will appear here", style = MaterialTheme.typography.titleMedium)
                            Text("Each corner you mark is added to the map as you go.", style = MaterialTheme.typography.bodyMedium)
                        } else ScanPreview(doc, vm.selectedPointId ?: vm.selectedWallId, false, Modifier.fillMaxSize(),
                            onPoint = { vm.selectedPointId = it; vm.selectedWallId = null }, onWall = { vm.selectedWallId = it; vm.selectedPointId = null },
                            wallSamples = vm.hiddenDraft)
                    }
                    if (camera != null && !outline) {
                        val (aimLabel, aimColor) = when (vm.arStatus.reticle) {
                            Reticle.VALID -> "Aim here: surface ready" to Color(0xFF6EE7A1)
                            Reticle.APPROXIMATE -> "Aim here: approximate or elevated surface" to Color(0xFFF5A524)
                            Reticle.SEARCHING -> "Aim here: looking for a surface" to Color.White
                        }
                        Icon(Icons.Outlined.Add, aimLabel, Modifier.align(UiAlignment.Center).size(32.dp), tint = aimColor)
                        CoachingBanner(vm.arNotice ?: vm.arStatus.text, vm.arNotice != null, Modifier.align(UiAlignment.TopCenter).padding(10.dp))
                        if (doc.points().isNotEmpty() || !vm.hiddenDraft.isEmpty) Box(Modifier.align(UiAlignment.BottomEnd).padding(10.dp).size(120.dp).clip(MaterialTheme.shapes.medium)) {
                            ScanPreview(doc, vm.selectedPointId ?: vm.selectedWallId, false, Modifier.fillMaxSize(), {}, {}, showFit = false, wallSamples = vm.hiddenDraft)
                            Box(Modifier.matchParentSize().clickable(onClickLabel = "Show the map full size") { outline = true })
                        }
                    }
                    if (camera != null) FilledTonalButton(onClick = { outline = !outline }, modifier = Modifier.align(UiAlignment.BottomStart).padding(10.dp)) {
                        Text(if (outline) "Show camera" else "Show map")
                    }
                }
            }
            val controls: @Composable (Modifier) -> Unit = { modifier ->
                Surface(modifier, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface) {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        when (stage) {
                            MapperStage.CAPTURE -> CaptureStep(vm, doc, camera, step, aligned, arSupported, availability, measured,
                                CameraView(outline) { outline = false },
                                onMeasured = { measured = it }, onStartCamera = { outline = false; onStartCamera() }, onStopCamera = onStopCamera,
                                onCheckSupport = onCheckSupport, onRoomDialog = { roomDialog = it }, onFinishFloor = { goTo(MapperStage.REVIEW) })
                            MapperStage.REVIEW -> ReviewStep(vm, doc, onBack = { goTo(MapperStage.CAPTURE) }, onNext = { goTo(MapperStage.MEASURE) },
                                onEdit = { onStopCamera(); details = true })
                            MapperStage.MEASURE -> MeasureStep(vm, doc, onBack = { goTo(MapperStage.REVIEW) }, onNext = { goTo(MapperStage.SAVE) })
                            MapperStage.SAVE -> SaveStep(vm, doc, onPublish, onExit, onJson, onPng, onBack = { goTo(MapperStage.MEASURE) })
                        }
                        Text(vm.status, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                        Text(vm.saveStatus, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (landscape) Row(Modifier.weight(1f).padding(8.dp)) {
                visual(Modifier.weight(1f).fillMaxHeight()); controls(Modifier.width(340.dp).fillMaxHeight())
            } else Column(Modifier.weight(1f)) {
                visual(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 12.dp)); controls(Modifier.fillMaxWidth().weight(1.15f))
            }
        }
    }
}

@Composable
private fun CoachingBanner(text: String, notice: Boolean, modifier: Modifier) {
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium,
        color = if (notice) MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = .94f) else Color.Black.copy(alpha = .62f),
        contentColor = if (notice) MaterialTheme.colorScheme.onTertiaryContainer else Color.White) {
        Text(text, Modifier.padding(horizontal = 14.dp, vertical = 10.dp), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun RoomNameDialog(vm: WalkMapperViewModel, mode: String, onDone: () -> Unit) {
    var name by remember { mutableStateOf(if (mode == "rename") vm.room?.name.orEmpty() else "") }
    val newRoom = mode != "rename"
    AlertDialog(onDismissRequest = onDone,
        title = { Text(if (!newRoom) "Rename this room" else if (vm.room == null) "Name this room" else "Next room") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (newRoom && vm.room != null) Text("Walk into the next room and mark its corners. Mark the doorway between the rooms with the Doorway tool so they're connected.",
                style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(name, { name = it }, label = { Text("Room name") }, placeholder = { Text("e.g. Kitchen") }, singleLine = true)
        } },
        confirmButton = { TextButton(onClick = {
            runCatching { if (newRoom) vm.addRoom(name) else vm.renameRoom(name) }.onFailure { vm.message(it.message ?: "Type a room name") }; onDone()
        }, enabled = name.isNotBlank() && !vm.busy) { Text(if (newRoom) "Start room" else "Save") } },
        dismissButton = { TextButton(onClick = onDone) { Text("Cancel") } })
}

/**
 * Which wizard step to show. Capture is steps 1–3: with the camera on, a session that has been lined up with the map
 * (its segment exists) is step 3 even after tracking is lost; before that, step 2 once the starting point is confirmed.
 */
internal fun wizardStep(stage: MapperStage, cameraOn: Boolean, sessionStarted: Boolean, originSet: Boolean,
    floorPending: Boolean, hasPoints: Boolean, measured: Boolean): Int = when (stage) {
    MapperStage.CAPTURE -> when {
        !cameraOn -> if (hasPoints || measured) 3 else 1
        sessionStarted -> 3
        originSet && !floorPending -> 2
        else -> 1
    }
    MapperStage.REVIEW -> 4
    MapperStage.MEASURE -> 5
    MapperStage.SAVE -> 6
}

/**
 * Whether the full-size map currently covers the camera ([outline]). Marking is refused while it does, because
 * the user cannot see what the + is pointing at; [show] switches back to the camera picture before a mark.
 */
internal class CameraView(val outline: Boolean, val show: () -> Unit)

/** Every "mark at the +" button switches back to the camera picture first (the buttons are also disabled while the map covers it). */
private fun ArScanView.markVisible(view: CameraView) { view.show(); markAtCentre() }

/** Steps 1–3: starting point, map direction, then the corners of each room. */
@Composable
private fun CaptureStep(vm: WalkMapperViewModel, doc: ScanDocument, camera: ArScanView?, step: Int, aligned: Boolean,
    arSupported: Boolean, availability: String, measured: Boolean, view: CameraView, onMeasured: (Boolean) -> Unit,
    onStartCamera: () -> Unit, onStopCamera: () -> Unit, onCheckSupport: () -> Unit,
    onRoomDialog: (String) -> Unit, onFinishFloor: () -> Unit) {
    val resuming = doc.points().isNotEmpty()
    val bigButton = Modifier.fillMaxWidth().heightIn(min = 52.dp)
    when {
        // Step 1, camera off, new map.
        camera == null && step == 1 -> {
            if (arSupported) {
                StepHeader(1, MAPPER_STEPS, "Set the starting point",
                    "Tap Start camera, point your phone at a clear patch of floor near where you're standing, and tap it.",
                    "This spot becomes the corner of your map (0,0).", FLOOR_HELP)
                Button(onClick = onStartCamera, enabled = vm.cameraMayStart && !vm.busy, shape = MaterialTheme.shapes.small, modifier = bigButton) {
                    Icon(Icons.Outlined.PhotoCamera, null); Spacer(Modifier.width(8.dp)); Text("Start camera")
                }
                TextButton(onClick = { onMeasured(true) }) { Text("No camera? Enter measurements instead") }
            } else {
                StepHeader(1, MAPPER_STEPS, "Enter your measurements",
                    "This phone can't map with the camera, so you'll type in the corners you measure with a tape.",
                    availability)
                Button(onClick = { onMeasured(true) }, shape = MaterialTheme.shapes.small, modifier = bigButton) { Text("Enter measurements") }
                TextButton(onClick = onCheckSupport) { Text("Check camera support again") }
            }
        }
        // Step 1 with the camera on: tap the floor, then confirm it.
        camera != null && step == 1 -> {
            if (vm.floorDescription.isNotEmpty()) {
                StepHeader(1, MAPPER_STEPS, if (resuming) "Find saved corner A" else "Set the starting point",
                    "Is this the floor?", vm.floorDescription, FLOOR_HELP)
                Button(onClick = { camera.confirmFloor() }, enabled = vm.arStatus.tracking, shape = MaterialTheme.shapes.small, modifier = bigButton) { Text("Yes, this is the floor") }
                OutlinedButton(onClick = { camera.rejectFloor() }, modifier = Modifier.fillMaxWidth()) { Text("No, try again") }
            } else {
                if (resuming) StepHeader(1, MAPPER_STEPS, "Find saved corner A",
                    "Stand near ${cornerName(doc, vm.alignmentA)} and tap the floor exactly at that corner.", ALIGN_WHY, FLOOR_HELP)
                else StepHeader(1, MAPPER_STEPS, "Set the starting point",
                    "Point your phone at a clear patch of floor near where you're standing and tap it on the screen.",
                    "This spot becomes the corner of your map (0,0).", FLOOR_HELP)
                Button(onClick = { camera.markVisible(view) }, enabled = vm.arStatus.tracking && !view.outline, shape = MaterialTheme.shapes.small, modifier = bigButton) {
                    Text("Use the spot under the +")
                }
                // Step 1 has nothing earlier to go back to with the camera on, so Back is the camera pause.
                OutlinedButton(onClick = onStopCamera, modifier = Modifier.fillMaxWidth()) { Text("Pause camera") }
            }
        }
        // Step 2: direction ("up" on the map).
        camera != null && step == 2 -> {
            if (resuming) StepHeader(2, MAPPER_STEPS, "Find saved corner B",
                "Now tap the floor exactly at ${cornerName(doc, vm.alignmentB)}.", ALIGN_WHY,
                "Tap as close to the real corner as you can. If the two spots you tap are a different distance apart than the saved corners, TURN will ask you to try again.")
            else StepHeader(2, MAPPER_STEPS, "Set which way is 'up' on the map",
                "Tap another floor point a few steps in front of you, e.g. along a hallway.",
                "The map will be drawn with this direction pointing up. It doesn't need to be north.",
                "Any direction works — pick one that's easy, like straight down a hallway or along a long wall. The point must be on the same floor and at least half a metre (one step) away. This only turns the map on screen; it doesn't change any distances.")
            Button(onClick = { camera.markVisible(view) }, enabled = vm.arStatus.tracking && !view.outline, shape = MaterialTheme.shapes.small, modifier = bigButton) {
                Text("Use the spot under the +")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { camera.rejectFloor() }, modifier = Modifier.weight(1f)) { Text("Back") }
                OutlinedButton(onClick = onStopCamera, modifier = Modifier.weight(1f)) { Text("Pause camera") }
            }
        }
        // Step 3: corners of each room.
        else -> CornersStep(vm, doc, camera, aligned, arSupported, measured, view, onMeasured, onStartCamera, onStopCamera, onCheckSupport, onRoomDialog, onFinishFloor)
    }
}

@Composable
private fun CornersStep(vm: WalkMapperViewModel, doc: ScanDocument, camera: ArScanView?, aligned: Boolean, arSupported: Boolean,
    measured: Boolean, view: CameraView, onMeasured: (Boolean) -> Unit, onStartCamera: () -> Unit, onStopCamera: () -> Unit,
    onCheckSupport: () -> Unit, onRoomDialog: (String) -> Unit, onFinishFloor: () -> Unit) {
    val room = vm.room
    val bigButton = Modifier.fillMaxWidth().heightIn(min = 52.dp)
    StepHeader(3, MAPPER_STEPS, "Mark the corners of this room",
        if (measured && camera == null) "Type in where each corner is, measured with a tape, going round the room in order."
        else "Tap the corners in order from wherever you have a clear view. Use Above corner or Corner behind furniture when the floor corner is hidden.",
        "The corners make the room's outline on the map.",
        "Go round the room in one direction. If furniture covers the bottom of a corner, aim at the wall or ceiling straight above it — TURN drops the point down to the floor. " +
            "If a wardrobe or sofa hides the whole corner, choose 'Corner behind furniture' and tap along the two walls instead. " +
            "Walking into another room? Mark the doorway with 'Doorway', then tap 'Next room'. When every room on this floor is done, tap 'Finish floor'.")
    Row(verticalAlignment = UiAlignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(room?.name ?: "No room yet", style = MaterialTheme.typography.titleMedium)
            Text(room?.let { "${it.corners.size} ${if (it.corners.size == 1) "corner" else "corners"} marked${if (it.confirmed) " · finished" else ""}" }
                ?: "Name a room to start", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (room != null) TextButton(onClick = { onRoomDialog("rename") }) { Text("Rename") }
    }
    if (doc.rooms.size > 1) Choice("Room", vm.currentRoomId, doc.rooms.map { it.id to it.name }, vm::chooseRoom)
    if (room?.confirmed == true) {
        Text("This room is marked finished. Reopen it to add more corners.", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = vm::unconfirmRoom) { Text("Reopen room") }
    }
    ToolPicker(vm)
    if (camera != null) {
        val label = when (vm.tool) {
            "CORNER", "ENTRANCE", "BOUNDARY", "ABOVE_CORNER" -> "Mark corner"
            "WALL_CORNER" -> "Mark wall point"
            "DOOR" -> if (vm.pendingEndpoint == null) "Mark first side of doorway" else "Mark other side of doorway"
            else -> if (vm.pendingEndpoint == null) "Mark first end of wall" else "Mark other end of wall"
        }
        Button(onClick = { camera.markVisible(view) },
            enabled = aligned && vm.arStatus.tracking && !view.outline && room != null && (vm.tool !in HiddenCorners.CORNER_TOOLS || room.confirmed == false),
            shape = MaterialTheme.shapes.small, modifier = bigButton) { Text(label) }
        if (!aligned) Text(TRACKING_LOST_HINT, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        else if (view.outline) Text("Tap Show camera to mark points.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        else Text("Put the + on the spot and press the button, or tap the spot on the camera picture.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = vm::undo, enabled = !vm.busy, modifier = Modifier.weight(1f)) { Text("Undo") }
            OutlinedButton(onClick = { onRoomDialog("new") }, enabled = !vm.busy, modifier = Modifier.weight(1f)) { Text("Next room") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onStopCamera, modifier = Modifier.weight(1f)) { Text("Pause camera") }
            FilledTonalButton(onClick = onFinishFloor, modifier = Modifier.weight(1f)) { Text("Finish floor") }
        }
        return
    }
    if (measured || !arSupported) {
        MeasuredEntry(vm, onRoomDialog)
        if (arSupported) TextButton(onClick = { onMeasured(false) }) { Text("Use the camera instead") }
        else TextButton(onClick = onCheckSupport) { Text("Check camera support again") }
    } else {
        Text("Continue with the camera", style = MaterialTheme.typography.titleMedium)
        Text("The camera is paused. To keep marking, it needs to line up with your map again: pick two corners you've already marked that you can easily find, at least half a metre apart (further is better).",
            style = MaterialTheme.typography.bodyMedium)
        PointChoice("Saved corner A", vm.alignmentA, doc) { vm.alignmentA = it }
        PointChoice("Saved corner B", vm.alignmentB, doc) { vm.alignmentB = it }
        Button(onClick = onStartCamera, enabled = vm.cameraMayStart && !vm.busy, shape = MaterialTheme.shapes.small, modifier = bigButton) {
            Icon(Icons.Outlined.PhotoCamera, null); Spacer(Modifier.width(8.dp)); Text("Continue with camera")
        }
        TextButton(onClick = { onMeasured(true) }) { Text("Enter measurements instead") }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = vm::undo, enabled = !vm.busy, modifier = Modifier.weight(1f)) { Text("Undo") }
        OutlinedButton(onClick = { onRoomDialog("new") }, enabled = !vm.busy, modifier = Modifier.weight(1f)) { Text(if (room == null) "Name room" else "Next room") }
    }
    if (doc.points().isNotEmpty()) FilledTonalButton(onClick = onFinishFloor, modifier = Modifier.fillMaxWidth()) { Text("Finish floor") }
}

@Composable
private fun ToolPicker(vm: WalkMapperViewModel) {
    var more by remember { mutableStateOf(false) }
    Text("What are you marking?", style = MaterialTheme.typography.labelLarge)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = UiAlignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        MapperTools.take(3).forEach { (id, label) -> FilterChip(vm.tool == id, { vm.chooseTool(id) }, label = { Text(label) }) }
        Box {
            FilterChip(vm.tool in MapperTools.drop(3).map { it.first }, { more = true },
                label = { Text(MapperTools.drop(3).firstOrNull { it.first == vm.tool }?.second ?: "More…") })
            DropdownMenu(more, { more = false }) {
                MapperTools.drop(3).forEach { (id, label) -> DropdownMenuItem(text = { Text(label) }, onClick = { vm.chooseTool(id); more = false }) }
            }
        }
    }
    val hint = when (vm.tool) {
        "ABOVE_CORNER" -> "Aim directly above the floor corner at the vertical seam or the ceiling corner."
        "WALL_CORNER" -> null
        "DOOR" -> "Doorway: first tap Show map and tap the wall that has the doorway. Then go back to the camera and mark each side of the opening. " +
            if (vm.selectedWallId == null) "No wall chosen yet." else "Wall chosen."
        "WALL" -> "Wall: mark both ends of an inside wall that isn't part of a room outline."
        "ENTRANCE" -> "Entrance corner: a corner of the outline at the edge of an entrance. It's part of the room outline; doorways are still marked with Doorway."
        "BOUNDARY" -> "Open-side corner: a corner where the room has no wall (for example where it opens into another space). It's part of the room outline."
        else -> null
    }
    if (hint != null) Text(hint, style = MaterialTheme.typography.bodySmall)
    if (vm.tool == "WALL_CORNER") HiddenCornerControls(vm)
}


@Composable
private fun MeasuredEntry(vm: WalkMapperViewModel, onRoomDialog: (String) -> Unit) {
    var x by remember { mutableStateOf("") }; var y by remember { mutableStateOf("") }
    HelpTip("Pick one corner as the start (X 0, Y 0). For every other corner, measure how far it is to the right (X) and forward (Y) from there, in metres.",
        "Choose two walls at right angles that meet at your starting corner: X is measured along one, Y along the other. Enter corners in order around the room, " +
            "for example a 4 × 3 m room is (0,0), (4,0), (4,3), (0,3). Use the same starting corner for every room on this floor. " +
            "The values must come from real measurements of your home.")
    if (vm.room == null) Button(onClick = { onRoomDialog("new") }, modifier = Modifier.fillMaxWidth()) { Text("Name room") }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(x, { x = it }, label = { Text("X (m)") }, singleLine = true, modifier = Modifier.weight(1f))
        OutlinedTextField(y, { y = it }, label = { Text("Y (m)") }, singleLine = true, modifier = Modifier.weight(1f))
    }
    Button(onClick = { runCatching { vm.measuredPoint(x.toDouble(), y.toDouble()) }.onFailure { vm.message(it.message ?: "Enter both numbers in metres") } },
        enabled = vm.room != null && !vm.busy, modifier = Modifier.fillMaxWidth()) { Text("Add measured corner") }
}

/** Step 4: check each room's outline and mark it finished. */
@Composable
private fun ReviewStep(vm: WalkMapperViewModel, doc: ScanDocument, onBack: () -> Unit, onNext: () -> Unit, onEdit: () -> Unit) {
    StepHeader(4, MAPPER_STEPS, "Check the outline",
        "Look at the map: does each room's shape match the real room? If it does, tap Looks right.",
        "TURN uses these outlines to know where you can walk.",
        "Corners are numbered in the order you marked them. A room needs at least three corners and its edges must not cross. " +
            "If a corner is in the wrong place, tap 'Fix corners, walls and doorways' to move or delete it. " +
            "Two rooms joined by a doorway need that doorway ticked for both rooms (also under Fix…).")
    if (doc.rooms.size > 1) Choice("Room", vm.currentRoomId, doc.rooms.map { it.id to "${it.name}${if (it.confirmed) " ✓" else ""}" }, vm::chooseRoom)
    val room = vm.room
    if (room == null) Text("No rooms yet — go back and mark some corners.", style = MaterialTheme.typography.bodyMedium)
    else Text("${room.name}: ${room.corners.size} corners · ${if (room.confirmed) "finished ✓" else "not finished yet"}", style = MaterialTheme.typography.titleMedium)
    if (doc.segments.any { it.interrupted && !it.reviewedAfterLoss }) {
        Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.small) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("The camera was paused or lost track while you mapped (this always happens when the camera stops). Check that the rooms still line up with each other, then confirm.",
                    style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick = vm::reviewInterruptedSections, modifier = Modifier.fillMaxWidth()) { Text("Rooms line up") }
            }
        }
    }
    val bigButton = Modifier.fillMaxWidth().heightIn(min = 52.dp)
    val unfinished = doc.rooms.firstOrNull { !it.confirmed }
    when {
        room != null && !room.confirmed -> Button(onClick = { vm.finishRoom() }, enabled = room.corners.size >= 3 && !vm.busy,
            shape = MaterialTheme.shapes.small, modifier = bigButton) { Text("Looks right — finish ${room.name}") }
        unfinished != null -> Button(onClick = { vm.chooseRoom(unfinished.id) }, shape = MaterialTheme.shapes.small, modifier = bigButton) {
            Text("Next: check ${unfinished.name}") }
        else -> Button(onClick = onNext, enabled = doc.rooms.isNotEmpty(), shape = MaterialTheme.shapes.small, modifier = bigButton) { Text("Next: check the size") }
    }
    if (room?.confirmed == true) TextButton(onClick = vm::unconfirmRoom) { Text("Reopen ${room.name} to change it") }
    OutlinedButton(onClick = onEdit, modifier = Modifier.fillMaxWidth()) { Text("Fix corners, walls and doorways") }
    TextButton(onClick = onBack) { Text("Back to marking corners") }
}

/** Step 5: one tape measurement checks the scale. */
@Composable
private fun MeasureStep(vm: WalkMapperViewModel, doc: ScanDocument, onBack: () -> Unit, onNext: () -> Unit) {
    var a by remember { mutableStateOf<String?>(null) }; var b by remember { mutableStateOf<String?>(null) }
    var length by remember { mutableStateOf("") }; var calibration by remember { mutableStateOf(false) }
    StepHeader(5, MAPPER_STEPS, "Check the size with a tape measure",
        "Measure one wall with a tape and type its length — this checks the scale.",
        "The camera can be slightly off. One real measurement shows whether the map's distances are right.",
        "Pick the two corners at the ends of a wall you can measure easily — the numbers match the corner numbers on the map. " +
            "Measure along the floor, corner to corner, in metres (for example 3.42). TURN compares it with the map: if it's off by more than about 5 % " +
            "(at least 15 cm), measure again or fix the corners. Checking a second wall in the other direction makes the check stronger.")
    PointChoice("From corner", a, doc) { a = it }; PointChoice("To corner", b, doc) { b = it }
    OutlinedTextField(length, { length = it }, label = { Text("Length you measured (m)") }, placeholder = { Text("e.g. 3.42") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    Button(onClick = { runCatching { vm.measurement(requireNotNull(a), requireNotNull(b), length.toDouble(), if (calibration) "CALIBRATION" else "VALIDATION") }
        .onFailure { vm.message(it.message ?: "Check the length you typed") }; length = "" },
        enabled = a != null && b != null && a != b && length.toDoubleOrNull()?.let { it.isFinite() && it > 0 } == true && !vm.busy,
        shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
        Text(if (calibration) "Use this length to fix the size" else "Check this length")
    }
    val points = doc.points().associateBy { it.id }
    doc.measurements.forEach { m ->
        val now = points[m.pointA]?.p?.let { p -> points[m.pointB]?.p?.let(p::distance) }
        val pair = "${cornerName(doc, m.pointA)} → ${cornerName(doc, m.pointB)}"
        if (m.purpose == "CALIBRATION") {
            Text("$pair: used to fix the size (%.2f m)%s".format(m.measured, if (m.calibrationApplied) " · applied" else ""), style = MaterialTheme.typography.bodyMedium)
            if (!m.calibrationApplied) OutlinedButton(onClick = { vm.applyCalibration(m.id) }) { Text("Apply size fix") }
        } else if (now != null) {
            val error = abs(now - m.measured)
            val ok = error <= max(doc.thresholds.validationAbsoluteTolerance, m.measured * doc.thresholds.validationRelativeTolerance)
            Text("$pair: you measured %.2f m, the map says %.2f m (%.0f %% off) %s".format(m.measured, now, 100 * error / m.measured, if (ok) "✓ good" else "✗ too far off"),
                style = MaterialTheme.typography.bodyMedium, color = if (ok) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error)
        }
    }
    AdvancedSection(title = "Fix the map size", subtitle = "Only if the whole map is consistently too big or too small") {
        Row(verticalAlignment = UiAlignment.CenterVertically) {
            Checkbox(calibration, { calibration = it }); Text("Use the next length to correct the map size instead of checking it")
        }
        Text("After fixing the size, measure a different wall to check the result — the same wall can't do both.", style = MaterialTheme.typography.bodySmall)
    }
    Button(onClick = onNext, enabled = doc.measurements.any { it.purpose == "VALIDATION" }, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth()) {
        Text("Next: name and save")
    }
    TextButton(onClick = onBack) { Text("Back to the outline") }
}

/** Step 6: name the floor and save it as a TURN map. */
@Composable
private fun SaveStep(vm: WalkMapperViewModel, doc: ScanDocument, onPublish: () -> Unit, onExit: () -> Unit, onJson: () -> Unit, onPng: () -> Unit, onBack: () -> Unit) {
    StepHeader(6, MAPPER_STEPS, "Name and save the floor plan",
        "Give this floor a name (for example 'Ground floor') and tap Save floor plan.",
        "TURN saves it as a new map you can navigate on. Your existing maps are not changed.",
        "Saving creates a new project containing this floor: an image of the plan, the rooms, walls, doorways and suggested walking paths. " +
            "Some paths may need finishing by hand afterwards (Map Setup → Paths). You can come back to the mapper later to edit and save again; that makes another copy.")
    val issues = remember(doc) { ScanGeometry.issues(doc) }
    val blocking = issues.filter { it.blocking }
    if (blocking.isNotEmpty()) {
        Text("Before you can save:", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.error)
        blocking.forEach { Text("• ${it.message}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    }
    issues.filterNot { it.blocking }.forEach { Text("Note: ${it.message}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    OutlinedTextField(doc.name, vm::name, label = { Text("Floor name") }, placeholder = { Text("e.g. Ground floor") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    Button(onClick = onPublish, enabled = !vm.busy && doc.name.isNotBlank() && blocking.isEmpty(), shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(if (vm.busy) "Saving…" else "Save floor plan") }
    doc.publicationIssues.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    if (doc.publishedFloorId != null) Button(onClick = onExit, modifier = Modifier.fillMaxWidth()) { Text("Open saved floor") }
    AdvancedSection(title = "Export", subtitle = "Save a copy of the map data or image to your files") {
        OutlinedButton(onClick = onJson, modifier = Modifier.fillMaxWidth()) { Text("Export map data (JSON)") }
        if (doc.publishedFloorId != null) OutlinedButton(onClick = onPng, modifier = Modifier.fillMaxWidth()) { Text("Export image (PNG)") }
    }
    TextButton(onClick = onBack) { Text("Back to the tape check") }
}

/** "Corner behind furniture": progress of the two wall-point sets and the steps to place the corner where they meet. */
@Composable
internal fun HiddenCornerControls(vm: WalkMapperViewModel) {
    val second = vm.hiddenWallB
    Text(if (second == null) "Tap two spots on the first wall face, at least 30 cm apart sideways."
        else "Tap two spots on the other wall face, then place the corner where the walls meet.",
        style = MaterialTheme.typography.bodySmall)
    Text(if (second == null) "First wall: ${vm.hiddenWallA.size} points (need 2, spread out by at least 30 cm)"
        else "First wall: ${vm.hiddenWallA.size} points ✓ · Second wall: ${second.size} points (need 2)", style = MaterialTheme.typography.labelLarge)
    Text("The walls must meet at an angle of at least ${HiddenCorners.MIN_ANGLE_DEGREES.toInt()}°. Check the corner with a tape later.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    vm.hiddenCornerFeedback?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    val canMark = vm.room?.confirmed == false && !vm.busy
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (second == null) OutlinedButton(onClick = vm::nextHiddenWall, enabled = vm.hiddenWallA.size >= 2 && canMark) { Text("Next wall") }
        else Button(onClick = vm::placeHiddenCorner, enabled = second.size >= 2 && canMark) { Text("Place corner") }
        TextButton(onClick = vm::clearHiddenCorner, enabled = vm.hiddenWallA.isNotEmpty()) { Text("Start over") }
    }
}
