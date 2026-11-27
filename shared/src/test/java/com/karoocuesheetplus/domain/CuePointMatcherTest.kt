package com.karoocuesheetplus.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CuePointMatcherTest {

    private fun samplePoints(): List<CuePoint> = listOf(
        CuePoint(
            id = "wpt_start_0",
            name = "Largada a Chapelco (190-205W)",
            subtitle = "Waypoint",
            category = CueCategory.GENERIC_WAYPOINT,
            distanceAlongRouteM = 0.0,
            deltaDistanceM = -12_100.0,
            isPassed = true,
            isNextActive = false,
        ),
        CuePoint(
            id = "wpt_chapelco_0",
            name = "Puerto Chapelco (175-190W)",
            subtitle = "Cima / Puerto",
            category = CueCategory.SUMMIT_CLIMB,
            distanceAlongRouteM = 14_500.0,
            deltaDistanceM = 2_400.0,
            isPassed = false,
            isNextActive = true,
        ),
        CuePoint(
            id = "wpt_techo_0",
            name = "Techo Ruta 1180m (120-150W)",
            subtitle = "Waypoint",
            category = CueCategory.GENERIC_WAYPOINT,
            distanceAlongRouteM = 17_600.0,
            deltaDistanceM = 5_500.0,
            isPassed = false,
            isNextActive = false,
        ),
    )

    @Test
    fun `matches unsuffixed POI id against KCS+ occurrence-suffixed id`() {
        val matched = CuePointMatcher.findBestMatch(
            allPoints = samplePoints(),
            waypointId = "wpt_chapelco",
            waypointName = "Puerto Chapelco",
            waypointRawName = "Puerto Chapelco (175-190W)",
            distanceAlongRouteM = 14_500.0,
        )
        assertEquals("wpt_chapelco_0", matched?.id)
    }

    @Test
    fun `matches by route distance and clean name when ids differ`() {
        val matched = CuePointMatcher.findBestMatch(
            allPoints = samplePoints(),
            waypointId = "different_id",
            waypointName = "Puerto Chapelco",
            waypointRawName = null,
            distanceAlongRouteM = 14_510.0,
        )
        assertEquals("wpt_chapelco_0", matched?.id)
    }

    @Test
    fun `matches by clean name when point name includes power range`() {
        val matched = CuePointMatcher.findBestMatch(
            allPoints = samplePoints(),
            waypointId = null,
            waypointName = "Techo Ruta 1180m",
            waypointRawName = null,
            distanceAlongRouteM = null,
        )
        assertEquals("wpt_techo_0", matched?.id)
    }

    @Test
    fun `falls back to next active point when no hints match or none provided`() {
        val matched = CuePointMatcher.findBestMatch(
            allPoints = samplePoints(),
            waypointId = null,
            waypointName = null,
            waypointRawName = null,
            distanceAlongRouteM = null,
        )
        assertEquals("wpt_chapelco_0", matched?.id)
        assertNull(CuePointMatcher.findBestMatch(emptyList(), waypointId = "any"))
    }
}
