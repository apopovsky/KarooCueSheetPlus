package com.karoocuesheetplus.domain

import java.util.Locale

/**
 * Physical & surface envelope used for gradient speed projection and simulator physics.
 */
enum class SurfaceProfile(
    val bikeAndGearMassKg: Double,
    val crr: Double,
    val halfRhoCdA: Double,
    val trailMicroLoss: Double,
    val maxSafeDescentKmh: Double,
    val steepTechnicalDescentKmh: Double,
    val hazardPenaltySeconds: Int,
    val defaultFlatSeedKmh: Double,
    val defaultVamMh: Double,
) {
    ROAD(
        bikeAndGearMassKg = 8.5,
        crr = 0.0045,
        halfRhoCdA = 0.20,
        trailMicroLoss = 0.025,
        maxSafeDescentKmh = 60.0,
        steepTechnicalDescentKmh = 48.0,
        hazardPenaltySeconds = 5,
        defaultFlatSeedKmh = 29.5,
        defaultVamMh = 940.0,
    ),
    GRAVEL(
        bikeAndGearMassKg = 10.2,
        crr = 0.0080,
        halfRhoCdA = 0.245,
        trailMicroLoss = 0.042,
        maxSafeDescentKmh = 47.0,
        steepTechnicalDescentKmh = 37.0,
        hazardPenaltySeconds = 15,
        defaultFlatSeedKmh = 25.5,
        defaultVamMh = 880.0,
    ),
    MTB(
        bikeAndGearMassKg = 12.2,
        crr = 0.0112,
        halfRhoCdA = 0.275,
        trailMicroLoss = 0.048,
        maxSafeDescentKmh = 41.0,
        steepTechnicalDescentKmh = 31.0,
        hazardPenaltySeconds = 24,
        defaultFlatSeedKmh = 24.8,
        defaultVamMh = 870.0,
    ),
    MTB_TECHNICAL(
        bikeAndGearMassKg = 14.2,
        crr = 0.0185,
        halfRhoCdA = 0.310,
        trailMicroLoss = 0.105,
        maxSafeDescentKmh = 30.0,
        steepTechnicalDescentKmh = 21.5,
        hazardPenaltySeconds = 50,
        defaultFlatSeedKmh = 18.0,
        defaultVamMh = 700.0,
    );

    companion object {
        /**
         * Zero-config auto-detection from Karoo OS's [RideProfile] fields (`routingPreference`,
         * `defaultActivityType`, `name`).
         */
        fun fromKarooRideProfile(
            routingPreference: String?,
            activityType: String?,
            profileName: String?,
        ): SurfaceProfile {
            val combined = listOfNotNull(routingPreference, activityType, profileName)
                .joinToString(" ")
                .lowercase(Locale.ROOT)

            return when {
                containsAny(combined, "enduro", "downhill", "barro", "mud", "tech", "fat") -> MTB_TECHNICAL
                containsAny(combined, "mtb", "mountain", "trail", "xc", "singletrack", "btt", "pinto") -> MTB
                containsAny(combined, "gravel", "cx", "cyclocross", "adventure", "ripio", "mixed", "bikepack") -> GRAVEL
                containsAny(combined, "road", "ruta", "race", "tarmac", "aero", "asfalto", "strada") -> ROAD
                else -> GRAVEL // Balanced middle ground when profile name is generic
            }
        }

        private fun containsAny(text: String, vararg tokens: String): Boolean =
            tokens.any { text.contains(it) }
    }
}

enum class SurfacePreference {
    AUTO,
    ROAD,
    GRAVEL,
    MTB,
    MTB_TECHNICAL;

    fun resolve(detectedKarooSurface: SurfaceProfile): SurfaceProfile = when (this) {
        AUTO -> detectedKarooSurface
        ROAD -> SurfaceProfile.ROAD
        GRAVEL -> SurfaceProfile.GRAVEL
        MTB -> SurfaceProfile.MTB
        MTB_TECHNICAL -> SurfaceProfile.MTB_TECHNICAL
    }
}

