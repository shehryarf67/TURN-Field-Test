package com.turn.fieldtest.ui

import android.graphics.Bitmap
import androidx.compose.runtime.*
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.activity.compose.LocalActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.turn.fieldtest.TurnApplication
import com.turn.fieldtest.data.local.*
import com.turn.fieldtest.data.repository.*
import com.turn.fieldtest.mapper.WalkMapperViewModel
import com.turn.fieldtest.mapper.data.ScanRepository
import com.turn.fieldtest.mapper.ui.WalkMapperScreen
import com.turn.fieldtest.ui.model.*
import com.turn.fieldtest.ui.theme.TurnTheme
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Visual fixtures only: no scans, sensor events or position records are sent to the runtime. */
@RunWith(AndroidJUnit4::class)
class WayfinderConsistencyTest {
    @get:Rule val compose=createComposeRule()
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val app get()=instrumentation.targetContext.applicationContext as TurnApplication
    private val text by lazy {app.assets.open("mapper/C-F4.json").bufferedReader().use {it.readText()}}
    private val p by lazy {MapperPackage.parse(text)}

    private fun fixture(dark: Boolean, page: String)=TurnAppState().apply {
        mode=DataMode.REAL_DEVICE;realMapReady=true;activeProject=ProjectContext("visual-venue",p.floorId)
        clearMapDraft(force=true);venueName="University";floorName=p.floorId;darkTheme=dark
        floorWidthMetres=p.width.toFloat();floorHeightMetres=p.height.toFloat()
        draftWalkablePolygon.addAll(p.regions.first().vertices.map {Offset(it.x.toFloat(),it.y.toFloat())})
        p.regions.drop(1).forEachIndexed {i,polygon->otherWalkableRegions.add(WalkableRegionEntity("r-$i",p.floorId,"Walkable",polygon.vertices.joinToString(",","[","]") {"{\"x\":${it.x},\"y\":${it.y}}"},updatedAtEpochMillis=1))}
        val pois=p.places.associateBy {it.nodeId};val nodes=p.nodes.associateBy {it.id}
        destinations.addAll(p.places.map {poi->val n=nodes.getValue(poi.nodeId);PointOfInterestEntity("preview-${poi.id}",p.floorId,poi.name,poi.category,n.point.x,n.point.y)})
        navigationNodes.addAll(p.nodes.map {NavigationNodeEntity(it.id,p.floorId,it.point.x,it.point.y,pois[it.id]?.let {poi->"preview-${poi.id}"})})
        navigationEdges.addAll(p.edges.map {NavigationEdgeEntity(it.id,p.floorId,it.from,it.to,it.distanceMetres,bidirectional=it.bidirectional,accessible=it.accessible)})
        val saved=SavedFloorMap(VenueEntity("visual-venue","University",notes="TURN-Mapper visual\n$text",createdAtEpochMillis=1,updatedAtEpochMillis=1),
            FloorEntity(p.floorId,"visual-venue",p.floorId,4,p.width,p.height,createdAtEpochMillis=1,updatedAtEpochMillis=1),null,emptyList(),emptyList(),emptyList(),emptyList(),false,destinations=destinations.toList())
        destinations.addAll(saved.missingMapperRooms());destinationAreas.addAll(saved.copy(destinations=destinations.toList()).destinationAreas())
        p.anchors.forEachIndexed {i,a->referencePoints.add(MapPointUi("visual-rp-$i",floorName,Offset(a.x.toFloat(),a.y.toFloat()),"Stair landing ${i+1}"))}
        selectedSurveyReferencePointId=referencePoints.first().id
        projects=listOf(ProjectSummary("visual-venue",p.floorId,"University",p.floorId,referencePoints.size,0,0,0))
        markMapSaved();editorStatus="Saved floor map loaded";projectStatus="Opened C-F4"
        navigationPreviewGraph=com.turn.fieldtest.core.NavigationGraph(p.graph.floor,navigationNodes.map {it.core()},navigationEdges.map {it.core()})
        destination=when {
            page=="buildings"->TurnDestination.VENUES
            page.startsWith("map-")->TurnDestination.FLOOR_EDITOR
            page=="survey"->TurnDestination.SURVEY
            page=="diagnostics"->TurnDestination.RADIO_DIAGNOSTICS
            page=="evaluate"->TurnDestination.EVALUATE
            page=="export"->TurnDestination.DATA_EXPORT
            page=="settings"->TurnDestination.SETTINGS
            else->TurnDestination.LIVE_LOCATE
        }
        if(page.startsWith("map-")) mapSetupSection=page.substringAfter("map-")
        if(page=="tools") openTools()
        if(page=="fullscreen") navigationFullscreen=true
        if(page in listOf("route","direction","live")) {
            manualStartPoint=referencePoints.first().metres;selectedDestinationId=destinations.first {it.name.contains("408")}.id
        }
        if(page=="live") {
            // Rendering fixture only; these values never reach a positioning repository or sensor service.
            liveRunning=true;realLiveInitialized=true;realHeadingAligned=true;realHeadingSampleEpochMillis=System.currentTimeMillis()
            liveEstimateEpochMillis=System.currentTimeMillis();realLiveConfidence=.8;realLivePosition=manualStartPoint;realLiveUncertaintyMetres=1.5
            val target=navigationNodes.first {it.poiId==selectedDestinationId}
            val route=navigationPreviewGraph!!.route(com.turn.fieldtest.core.MetricPoint(manualStartPoint!!.x.toDouble(),manualStartPoint!!.y.toDouble()),target.id)!!
            routePoints=route.points.map {Offset(it.x.toFloat(),it.y.toFloat())};remainingDistanceMetres=route.distanceMetres
        }
    }

