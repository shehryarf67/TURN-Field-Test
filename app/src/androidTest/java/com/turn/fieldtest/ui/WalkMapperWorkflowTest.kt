package com.turn.fieldtest.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.turn.fieldtest.TurnApplication
import com.turn.fieldtest.data.local.*
import com.turn.fieldtest.mapper.WalkMapperViewModel
import com.turn.fieldtest.mapper.data.ScanRepository
import com.turn.fieldtest.mapper.ui.WalkMapperScreen
import com.turn.fieldtest.mapper.ui.MapperDetailsScreen
import com.turn.fieldtest.ui.theme.TurnTheme
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class WalkMapperWorkflowTest {
    @get:Rule val compose=createComposeRule()
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val app get()=instrumentation.targetContext.applicationContext as TurnApplication
    private val name="turn-mapper-ui-${UUID.randomUUID()}.db"
    private var db: TurnDatabase?=null
    private val store=ViewModelStore()
    private var image: File?=null
    private fun waitForKeyboardLayout() {
        // IME/window resizing is driven by Android, outside Compose's test clock.
        instrumentation.waitForIdleSync()
        instrumentation.uiAutomation.waitForIdle(200L,5_000L)
        compose.waitForIdle()
    }
    @After fun clean() {instrumentation.runOnMainSync {store.clear()}; db?.close(); app.deleteDatabase(name); image?.delete()}
    @Test fun supportedPhoneCanRequestCameraBeforeCreatingRoom(): Unit=runBlocking {
        val database=TurnDatabase.build(app,name).also {db=it}
        lateinit var vm: WalkMapperViewModel
        instrumentation.runOnMainSync {
            vm=WalkMapperViewModel(app,SavedStateHandle(mapOf("venueId" to "v","floorId" to "f")),ScanRepository(database,app))
            store.put("mapper",vm)
        }
        compose.waitUntil(10_000) {vm.doc!=null}
        var requests=0
        compose.setContent {TurnTheme {WalkMapperScreen(vm,null,"AR supported · tap Start camera to begin",true,
            {requests++},{},{},{},{},{})}}
        compose.onNodeWithText("Start camera").assertIsEnabled().performClick()
        compose.runOnIdle {assertEquals(1,requests); assertNull(vm.room)}
    }
    @Test fun guidedMeasuredCaptureLeadsToReviewAndMeasuredChecks(): Unit=runBlocking {
        val database=TurnDatabase.build(app,name).also {db=it}
        lateinit var vm: WalkMapperViewModel
        instrumentation.runOnMainSync {
            vm=WalkMapperViewModel(app,SavedStateHandle(mapOf("venueId" to "v","floorId" to "f")),ScanRepository(database,app))
            store.put("mapper",vm)
        }
        compose.waitUntil(10_000) {vm.doc!=null}
        compose.setContent {TurnTheme {WalkMapperScreen(vm,null,"AR unavailable",false,{},{},{},{},{},{})}}
        compose.onNodeWithText("Start camera").assertDoesNotExist()
        compose.onNodeWithText("Use measured drawing").performScrollTo().performClick()
        compose.onAllNodesWithText("Name room").onFirst().performScrollTo().performClick()
        compose.onNodeWithText("Room name").performTextInput("Lab")
        compose.onNodeWithText("Create room").performClick()
        // Supply known geometry through the same ViewModel API exercised by the measured fields.
        compose.runOnIdle {vm.measuredPoint(0.0,0.0); vm.measuredPoint(4.0,0.0); vm.measuredPoint(4.0,3.0); vm.measuredPoint(0.0,3.0)}
        compose.onNodeWithText("Review outline").performScrollTo().performClick()
        compose.onNodeWithText("Confirm outline").performScrollTo().performClick()
        compose.onNodeWithText("Check measurements").performScrollTo().performClick()
        compose.onNodeWithText("Save floor plan").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("From corner: Choose").performScrollTo().assertExists()
        compose.runOnIdle {assertTrue(vm.room!!.confirmed); assertEquals(4,vm.room!!.corners.size)}
        compose.runOnIdle {val points=vm.doc!!.points(); vm.measurement(points[0].id,points[1].id,4.0,"VALIDATION")}
        compose.onNodeWithText("Save floor plan").performScrollTo().assertIsEnabled()
    }
    @Test fun measuredFallbackCapturesVerifiesAndPublishesAnActualTurnFloor(): Unit=runBlocking {
        val database=TurnDatabase.build(app,name).also {db=it}
        database.venueDao().upsert(VenueEntity("v","Test",createdAtEpochMillis=1,updatedAtEpochMillis=1))
        database.floorPlanDao().upsertFloor(FloorEntity("f","v","Source",0,20.0,20.0,createdAtEpochMillis=1,updatedAtEpochMillis=1))
        lateinit var vm: WalkMapperViewModel
        instrumentation.runOnMainSync {vm=WalkMapperViewModel(app,SavedStateHandle(mapOf("venueId" to "v","floorId" to "f")),ScanRepository(database,app)); store.put("mapper",vm)}
        compose.waitUntil(10_000) {vm.doc!=null}
        var published: String?=null
        compose.setContent {TurnTheme {MapperDetailsScreen(vm,null,"ARCore unavailable · measured drawing",false,{}, {}, {},{vm.publish {published=it}},{},{})}}
        compose.onNodeWithText("Start camera").assertIsNotEnabled()
        compose.onNodeWithText("Room name").performScrollTo().performTextInput("Bedroom")
        compose.onNodeWithText("Room name").assertTextContains("Bedroom")
        compose.onNodeWithText("Room name").performImeAction()
        waitForKeyboardLayout()
        compose.onNodeWithText("Room name").performScrollTo()
        // Tool rows scroll horizontally; explicitly address the page's vertical scroll semantics.
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
            .performSemanticsAction(SemanticsActions.ScrollBy) { it(0f,320f) }
        try {
            compose.onNodeWithText("Start next room").assertIsDisplayed().assertIsEnabled().performClick()
        } catch(error: AssertionError) {
            File(app.getExternalFilesDir(null),"mapper-ui-failure.txt").writeText(compose.onRoot(useUnmergedTree=true).printToString())
            instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
                File(app.getExternalFilesDir(null),"mapper-ui-failure.png").outputStream().use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)
                }; bitmap.recycle()
            }
            throw error
        }
        compose.runOnIdle { assertNotNull("Room must be created: ${vm.status}; busy=${vm.busy}; current=${vm.currentRoomId}; rooms=${vm.doc!!.rooms.map {it.id to it.name}}",vm.room) }
        compose.onNodeWithText("X (m)").performScrollTo().performTextInput("0")
        compose.onNodeWithText("Y (m)").performScrollTo().performTextInput("0")
        compose.onNodeWithText("Y (m)").performImeAction()
        waitForKeyboardLayout()
        compose.onNodeWithText("Add measured point").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals("First measured corner: ${vm.status}; tool=${vm.tool}",1,vm.doc!!.points().size)
            vm.measuredPoint(4.0,0.0); vm.measuredPoint(4.0,3.0); vm.measuredPoint(0.0,3.0)
            assertEquals("Complete measured outline: ${vm.status}",4,vm.doc!!.points().size)
        }
        compose.onNodeWithText("Confirm outline").performScrollTo().performClick()
        compose.runOnIdle {
            val points=vm.doc!!.points()
            vm.measurement(points[0].id,points[1].id,2.0,"CALIBRATION"); vm.applyCalibration(vm.doc!!.measurements.single().id)
            vm.unconfirmRoom(); vm.measuredPoint(2.0,1.5)
            // This is the existing scaled corner: numeric entry must not apply that scale a second time.
            assertEquals(4,vm.doc!!.points().size); vm.finishRoom()
            vm.measurement(points[1].id,points[2].id,1.5,"VALIDATION")
        }
        compose.onNodeWithText("Verify & save",useUnmergedTree=true).performScrollTo().performClick()
        compose.onNodeWithText("Save as new TURN project").performScrollTo().assertIsEnabled().performClick()
        compose.waitUntil(15_000) {published!=null}
        assertNotEquals("f",published)
        val map=com.turn.fieldtest.data.repository.ProjectRepository(database).load(com.turn.fieldtest.data.repository.ProjectContext(vm.doc!!.publishedVenueId!!,published!!))
        assertEquals("Bedroom",map.regions.single().name); assertTrue(map.nodes.isNotEmpty())
        image=File(android.net.Uri.parse(map.asset!!.contentUri).path!!)
        assertTrue(image!!.isFile)
        compose.onNodeWithText("Open saved floor in TURN").performScrollTo().assertIsDisplayed()
    }
}
