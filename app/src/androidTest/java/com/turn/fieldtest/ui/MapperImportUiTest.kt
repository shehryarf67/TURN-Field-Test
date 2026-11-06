package com.turn.fieldtest.ui
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.turn.fieldtest.ui.model.*
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
@RunWith(AndroidJUnit4::class)
class MapperImportUiTest {
 @get:Rule val compose=createComposeRule()
 @Test fun replacementWarningIsVisibleBeforeImportAndChoiceReachesRuntime() {
  val state=TurnAppState().apply {mode=DataMode.REAL_DEVICE;destination=TurnDestination.VENUES}
  var selected="";var replace=false
  compose.setContent {TurnApp(state,TurnAppActions(onBundledMapperImport={f,r->selected=f;replace=r}))}
  compose.onNodeWithText("Import university mapper floor").performClick()
  compose.onNodeWithText("This also deletes linked survey/positioning recordings and AR drafts on this device. Export anything you want to keep first.").assertIsDisplayed()
  compose.onNodeWithText("Import C-F4").performClick()
  compose.runOnIdle {assertEquals("C-F4",selected);assertTrue(replace)}
 }
}