enum class CueCategory(
    val badgeColorDark: Int,
    val badgeColorLight: Int,
) {
    WATER(0xFF38BDF8.toInt(), 0xFF0284C7.toInt()),
    FOOD_COFFEE(0xFFF59E0B.toInt(), 0xFFD97706.toInt()),
    SUMMIT_CLIMB(0xFFF97316.toInt(), 0xFFEA580C.toInt()),
    AID_CONTROL(0xFF14B8A6.toInt(), 0xFF0D9488.toInt()),
    DANGER_CAUTION(0xFFEF4444.toInt(), 0xFFDC2626.toInt()),
    BIKE_SERVICE(0xFFA855F7.toInt(), 0xFF9333EA.toInt()),
    REST_CAMPING(0xFF22C55E.toInt(), 0xFF16A34A.toInt()),
    GENERIC_WAYPOINT(0xFFFACC15.toInt(), 0xFFCA8A04.toInt()),
    FINISH(0xFFE5E7EB.toInt(), 0xFF1F2937.toInt());

    fun localizedLabel(strings: CueStrings = CueStrings.forLocale()): String = when (this) {
        WATER -> strings.catWater
        FOOD_COFFEE -> strings.catFoodCoffee
        SUMMIT_CLIMB -> strings.catSummitClimb
        AID_CONTROL -> strings.catAidControl
        DANGER_CAUTION -> strings.catDangerCaution
        BIKE_SERVICE -> strings.catBikeService
        REST_CAMPING -> strings.catRestCamping
        GENERIC_WAYPOINT -> strings.catGenericWaypoint
        FINISH -> strings.catFinish
    }

    companion object {
        fun fromKarooPoiTypeAndName(poiType: String?, name: String?): CueCategory {
            val normalizedType = poiType?.lowercase(Locale.ROOT)?.trim().orEmpty()
            val byType = when (normalizedType) {
                "water" -> WATER
                "coffee", "food", "bar", "winery", "convenience_store", "shopping" -> FOOD_COFFEE
                "summit", "viewpoint" -> SUMMIT_CLIMB
                "aid_station", "first_aid", "hospital", "control" -> AID_CONTROL
                "caution", "alert", "danger", "warning" -> DANGER_CAUTION
                "bike_shop", "bike_parking", "bike_share", "gas_station" -> BIKE_SERVICE
                "camping", "lodging", "rest_stop", "restroom", "shower", "home", "park" -> REST_CAMPING
                else -> null
            }
            if (byType != null) return byType

            val lowerName = name?.lowercase(Locale.ROOT).orEmpty()
            return when {
                containsAny(lowerName, "agua", "water", "fuente", "hidrat") -> WATER
                containsAny(lowerName, "café", "cafe", "coffee", "bar", "panader", "comida", "restaur", "almuerzo", "food") -> FOOD_COFFEE
                containsAny(
                    lowerName,
                    "peligro", "curva", "cuidado", "precauc", "caution", "danger",
                    "bache", "ripio", "laja", "vado", "ford", "bajada", "descenso", "alerta", "!"
                ) -> DANGER_CAUTION
                containsAny(lowerName, "cima", "alto ", "puerto", "cumbre", "summit", "col ", "mirador", "portillo") -> SUMMIT_CLIMB
                containsAny(lowerName, "control", "checkpoint", "puesto", "avituall", "aid", "auxilio", "pc ") -> AID_CONTROL
                containsAny(lowerName, "taller", "bici", "bike", "mecán", "mecan") -> BIKE_SERVICE
                containsAny(lowerName, "camping", "refugio", "hotel", "baño", "ducha", "descanso", "hostal") -> REST_CAMPING
                else -> GENERIC_WAYPOINT
            }
        }

        fun resolveSubtitle(
            category: CueCategory,
            poiType: String?,
            name: String?,
            strings: CueStrings = CueStrings.forLocale(),
        ): String {
            val lowerName = name?.lowercase(Locale.ROOT).orEmpty()
            return when {
                lowerName.contains("vado") || lowerName.contains("ford") -> strings.subRiverCrossing
                lowerName.contains("bajada") || lowerName.contains("descenso") -> strings.subTechnicalDescent
                lowerName.contains("laja") -> strings.subRockSlabs
                poiType?.equals("alert", ignoreCase = true) == true -> strings.subRouteAlert
                else -> category.localizedLabel(strings)
            }
        }

        private fun containsAny(text: String, vararg tokens: String): Boolean =
            tokens.any { text.contains(it) }
    }
}

