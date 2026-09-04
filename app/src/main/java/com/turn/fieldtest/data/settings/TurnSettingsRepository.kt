package com.turn.fieldtest.data.settings

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.turn.fieldtest.BuildConfig
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

enum class TurnDataMode { DEMO, REAL_DEVICE }

data class TurnSettings(
    val dataMode: TurnDataMode = TurnDataMode.DEMO,
    val wifiK: Int = 4,
    val missingRssiDbm: Int = -100,
    val normalizeDeviceOffset: Boolean = false,
    val defaultStrideMetres: Double = 0.73,
    val particleCount: Int = 600,
    val accelerometerFallbackEnabled: Boolean = false,
    val absoluteRotationVectorEnabled: Boolean = false,
    val bleFeatureEnabled: Boolean = BuildConfig.BLE_DEFAULT_ENABLED,
    val darkTheme: Boolean = true,
) {
    fun normalized(): TurnSettings = copy(
        wifiK = wifiK.coerceIn(1, 20),
        missingRssiDbm = missingRssiDbm.coerceIn(-120, -30),
        defaultStrideMetres = defaultStrideMetres.coerceIn(0.25, 1.5),
        particleCount = particleCount.coerceIn(100, 5_000),
    )
}

interface TurnSettingsRepository {
    val settings: Flow<TurnSettings>
    suspend fun update(transform: (TurnSettings) -> TurnSettings)
    suspend fun setDataMode(mode: TurnDataMode)
    suspend fun setBleFeatureEnabled(enabled: Boolean)
}

private val Context.turnSettingsDataStore by preferencesDataStore(name = "turn-settings")

class DataStoreTurnSettingsRepository(context: Context) : TurnSettingsRepository {
    private val dataStore = context.applicationContext.turnSettingsDataStore

    override val settings: Flow<TurnSettings> = dataStore.data
        .catch { error ->
            if (error is IOException) emit(androidx.datastore.preferences.core.emptyPreferences())
            else throw error
        }
        .map(::fromPreferences)

    override suspend fun update(transform: (TurnSettings) -> TurnSettings) {
        dataStore.edit { preferences ->
            write(preferences, transform(fromPreferences(preferences)).normalized())
        }
    }

    override suspend fun setDataMode(mode: TurnDataMode) = update { it.copy(dataMode = mode) }

    override suspend fun setBleFeatureEnabled(enabled: Boolean) = update {
        it.copy(bleFeatureEnabled = enabled)
    }

    private fun fromPreferences(preferences: Preferences): TurnSettings = TurnSettings(
        dataMode = preferences[Keys.DATA_MODE]
            ?.let { stored -> TurnDataMode.entries.firstOrNull { it.name == stored } }
            ?: TurnDataMode.DEMO,
        wifiK = preferences[Keys.WIFI_K] ?: 4,
        missingRssiDbm = preferences[Keys.MISSING_RSSI_DBM] ?: -100,
        normalizeDeviceOffset = preferences[Keys.NORMALIZE_DEVICE_OFFSET] ?: false,
        defaultStrideMetres = preferences[Keys.DEFAULT_STRIDE_METRES] ?: 0.73,
        particleCount = preferences[Keys.PARTICLE_COUNT] ?: 600,
        accelerometerFallbackEnabled = preferences[Keys.ACCELEROMETER_FALLBACK] ?: false,
        absoluteRotationVectorEnabled = preferences[Keys.ABSOLUTE_ROTATION_VECTOR] ?: false,
        bleFeatureEnabled = preferences[Keys.BLE_FEATURE_ENABLED] ?: BuildConfig.BLE_DEFAULT_ENABLED,
        darkTheme = preferences[Keys.DARK_THEME] ?: true,
    ).normalized()

    private fun write(preferences: androidx.datastore.preferences.core.MutablePreferences, value: TurnSettings) {
        preferences[Keys.DATA_MODE] = value.dataMode.name
        preferences[Keys.WIFI_K] = value.wifiK
        preferences[Keys.MISSING_RSSI_DBM] = value.missingRssiDbm
        preferences[Keys.NORMALIZE_DEVICE_OFFSET] = value.normalizeDeviceOffset
        preferences[Keys.DEFAULT_STRIDE_METRES] = value.defaultStrideMetres
        preferences[Keys.PARTICLE_COUNT] = value.particleCount
        preferences[Keys.ACCELEROMETER_FALLBACK] = value.accelerometerFallbackEnabled
        preferences[Keys.ABSOLUTE_ROTATION_VECTOR] = value.absoluteRotationVectorEnabled
        preferences[Keys.BLE_FEATURE_ENABLED] = value.bleFeatureEnabled
        preferences[Keys.DARK_THEME] = value.darkTheme
    }

    private object Keys {
        val DATA_MODE = stringPreferencesKey("data_mode")
        val WIFI_K = intPreferencesKey("wifi_k")
        val MISSING_RSSI_DBM = intPreferencesKey("missing_rssi_dbm")
        val NORMALIZE_DEVICE_OFFSET = booleanPreferencesKey("normalize_device_offset")
        val DEFAULT_STRIDE_METRES = doublePreferencesKey("default_stride_metres")
        val PARTICLE_COUNT = intPreferencesKey("particle_count")
        val ACCELEROMETER_FALLBACK = booleanPreferencesKey("accelerometer_fallback")
        val ABSOLUTE_ROTATION_VECTOR = booleanPreferencesKey("absolute_rotation_vector")
        val BLE_FEATURE_ENABLED = booleanPreferencesKey("ble_feature_enabled")
        val DARK_THEME = booleanPreferencesKey("dark_theme")
    }
}
