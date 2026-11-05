package com.karoocuesheetplus.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class GpxRouteParserTest {

    private fun resolveRioPintoGpxFile(): File {
        val candidates = listOf(
            File("../simulator/src/main/assets/COURSE_97867009.gpx"),
            File("simulator/src/main/assets/COURSE_97867009.gpx"),
        )
        return candidates.first { it.exists() }
    }

    @Test
    fun `parses Desafio Rio Pinto 2022 GPX waypoints elevation and climbs`() {
        val gpxFile = resolveRioPintoGpxFile()
        val parsed = gpxFile.inputStream().use { GpxRouteParser.parse(it) }

        assertEquals("Desafío Río Pinto 2022", parsed.routeName)
        assertTrue("Expected ~80-90 km route, got ${parsed.totalDistanceM}", parsed.totalDistanceM in 78_000.0..92_000.0)
        assertEquals(11, parsed.routePois.size)
        assertTrue("Expected rich elevation profile", parsed.elevationProfile.size > 500)
        assertTrue("Expected detected climbs on Río Pinto", parsed.climbs.isNotEmpty())

        val cuePoints = RouteGeometryEngine.buildCuePoints(
            routeName = parsed.routeName,
            indexedPolyline = parsed.indexedPolyline,
            elevationProfile = parsed.elevationProfile,
            routePois = parsed.routePois,
            globalPois = emptyList(),
            climbs = parsed.climbs,
            includeClimbs = true,
            includeGlobalPois = false,
            riderDistanceAlongRouteM = 15_000.0,
            rejoinExtraDistanceM = 0.0,
            flatSpeedMps = 23.5 / 3.6,
            nowEpochMillis = 1_760_000_000_000L,
            surface = SurfaceProfile.MTB,
            vamMh = SurfaceProfile.MTB.defaultVamMh,
            strings = CueStrings.ES,
        )

        assertTrue(cuePoints.any { it.isPassed })
        assertTrue(cuePoints.any { it.isNextActive })
        assertEquals(CueCategory.FINISH, cuePoints.last().category)

        val lajas = cuePoints.firstOrNull { it.name.contains("LAJAS", ignoreCase = true) }
        assertEquals(CueCategory.DANGER_CAUTION, lajas?.category)

        val mirador = cuePoints.firstOrNull { it.name.contains("Puesto Mirador", ignoreCase = true) }
        assertEquals(CueCategory.FOOD_COFFEE, mirador?.category)
        assertTrue("Mirador requires major climbing from km 15", (mirador?.ascentFromRiderM ?: 0.0) > 350.0)
    }

    @Test
    fun `Desafio Rio Pinto profile estimate starts realistic and adapts smoothly to mud without jumping on sprints`() {
        val parsed = resolveRioPintoGpxFile().inputStream().use { GpxRouteParser.parse(it) }

        fun projectRemainingSec(distM: Double, est: GradientEtaCalculator.FlatPaceEstimator): Long {
            return RouteGeometryEngine.buildCuePoints(
                routeName = parsed.routeName,
                indexedPolyline = parsed.indexedPolyline,
                elevationProfile = parsed.elevationProfile,
                routePois = parsed.routePois,
                globalPois = emptyList(),
                climbs = parsed.climbs,
                includeClimbs = true,
                includeGlobalPois = false,
                riderDistanceAlongRouteM = distM,
                rejoinExtraDistanceM = 0.0,
                flatSpeedMps = est.flatSpeedMps,
                nowEpochMillis = 1_760_000_000_000L,
                surface = est.surface,
                vamMh = est.vamMh,
                descentSkillFactor = est.descentSkillFactor,
                strings = CueStrings.ES,
            ).last().eteSeconds!!
        }

        // 1. Initial pre-race estimate X purely from MTB profile (no watts entered!)
        val mtbEst = GradientEtaCalculator.FlatPaceEstimator(
            initialFlatSpeedKmh = SurfaceProfile.MTB.defaultFlatSeedKmh,
            initialSurface = SurfaceProfile.MTB,
        )
        val initialEteSec = projectRemainingSec(0.0, mtbEst)
        println("Río Pinto Initial Profile Prior (MTB) -> ${CueFormatter.formatEte(initialEteSec)} ($initialEteSec s), V_llano=${mtbEst.flatSpeedKmh}, VAM=${mtbEst.vamMh}")
        assertTrue(
            "Initial MTB profile estimate should be between 3h35m (12900s) and 4h05m (14700s), got $initialEteSec",
            initialEteSec in 12_900L..14_700L,
        )

        // 2. Simulate progressive rain/mud degradation: rider starts at 230W dry, then mud slows ground speed by 25%
        val muddyEst = GradientEtaCalculator.FlatPaceEstimator(
            initialFlatSpeedKmh = SurfaceProfile.MTB.defaultFlatSeedKmh,
            initialSurface = SurfaceProfile.MTB,
        )
        var distM = 0.0
        var elapsedSec = 0.0
        val dt = 4.0
        var projectedAtKm15 = 0L
        var projectedAtKm45 = 0L

        while (distM < 45_000.0) {
            val e0 = GradientEtaCalculator.interpolateElevationAt(parsed.elevationProfile, distM)
            val e1 = GradientEtaCalculator.interpolateElevationAt(parsed.elevationProfile, distM + 40.0)
            val grade = ((e1 - e0) / 40.0) * 100.0
            // After km 12, rain turns the course muddy -> real ground speed for same effort drops progressively to 75%
            val mudFactor = if (distM < 12_000.0) 1.0 else (1.0 - ((distM - 12_000.0) / 30_000.0) * 0.25).coerceAtLeast(0.74)
            val baseSpeed = GradientEtaCalculator.solveGroundSpeedMps(
                wheelPowerWatts = 220.0 * 0.95,
                gradePct = grade,
                surface = SurfaceProfile.MTB,
            )
            val realSpeed = (baseSpeed * mudFactor).coerceAtLeast(1.5)
            muddyEst.observe(speedMps = realSpeed, gradePct = grade, dtSeconds = dt)
            val prevDist = distM
            distM += realSpeed * dt
            elapsedSec += dt

            if (prevDist < 15_000.0 && distM >= 15_000.0) {
                projectedAtKm15 = elapsedSec.toLong() + projectRemainingSec(distM, muddyEst)
            }
        }
        projectedAtKm45 = elapsedSec.toLong() + projectRemainingSec(distM, muddyEst)

        // 3. Now rider hits a 60-second 400W sprint at km 45 -> learned pace/VAM shift must be minimal (< 40s)!
        val checkpointDistM = distM
        val estRemainingAtKm45BeforeSprint = projectRemainingSec(checkpointDistM, muddyEst)
        val beforeSprintProjected = projectedAtKm45
        repeat(15) {
            val e0 = GradientEtaCalculator.interpolateElevationAt(parsed.elevationProfile, distM)
            val e1 = GradientEtaCalculator.interpolateElevationAt(parsed.elevationProfile, distM + 40.0)
            val grade = ((e1 - e0) / 40.0) * 100.0
            val sprintSpeed = GradientEtaCalculator.solveGroundSpeedMps(
                wheelPowerWatts = 400.0 * 0.95,
                gradePct = grade,
                surface = SurfaceProfile.MTB,
            )
            muddyEst.observe(speedMps = sprintSpeed, gradePct = grade, dtSeconds = 4.0)
            distM += sprintSpeed * 4.0
            elapsedSec += 4.0
        }
        val estRemainingAtKm45AfterSprint = projectRemainingSec(checkpointDistM, muddyEst)
        val pureEstimatorShiftSec = kotlin.math.abs(estRemainingAtKm45AfterSprint - estRemainingAtKm45BeforeSprint)
        val afterSprintProjected = elapsedSec.toLong() + projectRemainingSec(distM, muddyEst)

        println("Río Pinto Mud Evolution -> Start=${CueFormatter.formatEte(initialEteSec)} | Km15=${CueFormatter.formatEte(projectedAtKm15)} | Km45(Mud)=${CueFormatter.formatEte(projectedAtKm45)} | After 60s 400W Sprint=${CueFormatter.formatEte(afterSprintProjected)} (pureModelShift=${pureEstimatorShiftSec}s)")

        assertTrue(
            "Muddy conditions should progressively increase projected total time ($projectedAtKm45 > $projectedAtKm15 + 600s)",
            projectedAtKm45 > projectedAtKm15 + 600L,
        )
        assertTrue(
            "A 60-second 400W sprint mid-race must shift the extrapolated pace model by < 45 seconds, got ${pureEstimatorShiftSec}s",
            pureEstimatorShiftSec < 45L,
        )
    }
}