data class LatLngPoint(
    val lat: Double,
    val lng: Double,
)

data class ElevationSample(
    val distanceM: Double,
    val elevationM: Double,
)

data class ClimbSegment(
    val startDistanceM: Double,
    val lengthM: Double,
    val gradePct: Double,
    val totalElevationM: Double,
) {
    val summitDistanceM: Double get() = startDistanceM + lengthM
}

data class RawPoiInput(
    val id: String,
    val lat: Double,
    val lng: Double,
    val type: String,
    val name: String?,
    val distancesAlongRouteM: List<Double> = emptyList(),
    val isGlobal: Boolean = false,
)

data class CuePoint(
    val id: String,
    val name: String,
    val subtitle: String,
    val category: CueCategory,
    val distanceAlongRouteM: Double,
    val deltaDistanceM: Double,
    val isPassed: Boolean,
    val isNextActive: Boolean = false,
    val isPinnedFinish: Boolean = false,
    val ascentFromRiderM: Double = 0.0,
    val descentFromRiderM: Double = 0.0,
    val averageGradePct: Double = 0.0,
    val eteSeconds: Long? = null,
    val etaEpochMillis: Long? = null,
    val sparklineElevationsM: List<Float> = emptyList(),
)

data class CueListWindow(
    val rows: List<CuePoint>,
    val activeOrdinal: Int,
    val totalCount: Int,
    val canScrollUp: Boolean,
    val canScrollDown: Boolean,
    val isManualScroll: Boolean,
)

enum class ThemePreference {
    AUTO,
    DARK,
    LIGHT,
}

data class CueSheetSettings(
    val includeClimbs: Boolean = true,
    val includeGlobalPois: Boolean = true,
    val defaultFlatSpeedKmh: Double = 25.0,
    val autoReturnSeconds: Int = 10,
    val themePreference: ThemePreference = ThemePreference.AUTO,
    val surfacePreference: SurfacePreference = SurfacePreference.AUTO,
    val demoModeEnabled: Boolean = false,
)

data class CueSheetSnapshot(
    val routeName: String? = null,
    val hasActiveRoute: Boolean = false,
    val isDemoMode: Boolean = false,
    val riderDistanceAlongRouteM: Double = 0.0,
    val totalRouteDistanceM: Double = 0.0,
    val allPoints: List<CuePoint> = emptyList(),
    val window: CueListWindow = CueListWindow(emptyList(), 0, 0, canScrollUp = false, canScrollDown = false, isManualScroll = false),
    val nextPoint: CuePoint? = null,
    val selectedDetailPoint: CuePoint? = null,
    val detailRemainingSeconds: Int = 0,
    val learnedFlatSpeedKmh: Double = 25.0,
    val learnedClimbWkg: Double = 2.4,
    val learnedVamMh: Double = 850.0,
    val activeSurface: SurfaceProfile = SurfaceProfile.GRAVEL,
    val isMetricDistance: Boolean = true,
    val isMetricElevation: Boolean = true,
    val is24HourFormat: Boolean = true,
    val themePreference: ThemePreference = ThemePreference.AUTO,
    val strings: CueStrings = CueStrings.forLocale(),
)
