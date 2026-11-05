package com.karoocuesheetplus.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import kotlin.math.abs

class CueSheetEngineTest {

    @Test
    fun `localization resolves supported device languages and falls back to English`() {
        assertEquals("es", CueStrings.forLocale(Locale.forLanguageTag("es-AR")).langCode)
        assertEquals("pt", CueStrings.forLocale(Locale.forLanguageTag("pt-BR")).langCode)
        assertEquals("fr", CueStrings.forLocale(Locale.FRANCE).langCode)
        assertEquals("it", CueStrings.forLocale(Locale.ITALY).langCode)
        assertEquals("de", CueStrings.forLocale(Locale.GERMANY).langCode)
        assertEquals("en", CueStrings.forLocale(Locale.US).langCode)
        val fallback = CueStrings.forLocale(Locale.JAPAN)
        assertEquals("en", fallback.langCode)
        assertEquals("Finish", fallback.finishPointName)
        assertEquals("No Active Route", fallback.noActiveRouteTitle)
    }

    @Test
    fun `polyline decoder decodes precision 5 latlng and precision 1 elevation accurately`() {
        val coords = listOf(
            -31.41670 to -64.18330,
            -31.42515 to -64.19125,
            -31.43990 to -64.20500,
        )
        val encodedGeo = PolylineDecoder.encodePairs(coords, precision = 5)
        val decodedGeo = PolylineDecoder.decodeLatLng(encodedGeo, precision = 5)
        assertEquals(3, decodedGeo.size)
        assertEquals(-31.42515, decodedGeo[1].lat, 1e-5)
        assertEquals(-64.19125, decodedGeo[1].lng, 1e-5)

        val elevPairs = listOf(
            0.0 to 520.4,
            1250.5 to 585.8,
            3400.0 to 712.2,
        )
        val encodedElev = PolylineDecoder.encodePairs(elevPairs, precision = 1)
        val decodedElev = PolylineDecoder.decodeElevation(encodedElev, precision = 1)
        assertEquals(3, decodedElev.size)
        assertEquals(1250.5, decodedElev[1].distanceM, 0.15)
        assertEquals(585.8, decodedElev[1].elevationM, 0.15)
    }

    @Test
    fun `gradient ETA penalizes climbs rewards moderate descents and caps technical descents`() {
        val flatMps = 27.0 / 3.6 // 7.5 m/s

        val flatProfile = listOf(
            ElevationSample(0.0, 500.0),
            ElevationSample(10_000.0, 500.0),
        )
        val climbProfile = listOf(
            ElevationSample(0.0, 500.0),
            ElevationSample(10_000.0, 1_100.0), // +6% over 10 km
        )
        val moderateDescentProfile = listOf(
            ElevationSample(0.0, 1_100.0),
            ElevationSample(10_000.0, 700.0), // -4% over 10 km
        )
        val technicalDescentProfile = listOf(
            ElevationSample(0.0, 1_600.0),
            ElevationSample(10_000.0, 500.0), // -11% steep switchbacks
        )

        val flatEte = GradientEtaCalculator.estimateEteSeconds(0.0, 10_000.0, flatMps, flatProfile)
        val climbEte = GradientEtaCalculator.estimateEteSeconds(0.0, 10_000.0, flatMps, climbProfile)
        val modDescEte = GradientEtaCalculator.estimateEteSeconds(0.0, 10_000.0, flatMps, moderateDescentProfile)
        val techDescEte = GradientEtaCalculator.estimateEteSeconds(0.0, 10_000.0, flatMps, technicalDescentProfile)

        assertEquals(1333.0, flatEte.toDouble(), 10.0)
        assertTrue("Climb ETE ($climbEte) should be > 1.8x flat ETE ($flatEte)", climbEte > flatEte * 1.8)
        assertTrue("Moderate descent ETE ($modDescEte) should be faster than flat ($flatEte)", modDescEte < flatEte * 0.85)
        assertTrue("Technical -11% descent ($techDescEte) should be slower than smooth -4% descent ($modDescEte)", techDescEte > modDescEte)
    }

