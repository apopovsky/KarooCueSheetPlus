package com.karoocuesheetplus.domain

import java.util.Locale
import kotlin.math.abs

/**
 * Resolves external waypoint navigation requests (e.g. from KarooGFCompanion)
 * against the active CueSheet+ waypoint list.
 */
object CuePointMatcher {

    private const val DISTANCE_MATCH_TOLERANCE_M = 200.0

    fun normalizePoiId(raw: String): String =
        raw.trim().replace(Regex("_\\d+$"), "")

    fun findBestMatch(
        allPoints: List<CuePoint>,
        waypointId: String? = null,
        waypointName: String? = null,
        waypointRawName: String? = null,
        distanceAlongRouteM: Double? = null,
    ): CuePoint? {
        if (allPoints.isEmpty()) return null

        val targetId = waypointId?.trim().takeUnless { it.isNullOrEmpty() }
        val targetClean = waypointName?.trim().takeUnless { it.isNullOrEmpty() }
        val targetRaw = waypointRawName?.trim().takeUnless { it.isNullOrEmpty() }
        val validDistM = distanceAlongRouteM?.takeIf { !it.isNaN() && it >= 0.0 }

        // 1. Match by POI ID (exact or normalized without occurrence suffix "_0", "_1", ...)
        if (targetId != null) {
            val normalizedTarget = normalizePoiId(targetId)
            val idMatches = allPoints.filter {
                it.id == targetId || normalizePoiId(it.id) == normalizedTarget
            }
            if (idMatches.isNotEmpty()) {
                return pickClosestOrNext(idMatches, validDistM)
            }
        }

        // 2. Match by distance along route within tolerance
        if (validDistM != null) {
            val nearPoints = allPoints.filter {
                abs(it.distanceAlongRouteM - validDistM) <= DISTANCE_MATCH_TOLERANCE_M
            }
            if (nearPoints.isNotEmpty()) {
                val nameMatchedNear = nearPoints.filter { pt ->
                    matchesAnyName(pt.name, targetRaw, targetClean)
                }
                val pool = nameMatchedNear.ifEmpty { nearPoints }
                return pool.minByOrNull { abs(it.distanceAlongRouteM - validDistM) }
            }
        }

        // 3. Match by raw or clean name
        if (targetRaw != null || targetClean != null) {
            val exactRaw = if (targetRaw != null) {
                allPoints.filter { it.name.equals(targetRaw, ignoreCase = true) }
            } else {
                emptyList()
            }
            if (exactRaw.isNotEmpty()) {
                return pickClosestOrNext(exactRaw, validDistM)
            }

            val exactClean = if (targetClean != null) {
                allPoints.filter { it.name.equals(targetClean, ignoreCase = true) }
            } else {
                emptyList()
            }
            if (exactClean.isNotEmpty()) {
                return pickClosestOrNext(exactClean, validDistM)
            }

            val fuzzyName = allPoints.filter { pt ->
                matchesAnyName(pt.name, targetRaw, targetClean)
            }
            if (fuzzyName.isNotEmpty()) {
                return pickClosestOrNext(fuzzyName, validDistM)
            }
        }

        // 4. Fallback to next active point
        return allPoints.firstOrNull { it.isNextActive }
            ?: allPoints.firstOrNull { !it.isPassed }
            ?: allPoints.lastOrNull()
    }

    private fun pickClosestOrNext(
        candidates: List<CuePoint>,
        validDistM: Double?,
    ): CuePoint {
        if (candidates.size == 1) return candidates.first()
        if (validDistM != null) {
            return candidates.minByOrNull { abs(it.distanceAlongRouteM - validDistM) }
                ?: candidates.first()
        }
        return candidates.firstOrNull { !it.isPassed } ?: candidates.first()
    }

    private fun matchesAnyName(pointName: String, targetRaw: String?, targetClean: String?): Boolean {
        val normPoint = pointName.trim().lowercase(Locale.ROOT)
        if (targetRaw != null) {
            val normRaw = targetRaw.lowercase(Locale.ROOT)
            if (normPoint == normRaw || normPoint.contains(normRaw) || normRaw.contains(normPoint)) {
                return true
            }
        }
        if (targetClean != null) {
            val normClean = targetClean.lowercase(Locale.ROOT)
            if (normPoint == normClean || normPoint.contains(normClean) || normClean.contains(normPoint)) {
                return true
            }
        }
        return false
    }
}
