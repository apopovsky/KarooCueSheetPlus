package com.karoocuesheetplus.domain

import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

object RouteGeometryEngine {

    private const val EARTH_RADIUS_M = 6_371_000.0
    const val PASSED_HYSTERESIS_M = 35.0
    private const val GLOBAL_POI_MAX_CORRIDOR_M = 250.0
    private const val CLIMB_SUMMIT_DEDUP_M = 250.0
    private const val FINISH_DEDUP_M = 80.0

    data class IndexedPolyline(
        val points: List<LatLngPoint>,
        val cumulativeDistM: DoubleArray,
        val totalDistanceM: Double,
    )

    data class ProjectionResult(
        val distanceAlongRouteM: Double,
        val crossTrackDistanceM: Double,
    )

    fun buildIndexedPolyline(points: List<LatLngPoint>, declaredRouteDistanceM: Double? = null): IndexedPolyline {
        if (points.isEmpty()) {
            return IndexedPolyline(emptyList(), DoubleArray(0), declaredRouteDistanceM ?: 0.0)
        }
        val rawCum = DoubleArray(points.size)
        var acc = 0.0
        for (i in 1 until points.size) {
            acc += haversineMeters(points[i - 1], points[i])
            rawCum[i] = acc
        }
        val targetTotal = if (declaredRouteDistanceM != null && declaredRouteDistanceM > 1.0 && acc > 1.0) {
            val scale = declaredRouteDistanceM / acc
            // Normalize轻微 drift between spherical Haversine and Karoo's internal route length
            if (scale in 0.90..1.10) {
                for (i in rawCum.indices) {
                    rawCum[i] *= scale
                }
            }
            declaredRouteDistanceM
        } else {
            max(acc, declaredRouteDistanceM ?: 0.0)
        }
        return IndexedPolyline(points, rawCum, targetTotal)
    }

    fun projectOntoPolyline(indexed: IndexedPolyline, target: LatLngPoint): ProjectionResult? {
        val pts = indexed.points
        if (pts.isEmpty()) return null
        if (pts.size == 1) {
            return ProjectionResult(0.0, haversineMeters(pts[0], target))
        }

        var bestCrossM = Double.MAX_VALUE
        var bestAlongM = 0.0
        val cosLat = cos(Math.toRadians(target.lat)).coerceAtLeast(0.05)

        for (i in 0 until pts.lastIndex) {
            val a = pts[i]
            val b = pts[i + 1]

            // Local equirectangular tangent plane around target (fast & sub-meter accurate)
            val ax = Math.toRadians(a.lng - target.lng) * cosLat * EARTH_RADIUS_M
            val ay = Math.toRadians(a.lat - target.lat) * EARTH_RADIUS_M
            val bx = Math.toRadians(b.lng - target.lng) * cosLat * EARTH_RADIUS_M
            val by = Math.toRadians(b.lat - target.lat) * EARTH_RADIUS_M

            val dx = bx - ax
            val dy = by - ay
            val segLenSq = dx * dx + dy * dy
            val t = if (segLenSq <= 1e-6) {
                0.0
            } else {
                ((-ax * dx - ay * dy) / segLenSq).coerceIn(0.0, 1.0)
            }

            val projX = ax + t * dx
            val projY = ay + t * dy
            val distM = hypot(projX, projY)

            if (distM < bestCrossM) {
                bestCrossM = distM
                val segStartDist = indexed.cumulativeDistM[i]
                val segEndDist = indexed.cumulativeDistM[i + 1]
                bestAlongM = segStartDist + t * (segEndDist - segStartDist)
            }
        }

        return ProjectionResult(bestAlongM, bestCrossM)
    }

