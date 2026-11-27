package com.karoocuesheetplus.data

import android.content.Context
import com.karoocuesheetplus.domain.CueCategory
import com.karoocuesheetplus.domain.CueSheetSettings
import com.karoocuesheetplus.domain.RowMetricMode
import com.karoocuesheetplus.domain.SurfacePreference
import com.karoocuesheetplus.domain.ThemePreference
import com.karoocuesheetplus.domain.TimeDisplayMode
import com.karoocuesheetplus.domain.UnitPreference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class CueSettingsStore private constructor(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<CueSheetSettings> = _settings.asStateFlow()

    private val _karooMetricDistance = MutableStateFlow(getLastKnownKarooMetricDistance())
    val karooMetricDistance: StateFlow<Boolean> = _karooMetricDistance.asStateFlow()

    fun getPersistedLearnedFlatSpeedKmh(): Double {
        val v = prefs.getFloat(KEY_LEARNED_FLAT_SPEED_KMH, -1f).toDouble()
        return if (v in 12.0..45.0) v else _settings.value.defaultFlatSpeedKmh
    }

    fun saveLearnedFlatSpeedKmh(speedKmh: Double) {
        if (speedKmh in 12.0..45.0) {
            prefs.edit().putFloat(KEY_LEARNED_FLAT_SPEED_KMH, speedKmh.toFloat()).apply()
        }
    }

    fun getLastKnownKarooMetricDistance(): Boolean {
        return prefs.getBoolean(KEY_KAROO_METRIC_DISTANCE, true)
    }

    fun getLastKnownKarooMetricElevation(): Boolean {
        return prefs.getBoolean(KEY_KAROO_METRIC_ELEVATION, true)
    }

    fun saveKarooUnits(isMetricDistance: Boolean, isMetricElevation: Boolean) {
        prefs.edit()
            .putBoolean(KEY_KAROO_METRIC_DISTANCE, isMetricDistance)
            .putBoolean(KEY_KAROO_METRIC_ELEVATION, isMetricElevation)
            .apply()
        _karooMetricDistance.value = isMetricDistance
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
            .putString(KEY_TIME_DISPLAY_MODE, next.timeDisplayMode.name)
            .putString(KEY_ROW_METRIC_MODE, next.rowMetricMode.name)
            .putString(KEY_UNIT_PREF, next.unitPreference.name)
            .putString(
                KEY_DISABLED_CATEGORIES,
                next.disabledCategories
                    .filter { it != CueCategory.FINISH }
                    .joinToString(",") { it.name }
            )
            .apply()
        _settings.value = next
    }

    fun toggleCategory(category: CueCategory) {
        if (category == CueCategory.FINISH) return
        update { current ->
            val updated = if (category in current.disabledCategories) {
                current.disabledCategories - category
            } else {
                current.disabledCategories + category
            }
            current.copy(disabledCategories = updated)
        }
    }

    private fun loadSettings(): CueSheetSettings {
        val themeStr = prefs.getString(KEY_THEME_PREF, ThemePreference.AUTO.name)
        val theme = runCatching { ThemePreference.valueOf(themeStr ?: ThemePreference.AUTO.name) }
            .getOrDefault(ThemePreference.AUTO)
        val surfStr = prefs.getString(KEY_SURFACE_PREF, SurfacePreference.AUTO.name)
        val surf = runCatching { SurfacePreference.valueOf(surfStr ?: SurfacePreference.AUTO.name) }
            .getOrDefault(SurfacePreference.AUTO)
        val timeModeStr = prefs.getString(KEY_TIME_DISPLAY_MODE, TimeDisplayMode.ETA_AND_ETE.name)
        val timeMode = runCatching { TimeDisplayMode.valueOf(timeModeStr ?: TimeDisplayMode.ETA_AND_ETE.name) }
            .getOrDefault(TimeDisplayMode.ETA_AND_ETE)
        val rowMetricStr = prefs.getString(KEY_ROW_METRIC_MODE, RowMetricMode.DISTANCE_AND_TIME.name)
        val rowMetric = runCatching { RowMetricMode.valueOf(rowMetricStr ?: RowMetricMode.DISTANCE_AND_TIME.name) }
            .getOrDefault(RowMetricMode.DISTANCE_AND_TIME)
        val unitPrefStr = prefs.getString(KEY_UNIT_PREF, UnitPreference.AUTO.name)
        val unitPref = runCatching { UnitPreference.valueOf(unitPrefStr ?: UnitPreference.AUTO.name) }
            .getOrDefault(UnitPreference.AUTO)
        val disabledCatsRaw = prefs.getString(KEY_DISABLED_CATEGORIES, "").orEmpty()
        val disabledCats = disabledCatsRaw
            .split(',')
            .mapNotNull { token ->
                val trimmed = token.trim()
                if (trimmed.isEmpty()) null
                else CueCategory.entries.firstOrNull { it.name == trimmed && it != CueCategory.FINISH }
            }
            .toSet()
        return CueSheetSettings(
            includeClimbs = prefs.getBoolean(KEY_INCLUDE_CLIMBS, true),
            includeGlobalPois = prefs.getBoolean(KEY_INCLUDE_GLOBAL_POIS, true),
            defaultFlatSpeedKmh = prefs.getFloat(KEY_DEFAULT_FLAT_SPEED_KMH, 25.0f).toDouble().coerceIn(14.0, 42.0),
            autoReturnSeconds = prefs.getInt(KEY_AUTO_RETURN_SECONDS, 10).coerceIn(5, 30),
            refreshIntervalSeconds = prefs.getInt(KEY_REFRESH_INTERVAL_SECONDS, 5).coerceIn(1, 30),
            collapseCircuitPois = prefs.getBoolean(KEY_COLLAPSE_CIRCUIT_POIS, true),
            themePreference = theme,
            surfacePreference = surf,
            timeDisplayMode = timeMode,
            rowMetricMode = rowMetric,
            unitPreference = unitPref,
            disabledCategories = disabledCats,
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
        private const val KEY_TIME_DISPLAY_MODE = "time_display_mode"
        private const val KEY_ROW_METRIC_MODE = "row_metric_mode"
        private const val KEY_UNIT_PREF = "unit_pref"
        private const val KEY_KAROO_METRIC_DISTANCE = "karoo_metric_distance"
        private const val KEY_KAROO_METRIC_ELEVATION = "karoo_metric_elevation"
        private const val KEY_DISABLED_CATEGORIES = "disabled_categories"

        @Volatile
        private var instance: CueSettingsStore? = null

        fun getInstance(context: Context): CueSettingsStore {
            return instance ?: synchronized(this) {
                instance ?: CueSettingsStore(context).also { instance = it }
            }
        }
    }
}