    @Test
    fun `cumulative historical estimator handles peloton drafting short sprints and 50m fords without jumps`() {
        val estimator = GradientEtaCalculator.FlatPaceEstimator(
            initialFlatSpeedKmh = SurfaceProfile.ROAD.defaultFlatSeedKmh, // 29.5 km/h
            initialSurface = SurfaceProfile.ROAD,
        )
        val initialVam = estimator.vamMh

        // 1. 5 min hard chase at 40 km/h: anchored by 10 km prior -> moves moderately (~31.5-33 km/h), not jumping straight to 40
        repeat(60) {
            estimator.observe(speedMps = 40.0 / 3.6, gradePct = 0.0, dtSeconds = 5.0)
        }
        assertTrue("After 5m chase, avg flat (${estimator.flatSpeedKmh}) should be moderated by prior", estimator.flatSpeedKmh in 30.5..33.8)

        // 2. 25 min sustained in peloton at 40 km/h (even if rider only pedals 150W!): V_llano converges toward peloton speed,
        //    while VAM does NOT inflate above human prior!
        repeat(300) {
            estimator.observe(speedMps = 40.0 / 3.6, gradePct = 0.0, dtSeconds = 5.0, liveCrankWatts = 150.0)
        }
        assertTrue("After 30m in peloton, avg flat (${estimator.flatSpeedKmh}) converges toward ~35-39 km/h", estimator.flatSpeedKmh in 34.5..39.2)
        assertEquals("Peloton flat speed must NOT inflate climbing VAM", initialVam, estimator.vamMh, 1.0)

        // 3. 1-minute sprint / attack (48 km/h on flat or 17 km/h on 8% climb) barely nudges cumulative averages
        val beforeSprintFlat = estimator.flatSpeedKmh
        repeat(12) {
            estimator.observe(speedMps = 48.0 / 3.6, gradePct = 0.0, dtSeconds = 5.0, liveCrankWatts = 400.0)
        }
        assertTrue("1-min sprint changed avg flat by < 0.6 km/h", abs(estimator.flatSpeedKmh - beforeSprintFlat) < 0.6)

        // 4. Crossing a 50 m river ford (vado) at 5 km/h (1.39 m/s for 36 s) barely affects accumulated pace
        val mtbEst = GradientEtaCalculator.FlatPaceEstimator(
            initialFlatSpeedKmh = SurfaceProfile.MTB.defaultFlatSeedKmh,
            initialSurface = SurfaceProfile.MTB,
        )
        // Ride 12 km at 24 km/h first
        repeat(360) {
            mtbEst.observe(speedMps = 24.0 / 3.6, gradePct = 0.0, dtSeconds = 5.0)
        }
        val flatBeforeFord = mtbEst.flatSpeedKmh
        // Cross 50 m ford at 5 km/h (36 seconds total)
        mtbEst.observe(speedMps = 5.0 / 3.6, gradePct = 0.0, dtSeconds = 36.0)
        val flatAfterFord = mtbEst.flatSpeedKmh
        assertTrue(
            "50m ford should alter accumulated speed by < 0.15 km/h (was $flatBeforeFord -> $flatAfterFord)",
            abs(flatBeforeFord - flatAfterFord) < 0.15,
        )
    }

