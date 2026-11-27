package com.turn.fieldtest.mapper

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.google.ar.core.*
import com.turn.fieldtest.mapper.ar.*
import com.turn.fieldtest.mapper.core.scanId
import com.turn.fieldtest.mapper.ui.WalkMapperScreen
import com.turn.fieldtest.ui.theme.TurnTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.util.concurrent.Executors
import java.util.concurrent.Future

class WalkMapperActivity: ComponentActivity() {
    private val vm by viewModels<WalkMapperViewModel>()
    private var camera by mutableStateOf<ArScanView?>(null)
    private var availability by mutableStateOf("Checking whether this phone can map with the camera…")
    private var arSupported by mutableStateOf(false)
    private var installPending=false; private var userInstallRequested=true; private var starting=false
    private var closing: Future<*>?=null
    private var permissionPending=false
    private val permission=registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionPending=granted
        if(granted) window.decorView.post { resumeRequestedCamera() } else vm.message("TURN needs the camera to map. Allow Camera for TURN in Android Settings, then tap Start camera again — or enter measurements instead.")
    }
    private val exportJson=registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if(uri!=null) lifecycleScope.launch {
            try { val text=com.turn.fieldtest.mapper.data.ScanRepository.json.encodeToString(com.turn.fieldtest.mapper.core.ScanDocument.serializer(),requireNotNull(vm.doc))
                withContext(Dispatchers.IO) { contentResolver.openOutputStream(uri,"wt")!!.bufferedWriter().use { it.write(text) } }; vm.message("Map data file exported")
            } catch(e: Exception) { vm.message("Export failed: ${e.message}. Your map is still saved.") }
        }
    }
    private val exportPng=registerForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        if(uri!=null) lifecycleScope.launch {
            try {
                val app=application as com.turn.fieldtest.TurnApplication
                val floor=requireNotNull(vm.doc?.publishedFloorId) { "Save the floor plan before exporting its image" }
                val asset=app.repositories.floorPlans.observeAsset(floor).firstValue()
                requireNotNull(asset) { "The floor plan image isn't available" }
                withContext(Dispatchers.IO) { contentResolver.openInputStream(android.net.Uri.parse(asset.contentUri))!!.use { input->contentResolver.openOutputStream(uri,"wt")!!.use(input::copyTo) } }
                vm.message("Floor plan image exported")
            } catch(e: Exception) { vm.message("Image export failed: ${e.message}") }
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val appearance by (application as com.turn.fieldtest.TurnApplication).settings.settings.collectAsState(
                initial = com.turn.fieldtest.data.settings.TurnSettings())
            SideEffect {
                val bars=if(appearance.darkTheme) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                    else SystemBarStyle.light(android.graphics.Color.TRANSPARENT,android.graphics.Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle=bars,navigationBarStyle=bars)
            }
            TurnTheme(darkTheme = appearance.darkTheme) { WalkMapperScreen(vm,camera,availability,arSupported,
            onStartCamera=::startCamera,onStopCamera=::stopCamera,
            onExit={ lifecycleScope.launch { if(vm.doc==null || vm.saveNow()) {
                vm.doc?.publishedFloorId?.let {floor->setResult(RESULT_OK,Intent().putExtra("floorId",floor).putExtra("venueId",vm.doc!!.publishedVenueId ?: vm.doc!!.venueId))}
                finish()
            } } },
            onPublish={ vm.publish { floor-> setResult(RESULT_OK,Intent().putExtra("floorId",floor).putExtra("venueId",vm.doc!!.publishedVenueId)); vm.message("Floor plan saved. Tap Open saved floor to see it in TURN.") } },
            onJson={ vm.save(); exportJson.launch("turn-walk-scan-${vm.doc?.id}.json") },
            onPng={ exportPng.launch("turn-floor-plan.png") },onCheckSupport=::checkSupport) } }
    }
    override fun onResume() {
        super.onResume()
        checkSupport()
        window.decorView.post { resumeRequestedCamera() }
    }
    private fun resumeRequestedCamera() {
        if(permissionPending && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            permissionPending=false
            startCamera()
        }
    }
    private fun checkSupport() {
        ArCoreApk.getInstance().checkAvailabilityAsync(this) { result ->
            if(isFinishing || isDestroyed) return@checkAvailabilityAsync
            arSupported=result.isSupported
            availability=when { result.isTransient->"Still checking whether this phone can map with the camera… Tap Check again if this doesn't go away."; result.isSupported->"This phone can map with the camera"; else->"This phone can't map with the camera (no AR support). You can still enter measurements by hand, or import a floor plan image." }
            if(installPending && arSupported) { installPending=false; startCamera() }
        }
    }
    private fun startCamera() {
        if(starting || camera!=null || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        if(vm.doc==null || vm.busy) return
        if(!vm.cameraMayStart) { vm.message("First pick two saved corners (A and B) at least half a metre apart, so the camera can line up with your map."); return }
        if(vm.room==null) vm.addRoom("Room ${vm.doc!!.rooms.size+1}")
        val granted=ContextCompat.checkSelfPermission(this,Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED
        when(com.turn.fieldtest.mapper.core.CaptureGuard.entry(arSupported,granted,true)) {
            com.turn.fieldtest.mapper.core.CaptureGuard.Companion.Entry.MEASURED_ONLY->{vm.message("Camera mapping isn't available on this phone right now. Enter measurements instead, or close and reopen the mapper to try again."); return}
            com.turn.fieldtest.mapper.core.CaptureGuard.Companion.Entry.REQUEST_PERMISSION->{permission.launch(Manifest.permission.CAMERA); return}
            else->Unit // requestInstall below is the authoritative services install/update check.
        }
        starting=true
        lifecycleScope.launch {
            var session: Session?=null
            try {
                withContext(Dispatchers.IO) { closing?.get() }
                if(!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@launch
                if(ArCoreApk.getInstance().requestInstall(this@WalkMapperActivity,userInstallRequested)==ArCoreApk.InstallStatus.INSTALL_REQUESTED) {
                    userInstallRequested=false; installPending=true; vm.message("Install or update \"Google Play Services for AR\" when asked, then come back to TURN"); return@launch
                }
                session=Session(this@WalkMapperActivity)
                val automatic=session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)
                val raw=session.isDepthModeSupported(Config.DepthMode.RAW_DEPTH_ONLY)
                val mode=when { raw->Config.DepthMode.RAW_DEPTH_ONLY; automatic->Config.DepthMode.AUTOMATIC; else->Config.DepthMode.DISABLED }
                session.configure(Config(session).apply {
                    planeFindingMode=Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
                    depthMode=mode; updateMode=Config.UpdateMode.LATEST_CAMERA_IMAGE
                    focusMode=Config.FocusMode.AUTO
                    cloudAnchorMode=Config.CloudAnchorMode.DISABLED; geospatialMode=Config.GeospatialMode.DISABLED
                })
                val id=scanId(); vm.cameraStarted(id)
                session.resume()
                camera=ArScanView(this@WalkMapperActivity,session,vm,id,mode).also { it.onResume() }
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                vm.message("Camera started")
                session=null // View owns the successfully resumed session.
            } catch(e: Exception) {
                userInstallRequested=true; installPending=false
                val reason=when(e) {
                    is com.google.ar.core.exceptions.CameraNotAvailableException -> "The camera is busy. Close other camera apps, check TURN may use the camera, then tap Start camera again."
                    is com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException -> "This phone can't map with the camera. Enter measurements instead, or import a floor plan image."
                    is com.google.ar.core.exceptions.UnavailableApkTooOldException -> "Update \"Google Play Services for AR\" from the Play Store, then tap Start camera again."
                    is com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException -> "The AR install was cancelled. Tap Start camera to try again, or enter measurements instead."
                    is SecurityException -> "Allow Camera for TURN in Android Settings, then tap Start camera again."
                    else -> "The camera couldn't start. Tap Start camera again, or enter measurements instead. (${e.javaClass.simpleName})"
                }
                vm.message(reason)
            }
            finally { session?.let { s->closeExecutor.submit { s.close() } }; starting=false }
        }
    }
    private fun stopCamera() {
        val view=camera ?: return
        camera=null
        view.queueEvent { view.disposeNative() }; view.onPause()
        runCatching { view.session.pause() }
        closing=closeExecutor.submit { view.session.close() }
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        vm.cameraStopped(); vm.status(ArStatus("Camera paused — your work is saved"))
    }
    override fun onPause() { stopCamera(); vm.save(); super.onPause() }
    override fun onDestroy() { stopCamera(); super.onDestroy() }
    companion object { private val closeExecutor=Executors.newSingleThreadExecutor { r->Thread(r,"turn-ar-close").apply { isDaemon=true } } }
}
private suspend fun <T> kotlinx.coroutines.flow.Flow<T>.firstValue(): T = first()
