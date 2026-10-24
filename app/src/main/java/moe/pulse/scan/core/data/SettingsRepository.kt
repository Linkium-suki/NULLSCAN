package moe.pulse.scan.core.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "pulse_settings")

data class PulseSettings(
    val biometricLock: Boolean = false,
    val watermark: Boolean = true,
    val dynamicColor: Boolean = true,
)

class SettingsRepository(private val context: Context) {

    private object Keys {
        val biometricLock = booleanPreferencesKey("biometric_lock")
        val watermark = booleanPreferencesKey("pdf_watermark")
        val dynamicColor = booleanPreferencesKey("dynamic_color")
    }

    val settings: Flow<PulseSettings> = context.dataStore.data.map { prefs ->
        PulseSettings(
            biometricLock = prefs[Keys.biometricLock] ?: false,
            watermark = prefs[Keys.watermark] ?: true,
            dynamicColor = prefs[Keys.dynamicColor] ?: true,
        )
    }

    suspend fun setBiometricLock(enabled: Boolean) {
        context.dataStore.edit { it[Keys.biometricLock] = enabled }
    }

    suspend fun setWatermark(enabled: Boolean) {
        context.dataStore.edit { it[Keys.watermark] = enabled }
    }

    suspend fun setDynamicColor(enabled: Boolean) {
        context.dataStore.edit { it[Keys.dynamicColor] = enabled }
    }
}