    @Test
    fun `window builder marks passed POIs pins finish and supports up-down scrolling`() {
        val settings = CueSheetSettings(includeClimbs = true, demoModeEnabled = true)
        val autoSnap = DemoRouteProvider.buildDemoSnapshot(
            settings = settings,
            manualStartIndex = null,
            selectedDetailId = null,
            detailRemainingSeconds = 0,
            simulatedRiderDistM = 6_200.0,
            strings = CueStrings.ES,
        )

        assertTrue(autoSnap.allPoints.first().isPassed)
        assertNull(autoSnap.allPoints.first().eteSeconds)

        val autoRows = autoSnap.window.rows
        assertEquals(5, autoRows.size)
        assertEquals("Puesto Agua", autoRows[0].name)
        assertTrue(autoRows[0].isNextActive)
        assertFalse(autoRows[0].isPassed)
        assertEquals(CueCategory.FINISH, autoRows[4].category)
        assertTrue(autoRows[4].isPinnedFinish)
        assertTrue(autoSnap.window.canScrollUp)
        assertTrue(autoSnap.window.canScrollDown)

        val upSnap = DemoRouteProvider.buildDemoSnapshot(
            settings = settings,
            manualStartIndex = 0,
            selectedDetailId = null,
            detailRemainingSeconds = 0,
            simulatedRiderDistM = 6_200.0,
            strings = CueStrings.ES,
        )
        assertEquals("Control 1", upSnap.window.rows[0].name)
        assertTrue(upSnap.window.rows[0].isPassed)
        assertEquals("Puesto Agua", upSnap.window.rows[1].name)
        assertTrue(upSnap.window.rows[4].isPinnedFinish)

        val bottomSnap = DemoRouteProvider.buildDemoSnapshot(
            settings = settings,
            manualStartIndex = 99,
            selectedDetailId = null,
            detailRemainingSeconds = 0,
            simulatedRiderDistM = 6_200.0,
            strings = CueStrings.ES,
        )
        assertEquals(5, bottomSnap.window.rows.size)
        assertEquals(CueCategory.FINISH, bottomSnap.window.rows[4].category)
        assertFalse(bottomSnap.window.canScrollDown)
        val finishCount = bottomSnap.window.rows.count { it.category == CueCategory.FINISH }
        assertEquals(1, finishCount)

        val targetId = autoRows[1].id
        val detailSnap = DemoRouteProvider.buildDemoSnapshot(
            settings = settings,
            manualStartIndex = null,
            selectedDetailId = targetId,
            detailRemainingSeconds = 8,
            simulatedRiderDistM = 6_200.0,
            strings = CueStrings.ES,
        )
        assertNotNull(detailSnap.selectedDetailPoint)
        assertEquals(targetId, detailSnap.selectedDetailPoint?.id)
        assertTrue(detailSnap.selectedDetailPoint!!.sparklineElevationsM.isNotEmpty())
    }

    @Test
    fun `stationary or indoor ride start at 0km never marks upcoming waypoints as passed`() {
        val pts = listOf(
            LatLngPoint(-31.4000, -64.1000),
            LatLngPoint(-31.4100, -64.1100),
            LatLngPoint(-31.4200, -64.1200),
            LatLngPoint(-31.4300, -64.1300),
        )
        val indexed = RouteGeometryEngine.buildIndexedPolyline(pts, declaredRouteDistanceM = 3400.0)
        val pois = listOf(
            RawPoiInput("p1", -31.401, -64.101, "generic", "Entrada", listOf(100.0), false),
            RawPoiInput("p2", -31.408, -64.108, "generic", "Entrada", listOf(800.0), false),
            RawPoiInput("p3", -31.421, -64.121, "water", "Agua??", listOf(2100.0), false),
        )

        val pointsAtStart = RouteGeometryEngine.buildCuePoints(
            routeName = "Test Route",
            indexedPolyline = indexed,
            elevationProfile = emptyList(),
            routePois = pois,
            globalPois = emptyList(),
            climbs = emptyList(),
            includeClimbs = false,
            includeGlobalPois = false,
            riderDistanceAlongRouteM = 0.0,
            rejoinExtraDistanceM = 0.0,
            flatSpeedMps = 7.0,
            nowEpochMillis = System.currentTimeMillis(),
        )

        assertEquals(4, pointsAtStart.size) // 3 POIs + FINISH
        assertTrue(pointsAtStart.none { it.isPassed })
        assertTrue(pointsAtStart[0].isNextActive)
        assertEquals("Entrada", pointsAtStart[0].name)
        assertEquals(100.0, pointsAtStart[0].deltaDistanceM, 1.0)
        assertTrue(pointsAtStart.all { it.deltaDistanceM >= 0.0 })
    }

