package com.karoocuesheetplus.data

import android.content.Context
import com.karoocuesheetplus.domain.CueSheetSettings
import com.karoocuesheetplus.domain.SurfacePreference
import com.karoocuesheetplus.domain.ThemePreference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class CueSettingsStore private constructor(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<CueSheetSettings> = _settings.asStateFlow()

    fun getPersistedLearnedFlatSpeedKmh(): Double {
        val v = prefs.getFloat(KEY_LEARNED_FLAT_SPEED_KMH, -1f).toDouble()
        return if (v in 12.0..45.0) v else _settings.value.defaultFlatSpeedKmh
    }

    fun saveLearnedFlatSpeedKmh(speedKmh: Double) {
        if (speedKmh in 12.0..45.0) {
            prefs.edit().putFloat(KEY_LEARNED_FLAT_SPEED_KMH, speedKmh.toFloat()).apply()
        }
    }

    fun update(transform: (CueSheetSettings) -> CueSheetSettings) {
        val next = transform(_settings.value)
        prefs.edit()
            .putBoolean(KEY_INCLUDE_CLIMBS, next.includeClimbs)
            .putBoolean(KEY_INCLUDE_GLOBAL_POIS, next.includeGlobalPois)
            .putFloat(KEY_DEFAULT_FLAT_SPEED_KMH, next.defaultFlatSpeedKmh.toFloat())
            .putInt(KEY_AUTO_RETURN_SECONDS, next.autoReturnSeconds)
            .putInt(KEY_REFRESH_INTERVAL_SECONDS, next.refreshIntervalSeconds)
            .putBoolean(KEY_COLLAPSE_CIRCUIT_POIS, next.collapseCircuitPois)
            .putString(KEY_THEME_PREF, next.themePreference.name)
            .putString(KEY_SURFACE_PREF, next.surfacePreference.name)
            .apply()
        _settings.value = next
    }

    private fun loadSettings(): CueSheetSettings {
        val themeStr = prefs.getString(KEY_THEME_PREF, ThemePreference.AUTO.name)
        val theme = runCatching { ThemePreference.valueOf(themeStr ?: ThemePreference.AUTO.name) }
            .getOrDefault(ThemePreference.AUTO)
        val surfStr = prefs.getString(KEY_SURFACE_PREF, SurfacePreference.AUTO.name)
        val surf = runCatching { SurfacePreference.valueOf(surfStr ?: SurfacePreference.AUTO.name) }
            .getOrDefault(SurfacePreference.AUTO)
        return CueSheetSettings(
            includeClimbs = prefs.getBoolean(KEY_INCLUDE_CLIMBS, true),
            includeGlobalPois = prefs.getBoolean(KEY_INCLUDE_GLOBAL_POIS, true),
            defaultFlatSpeedKmh = prefs.getFloat(KEY_DEFAULT_FLAT_SPEED_KMH, 25.0f).toDouble().coerceIn(14.0, 42.0),
            autoReturnSeconds = prefs.getInt(KEY_AUTO_RETURN_SECONDS, 10).coerceIn(5, 30),
            refreshIntervalSeconds = prefs.getInt(KEY_REFRESH_INTERVAL_SECONDS, 5).coerceIn(1, 30),
            collapseCircuitPois = prefs.getBoolean(KEY_COLLAPSE_CIRCUIT_POIS, true),
            themePreference = theme,
            surfacePreference = surf,
        )
    }

    companion object {
        private const val PREFS_NAME = "karoo_cuesheet_plus_prefs"
        private const val KEY_INCLUDE_CLIMBS = "include_climbs"
        private const val KEY_INCLUDE_GLOBAL_POIS = "include_global_pois"
        private const val KEY_DEFAULT_FLAT_SPEED_KMH = "default_flat_speed_kmh"
        private const val KEY_LEARNED_FLAT_SPEED_KMH = "learned_flat_speed_kmh"
        private const val KEY_AUTO_RETURN_SECONDS = "auto_return_seconds"
        private const val KEY_REFRESH_INTERVAL_SECONDS = "refresh_interval_seconds"
        private const val KEY_COLLAPSE_CIRCUIT_POIS = "collapse_circuit_pois"
        private const val KEY_THEME_PREF = "theme_pref"
        private const val KEY_SURFACE_PREF = "surface_pref"

        @Volatile
        private var instance: CueSettingsStore? = null

        fun getInstance(context: Context): CueSettingsStore {
            return instance ?: synchronized(this) {
                instance ?: CueSettingsStore(context).also { instance = it }
            }
        }
    }
}
