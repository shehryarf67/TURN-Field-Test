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
    private var availability by mutableStateOf("Checking ARCore support…")
    private var arSupported by mutableStateOf(false)
    private var installPending=false; private var userInstallRequested=true; private var starting=false
    private var closing: Future<*>?=null
    private var permissionPending=false
    private val permission=registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionPending=granted
        if(granted) window.decorView.post { resumeRequestedCamera() } else vm.message("Camera permission denied. Allow Camera in Android Settings, then retry Start camera. Measured drawing is still available.")
    }
    private val exportJson=registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if(uri!=null) lifecycleScope.launch {
            try { val text=com.turn.fieldtest.mapper.data.ScanRepository.json.encodeToString(com.turn.fieldtest.mapper.core.ScanDocument.serializer(),requireNotNull(vm.doc))
                withContext(Dispatchers.IO) { contentResolver.openOutputStream(uri,"wt")!!.bufferedWriter().use { it.write(text) } }; vm.message("Experimental scan JSON exported")
            } catch(e: Exception) { vm.message("Export failed: ${e.message}; draft remains saved") }
        }
    }
    private val exportPng=registerForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        if(uri!=null) lifecycleScope.launch {
            try {
                val app=application as com.turn.fieldtest.TurnApplication
                val floor=requireNotNull(vm.doc?.publishedFloorId) { "Save a mapped floor before exporting PNG" }
                val asset=app.repositories.floorPlans.observeAsset(floor).firstValue()
                requireNotNull(asset) { "Generated image unavailable" }
                withContext(Dispatchers.IO) { contentResolver.openInputStream(android.net.Uri.parse(asset.contentUri))!!.use { input->contentResolver.openOutputStream(uri,"wt")!!.use(input::copyTo) } }
                vm.message("Floor-plan PNG exported")
            } catch(e: Exception) { vm.message("PNG export failed: ${e.message}") }
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
            onPublish={ vm.publish { floor-> setResult(RESULT_OK,Intent().putExtra("floorId",floor).putExtra("venueId",vm.doc!!.publishedVenueId)); vm.message("Mapped project saved. Export it below or open it in TURN.") } },
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
            availability=when { result.isTransient->"Checking AR support… Tap Check AR support again if this persists."; result.isSupported->"AR supported · tap Start camera to begin"; else->"AR is not available on this phone. You can still import a floor plan or draw using measurements." }
            if(installPending && arSupported) { installPending=false; startCamera() }
        }
    }
    private fun startCamera() {
        if(starting || camera!=null || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        if(vm.doc==null || vm.busy) return
        if(!vm.cameraMayStart) { vm.message("Choose Reference A and Reference B below: two saved corners at least 0.5 m apart. These align the camera with your existing map."); return }
        if(vm.room==null) vm.addRoom("Room ${vm.doc!!.rooms.size+1}")
        val granted=ContextCompat.checkSelfPermission(this,Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED
        when(com.turn.fieldtest.mapper.core.CaptureGuard.entry(arSupported,granted,true)) {
            com.turn.fieldtest.mapper.core.CaptureGuard.Companion.Entry.MEASURED_ONLY->{vm.message("ARCore support is unavailable/unknown. Use measured drawing or reopen the mapper to retry."); return}
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
                    userInstallRequested=false; installPending=true; vm.message("Install/update Google Play Services for AR, then return to TURN"); return@launch
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
                vm.message("ARCore ready · raw depth supported: $raw · automatic depth supported: $automatic")
                session=null // View owns the successfully resumed session.
            } catch(e: Exception) {
                userInstallRequested=true; installPending=false
                val reason=when(e) {
                    is com.google.ar.core.exceptions.CameraNotAvailableException -> "The camera is unavailable. Close other camera apps, check Android Camera access, then retry."
                    is com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException -> "AR is not supported on this phone. Use measured drawing or import a floor plan."
                    is com.google.ar.core.exceptions.UnavailableApkTooOldException -> "Update Google Play Services for AR, then retry Start camera."
                    is com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException -> "AR services installation was cancelled. Tap Start camera to try again, or use measured drawing."
                    is SecurityException -> "Allow Camera access in Android Settings, then retry Start camera."
                    else -> "The AR camera could not start. Retry Start camera or use measured drawing. Details: ${e.javaClass.simpleName}"
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
        vm.cameraStopped(); vm.status(ArStatus("Camera paused · geometry kept. Align a new AR section before capturing again."))
    }
    override fun onPause() { stopCamera(); vm.save(); super.onPause() }
    override fun onDestroy() { stopCamera(); super.onDestroy() }
    companion object { private val closeExecutor=Executors.newSingleThreadExecutor { r->Thread(r,"turn-ar-close").apply { isDaemon=true } } }
}
private suspend fun <T> kotlinx.coroutines.flow.Flow<T>.firstValue(): T = first()