    @Test
    fun `multi-lap circuit POI collapse reduces repeated passes to last passed and next upcoming`() {
        val pts = listOf(
            LatLngPoint(-31.4000, -64.1000),
            LatLngPoint(-31.4500, -64.1500),
        )
        val indexed = RouteGeometryEngine.buildIndexedPolyline(pts, declaredRouteDistanceM = 30_000.0)
        // Simulate a circuit POI (e.g., Agua??) passed 5 times: 2km, 6km, 10km, 14km, 18km
        val circuitPoi = RawPoiInput(
            id = "poi_water",
            lat = -31.4100,
            lng = -64.1100,
            type = "water",
            name = "Agua",
            distancesAlongRouteM = listOf(2_000.0, 6_000.0, 10_000.0, 14_000.0, 18_000.0),
            isGlobal = false,
        )
        val otherPoi = RawPoiInput(
            id = "poi_cafe",
            lat = -31.4400,
            lng = -64.1400,
            type = "food",
            name = "Coffee Stop",
            distancesAlongRouteM = listOf(22_000.0),
            isGlobal = false,
        )

        // Rider is at 8,000m (lap 3: 2km passed, 6km passed; next is 10km; 14km & 18km future)
        val collapsed = RouteGeometryEngine.buildCuePoints(
            routeName = "Park Circuit",
            indexedPolyline = indexed,
            elevationProfile = emptyList(),
            routePois = listOf(circuitPoi, otherPoi),
            globalPois = emptyList(),
            climbs = emptyList(),
            riderDistanceAlongRouteM = 8_000.0,
            collapseCircuitPois = true,
        )

        // With collapse: circuit POI should only have 2 passes (6km as last passed, 10km as next upcoming) + Coffee + Finish = 4 points total
        val waterOccurrences = collapsed.filter { it.name == "Agua" }
        assertEquals(2, waterOccurrences.size)
        assertTrue(waterOccurrences[0].isPassed)
        assertEquals(6_000.0, waterOccurrences[0].distanceAlongRouteM, 1.0)
        assertTrue(waterOccurrences[1].isNextActive)
        assertEquals(10_000.0, waterOccurrences[1].distanceAlongRouteM, 1.0)

        // And other waypoints are preserved and visible!
        assertTrue(collapsed.any { it.name == "Coffee Stop" })
        assertEquals(CueCategory.FINISH, collapsed.last().category)

        // Without collapse: all 5 passes of Agua + Coffee + Finish = 7 points
        val full = RouteGeometryEngine.buildCuePoints(
            routeName = "Park Circuit",
            indexedPolyline = indexed,
            elevationProfile = emptyList(),
            routePois = listOf(circuitPoi, otherPoi),
            globalPois = emptyList(),
            climbs = emptyList(),
            riderDistanceAlongRouteM = 8_000.0,
            collapseCircuitPois = false,
        )
        val fullWater = full.filter { it.name == "Agua" }
        assertEquals(5, fullWater.size)
    }

    @Test
    fun `detail locked state is preserved in snapshot`() {
        val settings = CueSheetSettings()
        val baseSnap = DemoRouteProvider.buildDemoSnapshot(settings, null, null, 0)
        val targetId = baseSnap.allPoints.first().id

        val snapUnlocked = DemoRouteProvider.buildDemoSnapshot(
            settings = settings,
            manualStartIndex = null,
            selectedDetailId = targetId,
            detailRemainingSeconds = 10,
            isDetailLocked = false,
        )
        assertFalse(snapUnlocked.isDetailLocked)

        val snapLocked = DemoRouteProvider.buildDemoSnapshot(
            settings = settings,
            manualStartIndex = null,
            selectedDetailId = targetId,
            detailRemainingSeconds = 0,
            isDetailLocked = true,
        )
        assertTrue(snapLocked.isDetailLocked)
    }

    private fun assertEquals(expected: Double, actual: Double, delta: Double) {
        org.junit.Assert.assertEquals(expected, actual, delta)
    }
}
