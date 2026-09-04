package com.turn.fieldtest

import android.app.Application
import com.turn.fieldtest.data.export.RoomBackupRepository
import com.turn.fieldtest.data.local.TurnDatabase
import com.turn.fieldtest.data.repository.RoomRepositories
import com.turn.fieldtest.data.settings.DataStoreTurnSettingsRepository
import com.turn.fieldtest.platform.ble.AndroidBleScanner
import com.turn.fieldtest.platform.permissions.TurnPermissionChecker
import com.turn.fieldtest.platform.qr.QrAnchorPayloadCodec
import com.turn.fieldtest.platform.qr.ZxingQrCodeGenerator
import com.turn.fieldtest.platform.sensors.AndroidSensorSource
import com.turn.fieldtest.platform.storage.SafTransferService
import com.turn.fieldtest.platform.wifi.AndroidWifiScanner

/** Process-level offline storage graph. Hardware sources remain session-scoped. */
class TurnApplication : Application() {
    val database: TurnDatabase by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        TurnDatabase.build(this)
    }

    val repositories: RoomRepositories by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        RoomRepositories.from(database)
    }

    val backupRepository by lazy { RoomBackupRepository(database.backupDao()) }
    val settings by lazy { DataStoreTurnSettingsRepository(this) }
    val wifiScanner by lazy { AndroidWifiScanner(this) }
    val sensorSource by lazy { AndroidSensorSource(this) }
    val bleScanner by lazy { AndroidBleScanner(this) }
    val permissionChecker by lazy { TurnPermissionChecker(this) }
    val qrPayloadCodec by lazy { QrAnchorPayloadCodec() }
    val qrCodeGenerator by lazy { ZxingQrCodeGenerator(qrPayloadCodec) }
    val safTransferService by lazy { SafTransferService(this) }
}