    fun buildCuePoints(
        routeName: String?,
        indexedPolyline: IndexedPolyline,
        elevationProfile: List<ElevationSample>,
        routePois: List<RawPoiInput>,
        globalPois: List<RawPoiInput>,
        climbs: List<ClimbSegment>,
        includeClimbs: Boolean = true,
        includeGlobalPois: Boolean = true,
        collapseCircuitPois: Boolean = true,
        riderDistanceAlongRouteM: Double = 0.0,
        rejoinExtraDistanceM: Double = 0.0,
        flatSpeedMps: Double = 7.0,
        nowEpochMillis: Long = System.currentTimeMillis(),
        surface: SurfaceProfile = SurfaceProfile.GRAVEL,
        vamMh: Double? = null,
        climbWheelWkg: Double? = null,
        descentSkillFactor: Double = 1.0,
        riderWeightKg: Double = GradientEtaCalculator.DEFAULT_RIDER_WEIGHT_KG,
        strings: CueStrings = CueStrings.forLocale(),
    ): List<CuePoint> {
        val totalDistM = indexedPolyline.totalDistanceM
        if (totalDistM <= 1.0) return emptyList()

        data class Candidate(
            val id: String,
            val name: String,
            val subtitle: String,
            val category: CueCategory,
            val distanceM: Double,
        )

        val candidates = ArrayList<Candidate>()

        // 1. Route POIs
        for (poi in routePois) {
            val category = CueCategory.fromKarooPoiTypeAndName(poi.type, poi.name)
            val cleanName = poi.name?.trim().takeUnless { it.isNullOrEmpty() } ?: category.localizedLabel(strings)

            val routeDistances = if (poi.distancesAlongRouteM.isNotEmpty()) {
                poi.distancesAlongRouteM
            } else {
                val proj = projectOntoPolyline(indexedPolyline, LatLngPoint(poi.lat, poi.lng))
                if (proj != null) listOf(proj.distanceAlongRouteM) else emptyList()
            }

            // In multi-lap circuits, collapse repeated passes of the same POI to only the nearest upcoming
            // (and most recent passed) pass so future laps don't flood the cue sheet and hide upcoming distinct waypoints/finish.
            val relevantDistances = if (collapseCircuitPois && routeDistances.size > 1) {
                val sortedDists = routeDistances.sorted()
                val nextUpcoming = sortedDists.firstOrNull { it >= riderDistanceAlongRouteM - PASSED_HYSTERESIS_M }
                val lastPassed = sortedDists.lastOrNull { it < riderDistanceAlongRouteM - PASSED_HYSTERESIS_M }
                listOfNotNull(lastPassed, nextUpcoming).distinct()
            } else {
                routeDistances
            }

            for ((occIdx, distM) in relevantDistances.withIndex()) {
                val clampedDist = distM.coerceIn(0.0, totalDistM)
                val matchedClimb = climbs.firstOrNull {
                    kotlin.math.abs(it.summitDistanceM - clampedDist) <= CLIMB_SUMMIT_DEDUP_M
                }
                val subtitle = if (matchedClimb != null) {
                    strings.formatClimbSubtitle(matchedClimb)
                } else {
                    CueCategory.resolveSubtitle(category, poi.type, poi.name, strings)
                }
                candidates.add(
                    Candidate(
                        id = "${poi.id}_$occIdx",
                        name = cleanName,
                        subtitle = subtitle,
                        category = category,
                        distanceM = clampedDist,
                    )
                )
            }
        }

        // 2. Global POIs near the route corridor
        if (includeGlobalPois) {
            for (gPoi in globalPois) {
                val alreadyInRoute = routePois.any { it.id == gPoi.id }
                if (alreadyInRoute) continue
                val proj = projectOntoPolyline(indexedPolyline, LatLngPoint(gPoi.lat, gPoi.lng)) ?: continue
                if (proj.crossTrackDistanceM <= GLOBAL_POI_MAX_CORRIDOR_M) {
                    val category = CueCategory.fromKarooPoiTypeAndName(gPoi.type, gPoi.name)
                    val cleanName = gPoi.name?.trim().takeUnless { it.isNullOrEmpty() } ?: category.localizedLabel(strings)
                    candidates.add(
                        Candidate(
                            id = "global_${gPoi.id}",
                            name = cleanName,
                            subtitle = "${category.localizedLabel(strings)} • ${strings.globalSuffix}",
                            category = category,
                            distanceM = proj.distanceAlongRouteM.coerceIn(0.0, totalDistM),
                        )
                    )
                }
            }
        }

        // 3. Climbs (summits) if enabled and not already represented by a nearby POI
        if (includeClimbs) {
            climbs.sortedBy { it.summitDistanceM }.forEachIndexed { idx, climb ->
                val summitDist = climb.summitDistanceM.coerceIn(0.0, totalDistM)
                val duplicatePoi = candidates.any {
                    kotlin.math.abs(it.distanceM - summitDist) <= CLIMB_SUMMIT_DEDUP_M &&
                        (it.category == CueCategory.SUMMIT_CLIMB || it.subtitle.contains("%"))
                }
                if (!duplicatePoi && climb.totalElevationM >= 25.0) {
                    candidates.add(
                        Candidate(
                            id = "climb_${idx}_${summitDist.roundToInt()}",
                            name = strings.formatClimbTitle(idx + 1),
                            subtitle = strings.formatClimbSubtitle(climb),
                            category = CueCategory.SUMMIT_CLIMB,
                            distanceM = summitDist,
                        )
                    )
                }
            }
        }

        // 4. Finish / Route End
        val hasFinishPoi = candidates.any { totalDistM - it.distanceM <= FINISH_DEDUP_M }
        if (!hasFinishPoi) {
            val finishSubtitle = routeName?.takeIf { it.isNotBlank() } ?: strings.catFinish
            candidates.add(
                Candidate(
                    id = "route_finish",
                    name = strings.finishPointName,
                    subtitle = finishSubtitle,
                    category = CueCategory.FINISH,
                    distanceM = totalDistM,
                )
            )
        } else {
            // Ensure the final point is marked as FINISH if it sits right at the end
            val lastIdx = candidates.indices.maxByOrNull { candidates[it].distanceM }
            if (lastIdx != null && totalDistM - candidates[lastIdx].distanceM <= FINISH_DEDUP_M) {
                val last = candidates[lastIdx]
                candidates[lastIdx] = last.copy(category = CueCategory.FINISH)
            }
        }

        val sorted = candidates.sortedBy { it.distanceM }

        // Enrich every candidate with rider-relative distance, terrain ascent/descent/sparkline, and Gradient ETA
        var nextActiveAssigned = false
        return sorted.map { cand ->
            val rawDeltaM = cand.distanceM - riderDistanceAlongRouteM
            val isPassed = riderDistanceAlongRouteM > PASSED_HYSTERESIS_M &&
                rawDeltaM < -PASSED_HYSTERESIS_M &&
                cand.category != CueCategory.FINISH
            val effectiveRemainingM = if (isPassed) {
                rawDeltaM // Negative value e.g. -4200 m
            } else {
                max(0.0, rawDeltaM) + rejoinExtraDistanceM
            }

            val isNextActive = if (!isPassed && !nextActiveAssigned) {
                nextActiveAssigned = true
                true
            } else {
                false
            }

            val terrain = if (!isPassed && cand.distanceM > riderDistanceAlongRouteM) {
                computeTerrainStats(
                    profile = elevationProfile,
                    fromDistM = riderDistanceAlongRouteM,
                    toDistM = cand.distanceM,
                )
            } else if (elevationProfile.size >= 2) {
                // For passed waypoints, still provide a context sparkline of the 3 km leading into that waypoint
                val previewStart = (cand.distanceM - 3000.0).coerceAtLeast(0.0)
                computeTerrainStats(
                    profile = elevationProfile,
                    fromDistM = previewStart,
                    toDistM = cand.distanceM,
                ).copy(ascentM = 0.0, descentM = 0.0)
            } else {
                TerrainStats(0.0, 0.0, 0.0, emptyList())
            }

            val upcomingHazards = if (!isPassed) {
                sorted.count { other ->
                    other.category == CueCategory.DANGER_CAUTION &&
                        other.distanceM > riderDistanceAlongRouteM + 20.0 &&
                        other.distanceM <= cand.distanceM
                }
            } else {
                0
            }

            val eteSec = if (!isPassed) {
                GradientEtaCalculator.estimateEteSeconds(
                    fromDistM = riderDistanceAlongRouteM,
                    toDistM = cand.distanceM,
                    flatSpeedMps = flatSpeedMps,
                    elevationProfile = elevationProfile,
                    climbs = climbs,
                    rejoinExtraDistanceM = rejoinExtraDistanceM,
                    surface = surface,
                    vamMh = vamMh,
                    climbWheelWkg = climbWheelWkg,
                    descentSkillFactor = descentSkillFactor,
                    riderWeightKg = riderWeightKg,
                    upcomingHazardCount = upcomingHazards,
                )
            } else {
                null
            }

            val etaMillis = eteSec?.let { nowEpochMillis + it * 1000L }

            CuePoint(
                id = cand.id,
                name = cand.name,
                subtitle = cand.subtitle,
                category = cand.category,
                distanceAlongRouteM = cand.distanceM,
                deltaDistanceM = effectiveRemainingM,
                isPassed = isPassed,
                isNextActive = isNextActive,
                isPinnedFinish = false,
                ascentFromRiderM = terrain.ascentM,
                descentFromRiderM = terrain.descentM,
                averageGradePct = terrain.avgGradePct,
                eteSeconds = eteSec,
                etaEpochMillis = etaMillis,
                sparklineElevationsM = terrain.sparkline,
            )
        }
    }

