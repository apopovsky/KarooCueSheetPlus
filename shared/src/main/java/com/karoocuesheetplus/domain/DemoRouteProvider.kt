package com.karoocuesheetplus.domain

import kotlin.math.PI
import kotlin.math.sin

object DemoRouteProvider {

    fun buildDemoSnapshot(
        settings: CueSheetSettings,
        manualStartIndex: Int?,
        selectedDetailId: String?,
        detailRemainingSeconds: Int,
        isDetailLocked: Boolean = false,
        simulatedRiderDistM: Double = 6_200.0,
        nowEpochMillis: Long = System.currentTimeMillis(),
        isMetricDistance: Boolean = true,
        isMetricElevation: Boolean = true,
        is24HourFormat: Boolean = true,
        strings: CueStrings = CueStrings.forLocale(),
    ): CueSheetSnapshot {
        val totalDistM = 44_200.0
        val latLngs = ArrayList<LatLngPoint>()
        val elevPairs = ArrayList<Pair<Double, Double>>()

        val steps = 88
        for (i in 0..steps) {
            val d = (i.toDouble() / steps) * totalDistM
            val lat = -31.4200 + (d / totalDistM) * 0.28
            val lng = -64.5000 + 0.04 * sin((d / totalDistM) * 2.0 * PI)
            latLngs.add(LatLngPoint(lat, lng))

            // Realistic mountain profile: gentle rise -> steep Alto San Pedro climb -> plateau cafe -> descent -> rolling finish
            val elev = when {
                d <= 12_600.0 -> 620.0 + (d / 12_600.0) * 190.0
                d <= 21_000.0 -> 810.0 + ((d - 12_600.0) / 8_400.0) * 370.0
                d <= 25_400.0 -> 1180.0 + ((d - 21_000.0) / 4_400.0) * 20.0
                d <= 33_000.0 -> 1200.0 - ((d - 25_400.0) / 7_600.0) * 440.0
                else -> 760.0 + ((d - 33_000.0) / 11_200.0) * 145.0
            }
            elevPairs.add(Pair(d, elev))
        }

        val encodedPoly = PolylineDecoder.encodePairs(latLngs.map { it.lat to it.lng }, precision = 5)
        val encodedElev = PolylineDecoder.encodePairs(elevPairs, precision = 1)

        val indexed = RouteGeometryEngine.buildIndexedPolyline(
            points = PolylineDecoder.decodeLatLng(encodedPoly, precision = 5),
            declaredRouteDistanceM = totalDistM,
        )
        val elevationProfile = PolylineDecoder.decodeElevation(encodedElev, precision = 1)

        val climbs = listOf(
            ClimbSegment(
                startDistanceM = 12_600.0,
                lengthM = 8_400.0,
                gradePct = 4.8,
                totalElevationM = 370.0,
            ),
            ClimbSegment(
                startDistanceM = 35_000.0,
                lengthM = 4_200.0,
                gradePct = 3.5,
                totalElevationM = 145.0,
            ),
        )

        val isEs = strings.langCode == "es"
        val demoRouteTitle = if (isEs) "Vuelta San Pedro (Demo)" else "San Pedro Loop (Demo)"

        val routePois = listOf(
            RawPoiInput(
                id = "demo_ctrl1",
                lat = latLngs[4].lat,
                lng = latLngs[4].lng,
                type = "control",
                name = if (isEs) "Control 1" else "Checkpoint 1",
                distancesAlongRouteM = listOf(2_000.0),
            ),
            RawPoiInput(
                id = "demo_water",
                lat = latLngs[25].lat,
                lng = latLngs[25].lng,
                type = "water",
                name = if (isEs) "Puesto Agua" else "Water Station",
                distancesAlongRouteM = listOf(12_600.0),
            ),
            RawPoiInput(
                id = "demo_summit",
                lat = latLngs[42].lat,
                lng = latLngs[42].lng,
                type = "summit",
                name = if (isEs) "Alto San Pedro" else "San Pedro Summit",
                distancesAlongRouteM = listOf(21_000.0),
            ),
            RawPoiInput(
                id = "demo_cafe",
                lat = latLngs[50].lat,
                lng = latLngs[50].lng,
                type = "coffee",
                name = if (isEs) "Café La Cumbre" else "Summit Cafe",
                distancesAlongRouteM = listOf(25_400.0),
            ),
            RawPoiInput(
                id = "demo_caution",
                lat = latLngs[59].lat,
                lng = latLngs[59].lng,
                type = "caution",
                name = if (isEs) "Curva Cerrada" else "Sharp Curve",
                distancesAlongRouteM = listOf(29_700.0),
            ),
            RawPoiInput(
                id = "demo_bike",
                lat = latLngs[68].lat,
                lng = latLngs[68].lng,
                type = "bike_shop",
                name = if (isEs) "Taller El Pedal" else "Bike Service",
                distancesAlongRouteM = listOf(34_500.0),
            ),
        )

        val surface = settings.surfacePreference.resolve(SurfaceProfile.GRAVEL)
        val flatMps = settings.defaultFlatSpeedKmh / 3.6
        val allPoints = RouteGeometryEngine.buildCuePoints(
            routeName = demoRouteTitle,
            indexedPolyline = indexed,
            elevationProfile = elevationProfile,
            routePois = routePois,
            globalPois = emptyList(),
            climbs = climbs,
            includeClimbs = settings.includeClimbs,
            includeGlobalPois = settings.includeGlobalPois,
            riderDistanceAlongRouteM = simulatedRiderDistM,
            rejoinExtraDistanceM = 0.0,
            flatSpeedMps = flatMps,
            nowEpochMillis = nowEpochMillis,
            surface = surface,
            collapseCircuitPois = settings.collapseCircuitPois,
            strings = strings,
        )

        val window = CueListWindowBuilder.buildWindow(allPoints, manualStartIndex)
        val nextPoint = allPoints.firstOrNull { it.isNextActive } ?: allPoints.lastOrNull()
        val detailPoint = selectedDetailId?.let { id -> allPoints.firstOrNull { it.id == id } }

        return CueSheetSnapshot(
            routeName = demoRouteTitle,
            hasActiveRoute = true,
            isDemoMode = false,
            riderDistanceAlongRouteM = simulatedRiderDistM,
            totalRouteDistanceM = totalDistM,
            allPoints = allPoints,
            window = window,
            nextPoint = nextPoint,
            selectedDetailPoint = detailPoint,
            detailRemainingSeconds = if (detailPoint != null) detailRemainingSeconds else 0,
            isDetailLocked = if (detailPoint != null) isDetailLocked else false,
            learnedFlatSpeedKmh = settings.defaultFlatSpeedKmh,
            activeSurface = surface,
            isMetricDistance = isMetricDistance,
            isMetricElevation = isMetricElevation,
            is24HourFormat = is24HourFormat,
            themePreference = settings.themePreference,
            strings = strings,
        )
    }
}
