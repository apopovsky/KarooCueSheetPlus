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

    private fun assertEquals(expected: Double, actual: Double, delta: Double) {
        org.junit.Assert.assertEquals(expected, actual, delta)
    }
}