    private data class TerrainStats(
        val ascentM: Double,
        val descentM: Double,
        val avgGradePct: Double,
        val sparkline: List<Float>,
    )

    private fun computeTerrainStats(
        profile: List<ElevationSample>,
        fromDistM: Double,
        toDistM: Double,
        sparklineSamples: Int = 40,
    ): TerrainStats {
        val spanM = toDistM - fromDistM
        if (profile.size < 2 || spanM <= 5.0) {
            return TerrainStats(0.0, 0.0, 0.0, emptyList())
        }

        val stepM = max(50.0, spanM / 200.0)
        var cursor = fromDistM
        var prevElev = GradientEtaCalculator.interpolateElevationAt(profile, cursor)
        val startElev = prevElev
        var ascent = 0.0
        var descent = 0.0

        while (cursor < toDistM) {
            val next = min(cursor + stepM, toDistM)
            val nextElev = GradientEtaCalculator.interpolateElevationAt(profile, next)
            val diff = nextElev - prevElev
            if (diff > 0.0) ascent += diff else descent += -diff
            cursor = next
            prevElev = nextElev
        }

        val endElev = prevElev
        val netGradePct = ((endElev - startElev) / spanM) * 100.0

        val spark = ArrayList<Float>(sparklineSamples)
        for (i in 0 until sparklineSamples) {
            val ratio = i.toDouble() / (sparklineSamples - 1).coerceAtLeast(1)
            val d = fromDistM + ratio * spanM
            spark.add(GradientEtaCalculator.interpolateElevationAt(profile, d).toFloat())
        }

        return TerrainStats(
            ascentM = ascent,
            descentM = descent,
            avgGradePct = netGradePct,
            sparkline = spark,
        )
    }

    fun haversineMeters(a: LatLngPoint, b: LatLngPoint): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLng = Math.toRadians(b.lng - a.lng)
        val sinLat = sin(dLat * 0.5)
        val sinLng = sin(dLng * 0.5)
        val h = sinLat * sinLat +
            cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sinLng * sinLng
        return 2.0 * EARTH_RADIUS_M * atan2(sqrt(h), sqrt((1.0 - h).coerceAtLeast(0.0)))
    }
}
