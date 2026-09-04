package com.turn.fieldtest.platform.permissions

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

enum class TurnCapability {
    WIFI_SCAN,
    QR_CAMERA,
    BLE_SCAN,
}

/**
 * Central, version-aware runtime-permission contract. BLE returns no permissions while it is
 * disabled or has no registered beacon, so normal TURN workflows never prompt for Bluetooth.
 */
object TurnPermissionPolicy {
    fun requiredPermissions(
        capability: TurnCapability,
        sdkInt: Int = Build.VERSION.SDK_INT,
        bleFeatureEnabled: Boolean = false,
        registeredBeaconCount: Int = 0,
    ): Set<String> = when (capability) {
        TurnCapability.WIFI_SCAN -> buildSet {
            // Wi-Fi fingerprints are used to derive physical location. Fine location remains an
            // intentional requirement and neverForLocation must not be declared.
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (sdkInt >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.NEARBY_WIFI_DEVICES)
            }
        }

        TurnCapability.QR_CAMERA -> setOf(Manifest.permission.CAMERA)

        TurnCapability.BLE_SCAN -> when {
            !bleFeatureEnabled || registeredBeaconCount <= 0 -> emptySet()
            sdkInt >= Build.VERSION_CODES.S -> setOf(Manifest.permission.BLUETOOTH_SCAN)
            else -> setOf(
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION,
            )
        }
    }
}

class TurnPermissionChecker(private val context: Context) {
    fun missingPermissions(
        capability: TurnCapability,
        bleFeatureEnabled: Boolean = false,
        registeredBeaconCount: Int = 0,
    ): Set<String> = TurnPermissionPolicy.requiredPermissions(
        capability = capability,
        bleFeatureEnabled = bleFeatureEnabled,
        registeredBeaconCount = registeredBeaconCount,
    ).filterTo(linkedSetOf()) { permission ->
        ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED
    }

    fun isGranted(
        capability: TurnCapability,
        bleFeatureEnabled: Boolean = false,
        registeredBeaconCount: Int = 0,
    ): Boolean = missingPermissions(capability, bleFeatureEnabled, registeredBeaconCount).isEmpty()
}