    @Test fun captureEveryPageInBothThemesAndReachPrimaryActions()=runBlocking {
        val dbName="wayfinder-visual-${UUID.randomUUID()}.db"
        val db=TurnDatabase.build(app,dbName);val store=ViewModelStore()
        try {
            db.venueDao().upsert(VenueEntity("visual-v","UI review",createdAtEpochMillis=1,updatedAtEpochMillis=1))
            db.floorPlanDao().upsertFloor(FloorEntity("visual-f","visual-v","UI review",0,20.0,20.0,createdAtEpochMillis=1,updatedAtEpochMillis=1))
            lateinit var vm: WalkMapperViewModel
            instrumentation.runOnMainSync {vm=WalkMapperViewModel(app,SavedStateHandle(mapOf("venueId" to "visual-v","floorId" to "visual-f")),ScanRepository(db,app));store.put("visual",vm)}
            compose.waitUntil(10_000) {vm.doc!=null}
            var state by mutableStateOf(fixture(false,"explore"))
            var page by mutableStateOf("explore")
            var activity: ComponentActivity?=null
            compose.setContent {key(page,state.darkTheme) {
                val host=LocalActivity.current as? ComponentActivity
                SideEffect {
                    activity=host
                    val bars=if(state.darkTheme) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                        else SystemBarStyle.light(android.graphics.Color.TRANSPARENT,android.graphics.Color.TRANSPARENT)
                    host?.enableEdgeToEdge(statusBarStyle=bars,navigationBarStyle=bars)
                }
                if(page.startsWith("ar")) TurnTheme(state.darkTheme) {WalkMapperScreen(vm,null,"Camera not started · UI review",true,{},{},{},{},{},{})}
                else TurnApp(state,TurnAppActions(onDestinationSelected={state.selectedDestinationId=it}))
            }}
            val layout=if(app.resources.configuration.screenWidthDp>app.resources.configuration.screenHeightDp) "landscape" else "portrait"
            val output=File(app.getExternalFilesDir(null),"wayfinder-audit/$layout").apply {mkdirs()}
            fun shell(command: String) {android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use {it.readBytes()}}
            val shared="/sdcard/Download/turn-wayfinder-audit/$layout"
            shell("mkdir -p $shared")
            val pages=listOf("explore","route","direction","live","buildings","tools","map-Scale","map-Layout","map-Paths","map-Points","survey","diagnostics","evaluate","export","settings","fullscreen","ar","ar-review","ar-verify")
            for(dark in listOf(false,true)) for(name in pages) {
                if(name=="ar" && vm.doc!!.rooms.isNotEmpty()) {
                    val previous=vm.doc!!.id;compose.runOnIdle {vm.newDraft()}
                    compose.waitUntil(10_000) {vm.doc!!.id!=previous && vm.doc!!.rooms.isEmpty()}
                }
                if(name=="ar-review") compose.runOnIdle {
                    vm.addRoom("Lab 1");vm.measuredPoint(0.0,0.0);vm.measuredPoint(4.0,0.0);vm.measuredPoint(4.0,3.0);vm.measuredPoint(0.0,3.0);vm.finishRoom()
                }
                compose.runOnIdle {page=name;state=fixture(dark,name)}
                compose.waitForIdle()
                if(name=="ar-review") {
                    compose.onNodeWithText("2 · Review").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) {it()}
                    compose.onNodeWithText("Check your room outline").assertExists()
                }
                if(name=="ar-verify") {
                    compose.onNodeWithText("3 · Verify & save").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) {it()}
                    compose.onNodeWithText("Measure, then save").assertExists()
                }
                if(name=="direction") {
                    compose.onNodeWithText("Start navigation").performClick()
                    compose.onNodeWithText("Which way are you facing?").assertExists()
                }
                if(name=="fullscreen") compose.onNodeWithText("Exit full screen").assertExists()
                val file=File(output,"${if(dark) "dark" else "light"}-$name.png")
                instrumentation.waitForIdleSync()
                val frame=java.util.concurrent.CountDownLatch(1)
                instrumentation.runOnMainSync {
                    val decor=android.view.inspector.WindowInspector.getGlobalWindowViews()
                        .lastOrNull {it.isShown && it.isAttachedToWindow && it.isHardwareAccelerated}
                        ?: checkNotNull(activity).window.decorView
                    decor.viewTreeObserver.registerFrameCommitCallback {frame.countDown()}
                    decor.invalidate()
                }
                check(frame.await(5,java.util.concurrent.TimeUnit.SECONDS)) {"No rendered frame for $name"}
                // Compose can be idle before Android presents the newly drawn window/dialog.
                // Wait for native accessibility/window events to settle before capturing SurfaceFlinger.
                instrumentation.uiAutomation.waitForIdle(200L,3_000L)
                val image=checkNotNull(instrumentation.uiAutomation.takeScreenshot()) {"Emulator screenshot unavailable"}
                file.outputStream().use {image.compress(Bitmap.CompressFormat.PNG,100,it)}
                // UTP may uninstall the test target at teardown; copy only this audit's images to shared storage.
                shell("cp ${file.absolutePath} $shared/${file.name}")
                when(name) {
                    "survey"->compose.onNodeWithText("Begin collection").performScrollTo().assertIsDisplayed()
                    "export"->compose.onNodeWithText("Save complete database JSON").performScrollTo().assertIsDisplayed()
                    "settings"->compose.onNodeWithText("Dark mode").performScrollTo().assertExists()
                    "ar"->compose.onNodeWithText("Start camera").performScrollTo().assertIsDisplayed()
                }
            }
        } finally {instrumentation.runOnMainSync {store.clear()};db.close();app.deleteDatabase(dbName)}
    }
}
