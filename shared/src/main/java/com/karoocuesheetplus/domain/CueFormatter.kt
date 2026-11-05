package com.karoocuesheetplus.domain

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

object CueFormatter {

    private const val METERS_PER_MILE = 1609.344
    private const val FEET_PER_METER = 3.28084

    data class DistanceText(
        val value: String,
        val unit: String,
    ) {
        val full: String get() = "$value $unit"
    }

    fun formatDistance(meters: Double, isMetric: Boolean): DistanceText {
        val sign = if (meters < -1.0) "-" else ""
        val absM = abs(meters)

        return if (isMetric) {
            when {
                absM < 950.0 -> {
                    val rounded10 = ((absM / 10.0).roundToInt() * 10).coerceAtLeast(0)
                    DistanceText("$sign$rounded10", "m")
                }
                absM < 100_000.0 -> {
                    DistanceText(String.format(Locale.US, "%s%.1f", sign, absM / 1000.0), "km")
                }
                else -> {
                    DistanceText(String.format(Locale.US, "%s%d", sign, (absM / 1000.0).roundToInt()), "km")
                }
            }
        } else {
            val miles = absM / METERS_PER_MILE
            when {
                miles < 0.15 -> {
                    val feet = ((absM * FEET_PER_METER / 50.0).roundToInt() * 50).coerceAtLeast(0)
                    DistanceText("$sign$feet", "ft")
                }
                miles < 100.0 -> {
                    DistanceText(String.format(Locale.US, "%s%.1f", sign, miles), "mi")
                }
                else -> {
                    DistanceText(String.format(Locale.US, "%s%d", sign, miles.roundToInt()), "mi")
                }
            }
        }
    }

    fun formatElevationDelta(
        ascentM: Double,
        descentM: Double,
        isMetricElevation: Boolean,
        strings: CueStrings = CueStrings.forLocale(),
    ): String {
        val unit = if (isMetricElevation) "m" else "ft"
        val factor = if (isMetricElevation) 1.0 else FEET_PER_METER
        val ascVal = (ascentM * factor).roundToInt()
        val descVal = (descentM * factor).roundToInt()

        return when {
            ascVal >= 15 -> "↗ +$ascVal$unit"
            descVal >= 40 -> "↘ -$descVal$unit"
            else -> strings.flatTerrainLabel
        }
    }

    fun formatAscentCardValue(ascentM: Double, isMetricElevation: Boolean): String {
        val unit = if (isMetricElevation) "m" else "ft"
        val factor = if (isMetricElevation) 1.0 else FEET_PER_METER
        return "+${(ascentM * factor).roundToInt()} $unit"
    }

    fun formatEte(eteSeconds: Long?): String {
        if (eteSeconds == null) return "--"
        val totalMinutes = ((eteSeconds + 30L) / 60L).coerceAtLeast(1L)
        val hours = totalMinutes / 60L
        val mins = totalMinutes % 60L
        return if (hours > 0L) {
            String.format(Locale.US, "%dh %02dm", hours, mins)
        } else {
            "${mins}m"
        }
    }

    fun formatEtaClock(etaEpochMillis: Long?, is24Hour: Boolean): String {
        if (etaEpochMillis == null) return "--:--"
        val pattern = if (is24Hour) "HH:mm" else "h:mm a"
        return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(etaEpochMillis))
    }

    fun formatCombinedEtaAndEte(etaEpochMillis: Long?, eteSeconds: Long?, is24Hour: Boolean): String {
        if (etaEpochMillis == null || eteSeconds == null) return "--:--"
        return "${formatEtaClock(etaEpochMillis, is24Hour)} (${formatEte(eteSeconds)})"
    }
}
