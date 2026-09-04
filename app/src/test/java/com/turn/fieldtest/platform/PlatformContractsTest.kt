package com.turn.fieldtest.platform

import android.Manifest
import com.turn.fieldtest.platform.permissions.TurnCapability
import com.turn.fieldtest.platform.permissions.TurnPermissionPolicy
import com.turn.fieldtest.platform.qr.QrAnchorPayload
import com.turn.fieldtest.platform.qr.QrAnchorPayloadCodec
import com.turn.fieldtest.platform.qr.QrPayloadError
import com.turn.fieldtest.platform.qr.QrPayloadResult
import com.turn.fieldtest.platform.wifi.WifiChannels
import com.turn.fieldtest.platform.wifi.WifiFreshness
import com.turn.fieldtest.platform.wifi.WifiFreshnessEvaluator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PlatformContractsTest {
    @Test
    fun wifiFreshnessRequiresUpdatedRecentAndNovelTimestamp() {
        assertEquals(
            WifiFreshness.FRESH,
            WifiFreshnessEvaluator.evaluate(true, 2_000, 500, 1_000),
        )
        assertEquals(
            WifiFreshness.CACHED_OR_STALE,
            WifiFreshnessEvaluator.evaluate(false, 2_000, 500, 1_000),
        )
        assertEquals(
            WifiFreshness.CACHED_OR_STALE,
            WifiFreshnessEvaluator.evaluate(true, 1_000, 500, 1_000),
        )
        assertEquals(
            WifiFreshness.CACHED_OR_STALE,
            WifiFreshnessEvaluator.evaluate(true, 2_000, 15_001, 1_000),
        )
    }

    @Test
    fun derivesCommonWifiChannelsIncludingSixGhzSpecialCase() {
        assertEquals(1, WifiChannels.fromFrequencyMhz(2_412))
        assertEquals(14, WifiChannels.fromFrequencyMhz(2_484))
        assertEquals(36, WifiChannels.fromFrequencyMhz(5_180))
        assertEquals(2, WifiChannels.fromFrequencyMhz(5_935))
        assertEquals(5, WifiChannels.fromFrequencyMhz(5_975))
    }

    @Test
    fun blePermissionsStayEmptyUntilFeatureAndBeaconAreConfigured() {
        assertTrue(TurnPermissionPolicy.requiredPermissions(TurnCapability.BLE_SCAN, 35).isEmpty())
        assertTrue(
            TurnPermissionPolicy.requiredPermissions(
                TurnCapability.BLE_SCAN,
                sdkInt = 35,
                bleFeatureEnabled = true,
                registeredBeaconCount = 0,
            ).isEmpty(),
        )
        assertEquals(
            setOf(Manifest.permission.BLUETOOTH_SCAN),
            TurnPermissionPolicy.requiredPermissions(
                TurnCapability.BLE_SCAN,
                sdkInt = 35,
                bleFeatureEnabled = true,
                registeredBeaconCount = 1,
            ),
        )
    }

    @Test
    fun wifiPermissionsRequestTheAndroidLocationPairAndNearbyWifiWhenApplicable() {
        assertEquals(
            setOf(
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.NEARBY_WIFI_DEVICES,
            ),
            TurnPermissionPolicy.requiredPermissions(TurnCapability.WIFI_SCAN, sdkInt = 35),
        )
        assertEquals(
            setOf(
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION,
            ),
            TurnPermissionPolicy.requiredPermissions(TurnCapability.WIFI_SCAN, sdkInt = 30),
        )
    }

    @Test
    fun qrCodecRejectsUnknownVenueAfterStructuralValidation() {
        val text = QrAnchorPayloadCodec().encode(
            QrAnchorPayload(1, "venue-a", "floor-a", "anchor-a", 2.0, 3.0),
        )
        val result = QrAnchorPayloadCodec().decodeAndValidate(text, knownVenueIds = setOf("venue-b"))
        assertEquals(QrPayloadError.UNKNOWN_VENUE, assertIs<QrPayloadResult.Invalid>(result).error)
    }
}
