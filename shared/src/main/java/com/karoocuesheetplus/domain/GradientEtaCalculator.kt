package com.karoocuesheetplus.domain

import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Historical Cumulative Pace + VAM Extrapolation Engine (v3).
 *
 * Philosophy ("Mirar hacia atrás, promediar ponderado por distancia/desnivel, proyectar hacia adelante"):
 * 1. **Initial Profile Estimate (Bayesian Prior):**
 *    Starts with a profile-based seed (`V_llano_0` and `VAM_0` from Karoo's `ActiveRideProfile` + `UserProfile`,
 *    or surface defaults) backed by a virtual anchor (`10 km` flat + `320 m` ascent) so the first minutes of
 *    chasing a peloton or rolling out of town never cause wild ETA swings.
 * 2. **Zero sensitivity to instantaneous Power (`W`) or short spikes:**
 *    Accumulates actual ridden distance & time on flats (`V_llano`) and actual vertical gain & time on climbs (`VAM`)
 *    with long distance/ascent memory (`~18 km` flat / `~550 m` vertical).
 *    - Drafting in a `40 km/h` peloton at `150 W` naturally converges `V_llano` to peloton speed without falsely
 *      inflating uphill `VAM`.
 *    - A 1-minute `400 W` surge or crossing a `50 m` river ford (`vado`) represents `< 1–3%` of accumulated
 *      distance, moving a 3.5-hour ETA by mere seconds instead of jumping abruptly.
 *    - Progressive terrain degradation (rain turning gravel to mud) or cumulative fatigue steadily lowers the
 *      weighted averages over kilometers, increasing ETA smoothly and organically.
 * 3. **Forward Route Extrapolation:**
 *    Integrates `100 m` micro-segments ahead combining `V_llano` and `VAM` via quadrature on climbs, bounded
 *    technical descent speeds on downhills, `DANGER_CAUTION` ford/hazard delays, and gentle endurance drift.
 */
object GradientEtaCalculator {

    private const val GRAVITY = 9.81
    private const val DRIVETRAIN_EFF = 0.95
    private const val MIN_CLIMB_SPEED_MPS = 1.35    // ~4.9 km/h floor on extreme ramps
    private const val MIN_FLAT_PACE_MPS = 3.05      // ~11.0 km/h min learned flat pace
    private const val MAX_FLAT_PACE_MPS = 13.30     // ~47.9 km/h max learned flat pace (fast peloton)
    private const val MIN_VAM_MH = 380.0
    private const val MAX_VAM_MH = 1_650.0
    private const val REF_VAM_GRADE = 0.08          // 8% reference gradient for VAM normalization
    private const val MICRO_SEGMENT_METERS = 100.0
    const val DEFAULT_RIDER_WEIGHT_KG = 74.0

    /**
     * Solves steady-state ground speed (m/s) for a given [wheelPowerWatts], [gradePct], and [surface].
     * Used by the Simulator to translate virtual rider power into realistic ground speed, and internally
     * to model gravity-assisted downhill acceleration up to each surface's technical speed ceiling.
     */
    fun solveGroundSpeedMps(
        wheelPowerWatts: Double,
        gradePct: Double,
        surface: SurfaceProfile,
        riderWeightKg: Double = DEFAULT_RIDER_WEIGHT_KG,
        descentSkillFactor: Double = 1.0,
    ): Double {
        val totalMassKg = riderWeightKg.coerceIn(45.0, 130.0) + surface.bikeAndGearMassKg
        val clampedGrade = gradePct.coerceIn(-22.0, 25.0)
        val theta = atan(clampedGrade / 100.0)
        val gravityAndRollForce = totalMassKg * GRAVITY * (sin(theta) + surface.crr * cos(theta))

        val effectiveDescentPowerFactor = when {
            clampedGrade >= -1.5 -> 1.0
            clampedGrade >= -4.5 -> 0.72
            else -> 0.35
        }
        val appliedWheelWatts = (wheelPowerWatts * effectiveDescentPowerFactor).coerceAtLeast(0.0)

        var lo = 1.10
        var hi = 22.50
        repeat(18) {
            val mid = (lo + hi) * 0.5
            val reqWatts = mid * gravityAndRollForce + surface.halfRhoCdA * mid * mid * mid
            if (reqWatts < appliedWheelWatts) {
                lo = mid
            } else {
                hi = mid
            }
        }
        val steadySpeedMps = (lo + hi) * 0.5
        val afterTrailLossMps = steadySpeedMps * (1.0 - surface.trailMicroLoss)

        val skill = descentSkillFactor.coerceIn(0.60, 1.15)
        val maxSafeMps = (surface.maxSafeDescentKmh / 3.6) * skill
        val steepTechMps = (surface.steepTechnicalDescentKmh / 3.6) * skill

        return when {
            clampedGrade >= -5.5 -> afterTrailLossMps.coerceIn(MIN_CLIMB_SPEED_MPS, maxSafeMps)
            else -> {
                val t = ((abs(clampedGrade) - 5.5) / 6.0).coerceIn(0.0, 1.0)
                val techCapMps = maxSafeMps * (1.0 - t) + steepTechMps * t
                afterTrailLossMps.coerceIn(MIN_CLIMB_SPEED_MPS, techCapMps)
            }
        }
    }

    /**
     * Infers steady wheel power (Watts) implied by an observed [groundSpeedMps] at [gradePct] on [surface].
     */
    fun inferWheelPowerWatts(
        groundSpeedMps: Double,
        gradePct: Double,
        surface: SurfaceProfile,
        riderWeightKg: Double = DEFAULT_RIDER_WEIGHT_KG,
    ): Double {
        val totalMassKg = riderWeightKg.coerceIn(45.0, 130.0) + surface.bikeAndGearMassKg
        val steadyV = (groundSpeedMps / (1.0 - surface.trailMicroLoss).coerceAtLeast(0.75))
            .coerceIn(1.0, 18.0)
        val theta = atan(gradePct.coerceIn(-10.0, 22.0) / 100.0)
        val force = totalMassKg * GRAVITY * (sin(theta) + surface.crr * cos(theta))
        return (steadyV * force + surface.halfRhoCdA * steadyV * steadyV * steadyV)
            .coerceIn(35.0, 900.0)
    }

    /**
     * Computes observed VAM (m/h on an 8% reference climb) corresponding to a given [wheelPowerWatts] on [surface].
     */
    fun vamMhFromWheelPower(
        wheelPowerWatts: Double,
        surface: SurfaceProfile,
        riderWeightKg: Double = DEFAULT_RIDER_WEIGHT_KG,
    ): Double {
        val speedAt8PctMps = solveGroundSpeedMps(
            wheelPowerWatts = wheelPowerWatts,
            gradePct = REF_VAM_GRADE * 100.0,
            surface = surface,
            riderWeightKg = riderWeightKg,
        )
        val sinTheta = sin(atan(REF_VAM_GRADE))
        return (speedAt8PctMps * sinTheta * 3600.0).coerceIn(MIN_VAM_MH, MAX_VAM_MH)
    }

    /**
     * Relative speed multiplier helper for unit checks.
     */
    fun speedFactorForGrade(
        gradePct: Double,
        surface: SurfaceProfile = SurfaceProfile.GRAVEL,
        flatSpeedMps: Double = 25.0 / 3.6,
    ): Double {
        val baseFlat = flatSpeedMps.coerceIn(MIN_FLAT_PACE_MPS, MAX_FLAT_PACE_MPS)
        val segSpeed = segmentSpeedMps(
            flatSpeedMps = baseFlat,
            gradePct = gradePct,
            surface = surface,
            vamMh = surface.defaultVamMh,
        )
        return (segSpeed / baseFlat).coerceIn(0.16, 1.85)
    }

    /**
     * Extrapolates ground speed (m/s) for a segment of [gradePct] from historical [flatSpeedMps] and [vamMh]:
     * - Uphill (`> +0.25%`): Quadrature blend of horizontal pace (`1 / V_llano`) and vertical ascent capacity (`VAM`),
     *   matching `flatSpeedMps` at `0%` and `vamMh` at `+8%` without letting peloton drafting inflate climb speed.
     * - Flat & Downhill (`<= +0.25%`): Anchored to `flatSpeedMps` with gravity assist capped by surface technical limits.
     */
    fun segmentSpeedMps(
        flatSpeedMps: Double,
        gradePct: Double,
        surface: SurfaceProfile = SurfaceProfile.GRAVEL,
        vamMh: Double? = null,
        climbWheelWkg: Double? = null,
        descentSkillFactor: Double = 1.0,
        riderWeightKg: Double = DEFAULT_RIDER_WEIGHT_KG,
    ): Double {
        val cleanFlat = flatSpeedMps.coerceIn(MIN_FLAT_PACE_MPS, MAX_FLAT_PACE_MPS)
        val totalMassKg = riderWeightKg.coerceIn(45.0, 130.0) + surface.bikeAndGearMassKg

        val resolvedVamMh = when {
            vamMh != null && vamMh > 100.0 -> vamMh.coerceIn(MIN_VAM_MH, MAX_VAM_MH)
            climbWheelWkg != null && climbWheelWkg > 0.5 -> {
                vamMhFromWheelPower(climbWheelWkg * totalMassKg, surface, riderWeightKg)
            }
            else -> surface.defaultVamMh
        }

        val clampedGrade = gradePct.coerceIn(-22.0, 25.0)
        return if (clampedGrade > 0.25) {
            // Convert 8%-reference VAM into pure vertical velocity component v_vert (m/s)
            val sinRef = sin(atan(REF_VAM_GRADE))
            val refVamMps = resolvedVamMh / 3600.0
            val horizShareAtRef = (refVamMps / (sinRef * cleanFlat)).coerceIn(0.0, 0.78)
            val vVertMps = (refVamMps / sqrt((1.0 - horizShareAtRef * horizShareAtRef).coerceAtLeast(0.35)))
                .coerceAtLeast(0.08)

            val sinGrade = sin(atan(clampedGrade / 100.0)).coerceAtLeast(0.0)
            val dtHorizPerMeter = 1.0 / cleanFlat
            val dtVertPerMeter = sinGrade / vVertMps
            val combinedSecPerMeter = sqrt(dtHorizPerMeter * dtHorizPerMeter + dtVertPerMeter * dtVertPerMeter)
            val maxSafeMps = (surface.maxSafeDescentKmh / 3.6) * descentSkillFactor.coerceIn(0.60, 1.15)
            (1.0 / combinedSecPerMeter).coerceIn(MIN_CLIMB_SPEED_MPS, maxSafeMps)
        } else {
            val flatImpliedWatts = inferWheelPowerWatts(cleanFlat, 0.0, surface, riderWeightKg)
            solveGroundSpeedMps(
                wheelPowerWatts = flatImpliedWatts,
                gradePct = clampedGrade,
                surface = surface,
                riderWeightKg = riderWeightKg,
                descentSkillFactor = descentSkillFactor,
            )
        }
    }

    /**
     * Estimates remaining ETE (seconds) from `fromDistM` to `toDistM` by extrapolating historical
     * [flatSpeedMps] and [vamMh] across the remaining elevation profile.
     */
    fun estimateEteSeconds(
        fromDistM: Double,
        toDistM: Double,
        flatSpeedMps: Double,
        elevationProfile: List<ElevationSample>,
        climbs: List<ClimbSegment> = emptyList(),
        rejoinExtraDistanceM: Double = 0.0,
        surface: SurfaceProfile = SurfaceProfile.GRAVEL,
        vamMh: Double? = null,
        climbWheelWkg: Double? = null,
        descentSkillFactor: Double = 1.0,
        riderWeightKg: Double = DEFAULT_RIDER_WEIGHT_KG,
        upcomingHazardCount: Int = 0,
    ): Long {
        val remainingRouteDistM = (toDistM - fromDistM).coerceAtLeast(0.0)
        val cleanFlatMps = flatSpeedMps.coerceIn(MIN_FLAT_PACE_MPS, MAX_FLAT_PACE_MPS)
        val rejoinSeconds = if (rejoinExtraDistanceM > 0.0) rejoinExtraDistanceM / cleanFlatMps else 0.0

        if (remainingRouteDistM < 1.0) {
            return rejoinSeconds.roundToLong()
        }

        val baseRouteSeconds = when {
            elevationProfile.size >= 2 -> integrateOverElevationProfile(
                fromDistM = fromDistM,
                toDistM = toDistM,
                flatSpeedMps = cleanFlatMps,
                profile = elevationProfile,
                surface = surface,
                vamMh = vamMh,
                climbWheelWkg = climbWheelWkg,
                descentSkillFactor = descentSkillFactor,
                riderWeightKg = riderWeightKg,
            )
            climbs.isNotEmpty() -> integrateOverClimbsFallback(
                fromDistM = fromDistM,
                toDistM = toDistM,
                flatSpeedMps = cleanFlatMps,
                climbs = climbs,
                surface = surface,
                vamMh = vamMh,
                climbWheelWkg = climbWheelWkg,
                descentSkillFactor = descentSkillFactor,
                riderWeightKg = riderWeightKg,
            )
            else -> remainingRouteDistM / cleanFlatMps
        }

        val hazardSeconds = (upcomingHazardCount.coerceAtLeast(0) * surface.hazardPenaltySeconds).toDouble()
        val rawTotalSec = baseRouteSeconds + hazardSeconds + rejoinSeconds

        // Gentle endurance pacing drift for remaining durations > 90 minutes (+2.5% per extra hour, max +7.5%)
        val hoursBeyond90m = ((rawTotalSec - 5_400.0) / 3_600.0).coerceAtLeast(0.0)
        val enduranceMultiplier = (1.0 + hoursBeyond90m * 0.025).coerceAtMost(1.075)

        return (rawTotalSec * enduranceMultiplier).roundToLong().coerceAtLeast(0L)
    }

    private fun integrateOverElevationProfile(
        fromDistM: Double,
        toDistM: Double,
        flatSpeedMps: Double,
        profile: List<ElevationSample>,
        surface: SurfaceProfile,
        vamMh: Double?,
        climbWheelWkg: Double?,
        descentSkillFactor: Double,
        riderWeightKg: Double,
    ): Double {
        var cursor = fromDistM
        var prevElev = interpolateElevationAt(profile, cursor)
        var totalSec = 0.0

        while (cursor < toDistM) {
            val next = min(cursor + MICRO_SEGMENT_METERS, toDistM)
            val segLen = next - cursor
            if (segLen <= 0.01) break

            val nextElev = interpolateElevationAt(profile, next)
            val gradePct = ((nextElev - prevElev) / segLen) * 100.0
            val speed = segmentSpeedMps(
                flatSpeedMps = flatSpeedMps,
                gradePct = gradePct,
                surface = surface,
                vamMh = vamMh,
                climbWheelWkg = climbWheelWkg,
                descentSkillFactor = descentSkillFactor,
                riderWeightKg = riderWeightKg,
            )
            totalSec += segLen / speed

            cursor = next
            prevElev = nextElev
        }
        return totalSec
    }

    private fun integrateOverClimbsFallback(
        fromDistM: Double,
        toDistM: Double,
        flatSpeedMps: Double,
        climbs: List<ClimbSegment>,
        surface: SurfaceProfile,
        vamMh: Double?,
        climbWheelWkg: Double?,
        descentSkillFactor: Double,
        riderWeightKg: Double,
    ): Double {
        var climbMetersCovered = 0.0
        var climbSeconds = 0.0
        for (climb in climbs) {
            val overlapStart = max(fromDistM, climb.startDistanceM)
            val overlapEnd = min(toDistM, climb.summitDistanceM)
            if (overlapEnd > overlapStart) {
                val len = overlapEnd - overlapStart
                climbMetersCovered += len
                climbSeconds += len / segmentSpeedMps(
                    flatSpeedMps = flatSpeedMps,
                    gradePct = climb.gradePct,
                    surface = surface,
                    vamMh = vamMh,
                    climbWheelWkg = climbWheelWkg,
                    descentSkillFactor = descentSkillFactor,
                    riderWeightKg = riderWeightKg,
                )
            }
        }
        val nonClimbMeters = (toDistM - fromDistM - climbMetersCovered).coerceAtLeast(0.0)
        return climbSeconds + (nonClimbMeters / flatSpeedMps)
    }

    fun interpolateElevationAt(profile: List<ElevationSample>, distanceM: Double): Double {
        if (profile.isEmpty()) return 0.0
        if (distanceM <= profile.first().distanceM) return profile.first().elevationM
        if (distanceM >= profile.last().distanceM) return profile.last().elevationM

        var low = 0
        var high = profile.lastIndex
        while (low <= high) {
            val mid = (low + high) ushr 1
            val midDist = profile[mid].distanceM
            when {
                midDist < distanceM -> low = mid + 1
                midDist > distanceM -> high = mid - 1
                else -> return profile[mid].elevationM
            }
        }
        val rightIdx = low.coerceIn(1, profile.lastIndex)
        val left = profile[rightIdx - 1]
        val right = profile[rightIdx]
        val span = right.distanceM - left.distanceM
        if (span <= 1e-6) return left.elevationM
        val ratio = ((distanceM - left.distanceM) / span).coerceIn(0.0, 1.0)
        return left.elevationM + ratio * (right.elevationM - left.elevationM)
    }

    /**
     * Cumulative Distance- & Ascent-Weighted Historical Pace & VAM Estimator.
     *
     * Always looks backward at accumulated meters & vertical ascent (anchored by an initial profile prior)
     * and extrapolates forward — never reacting abruptly to instantaneous power changes, short sprints, or
     * a 50 m river ford.
     */
    class FlatPaceEstimator(
        initialFlatSpeedKmh: Double = 25.0,
        initialSurface: SurfaceProfile = SurfaceProfile.GRAVEL,
    ) {
        companion object {
            // Virtual initial anchors (~25-30 min of flat riding, ~33 min of climbing, ~10 min of descending)
            private const val PRIOR_FLAT_DIST_M = 12_000.0
            private const val PRIOR_VAM_GAIN_M = 480.0
            private const val PRIOR_DESCENT_DIST_M = 6_000.0

            // Long cumulative recency decay scales (~60-70 min rolling memory)
            private const val FLAT_DECAY_DIST_M = 28_000.0
            private const val VAM_DECAY_GAIN_M = 900.0
            private const val DESCENT_DECAY_DIST_M = 16_000.0
        }

        var surface: SurfaceProfile = initialSurface
            private set

        var riderWeightKg: Double = DEFAULT_RIDER_WEIGHT_KG
            private set

        private var seedFlatMps: Double = (initialFlatSpeedKmh / 3.6).coerceIn(MIN_FLAT_PACE_MPS, MAX_FLAT_PACE_MPS)
        private var seedVamMh: Double = initialSurface.defaultVamMh

        private var cumFlatDistM: Double = PRIOR_FLAT_DIST_M
        private var cumFlatTimeSec: Double = PRIOR_FLAT_DIST_M / seedFlatMps
        private var realFlatDistRiddenM: Double = 0.0

        private var cumClimbGainM: Double = PRIOR_VAM_GAIN_M
        private var cumClimbEq8TimeSec: Double = PRIOR_VAM_GAIN_M / (seedVamMh / 3600.0)
        private var realClimbGainRiddenM: Double = 0.0

        private var cumDescentDistM: Double = PRIOR_DESCENT_DIST_M
        private var cumDescentWeightedRatio: Double = PRIOR_DESCENT_DIST_M * 1.0
        private var realDescentDistRiddenM: Double = 0.0

        val flatSpeedMps: Double
            get() = (cumFlatDistM / cumFlatTimeSec.coerceAtLeast(1.0))
                .coerceIn(MIN_FLAT_PACE_MPS, MAX_FLAT_PACE_MPS)

        val flatSpeedKmh: Double
            get() = flatSpeedMps * 3.6

        val vamMh: Double
            get() {
                val rawVam = 3600.0 * (cumClimbGainM / cumClimbEq8TimeSec.coerceAtLeast(1.0))
                // Before real climbs (>= 80m gain) are ridden, if flat speed dropped significantly below seed
                // (e.g. fatigue or heavy mud/rain), gently temper VAM downward — NEVER inflate upward on peloton speed!
                val flatRatio = (flatSpeedMps / seedFlatMps.coerceAtLeast(1.0)).coerceAtMost(1.0)
                val preClimbFactor = if (realClimbGainRiddenM < 80.0 && flatRatio < 0.96) {
                    val weight = (1.0 - realClimbGainRiddenM / 80.0).coerceIn(0.0, 1.0)
                    1.0 - weight * 0.45 * (1.0 - flatRatio)
                } else {
                    1.0
                }
                return (rawVam * preClimbFactor).coerceIn(MIN_VAM_MH, MAX_VAM_MH)
            }

        val climbWheelWkg: Double
            get() {
                val totalMass = riderWeightKg + surface.bikeAndGearMassKg
                val speedAt8 = (vamMh / 3600.0) / sin(atan(REF_VAM_GRADE))
                val watts = inferWheelPowerWatts(speedAt8, REF_VAM_GRADE * 100.0, surface, riderWeightKg)
                return (watts / totalMass).coerceIn(0.9, 5.8)
            }

        val descentSkillFactor: Double
            get() = (cumDescentWeightedRatio / cumDescentDistM.coerceAtLeast(1.0))
                .coerceIn(0.60, 1.15)

        fun updateContext(
            newSurface: SurfaceProfile,
            newRiderWeightKg: Double? = null,
            riderFtpWatts: Int? = null,
        ) {
            val surfaceChanged = newSurface != surface
            surface = newSurface
            if (newRiderWeightKg != null && newRiderWeightKg in 40.0..140.0) {
                riderWeightKg = newRiderWeightKg
            }
            // Only re-seed priors before meaningful ride history has been accumulated
            if (realFlatDistRiddenM < 250.0 && realClimbGainRiddenM < 15.0) {
                if (riderFtpWatts != null && riderFtpWatts in 90..550) {
                    // Endurance race pacing prior (~78% of FTP at crank)
                    seedFromCrankPowerWatts(riderFtpWatts * 0.78, surface)
                } else if (surfaceChanged) {
                    reinitializePriors(
                        targetFlatMps = (surface.defaultFlatSeedKmh / 3.6).coerceIn(MIN_FLAT_PACE_MPS, MAX_FLAT_PACE_MPS),
                        targetVamMh = surface.defaultVamMh,
                    )
                }
            }
        }

        fun resetSeedKmh(seedKmh: Double) {
            if (realFlatDistRiddenM < 250.0) {
                val newFlatMps = (seedKmh / 3.6).coerceIn(MIN_FLAT_PACE_MPS, MAX_FLAT_PACE_MPS)
                val impliedWatts = inferWheelPowerWatts(newFlatMps, 0.0, surface, riderWeightKg)
                val newVam = vamMhFromWheelPower(impliedWatts * 0.94, surface, riderWeightKg)
                reinitializePriors(newFlatMps, newVam)
            }
        }

        /**
         * Initializes the pre-ride baseline prior from crank power (e.g. Karoo FTP endurance prior or
         * Simulator pre-start setup at t=0). Clears accumulated ride history.
         */
        fun seedFromCrankPowerWatts(crankWatts: Double, targetSurface: SurfaceProfile = surface) {
            surface = targetSurface
            val safeCrankW = crankWatts.coerceIn(60.0, 600.0)
            val wheelWatts = safeCrankW * DRIVETRAIN_EFF
            val targetFlatMps = solveGroundSpeedMps(
                wheelPowerWatts = wheelWatts,
                gradePct = 0.0,
                surface = targetSurface,
                riderWeightKg = riderWeightKg,
                descentSkillFactor = 1.0,
            ).coerceIn(MIN_FLAT_PACE_MPS, MAX_FLAT_PACE_MPS)
            val targetVam = vamMhFromWheelPower(wheelWatts, targetSurface, riderWeightKg)
            reinitializePriors(targetFlatMps, targetVam)
        }

        private fun reinitializePriors(targetFlatMps: Double, targetVamMh: Double) {
            seedFlatMps = targetFlatMps.coerceIn(MIN_FLAT_PACE_MPS, MAX_FLAT_PACE_MPS)
            seedVamMh = targetVamMh.coerceIn(MIN_VAM_MH, MAX_VAM_MH)

            cumFlatDistM = PRIOR_FLAT_DIST_M
            cumFlatTimeSec = PRIOR_FLAT_DIST_M / seedFlatMps
            realFlatDistRiddenM = 0.0

            cumClimbGainM = PRIOR_VAM_GAIN_M
            cumClimbEq8TimeSec = PRIOR_VAM_GAIN_M / (seedVamMh / 3600.0)
            realClimbGainRiddenM = 0.0

            cumDescentDistM = PRIOR_DESCENT_DIST_M
            cumDescentWeightedRatio = PRIOR_DESCENT_DIST_M * 1.0
            realDescentDistRiddenM = 0.0
        }

        /**
         * Accumulates backward-looking distance & vertical gain from real ground speed (`speedMps`) and
         * terrain gradient (`gradePct`). Instantaneous power (`W`) is intentionally unused so drafting,
         * short sprints, or brief coasting never cause erratic ETA jumps.
         */
        @Suppress("UNUSED_PARAMETER")
        fun observe(
            speedMps: Double,
            gradePct: Double,
            dtSeconds: Double,
            liveCrankWatts: Double? = null,
        ) {
            if (dtSeconds <= 0.0 || dtSeconds > 60.0) return
            if (speedMps < 1.0) return // Stationary / paused (< 3.6 km/h)

            val deltaDistM = speedMps * dtSeconds
            if (deltaDistM <= 0.05) return

            when {
                // 1. FLAT & GENTLE ROLLING (-1.8% .. +2.2%): accumulate normalized flat distance & time
                gradePct in -1.8..2.2 -> {
                    val normFactor = speedFactorForGrade(gradePct, surface, flatSpeedMps).coerceIn(0.55, 1.35)
                    val eqFlatSpeedMps = (speedMps / normFactor).coerceIn(MIN_FLAT_PACE_MPS * 0.8, MAX_FLAT_PACE_MPS)
                    val eqFlatTimeSec = deltaDistM / eqFlatSpeedMps

                    val decay = exp(-deltaDistM / FLAT_DECAY_DIST_M)
                    cumFlatDistM = cumFlatDistM * decay + deltaDistM
                    cumFlatTimeSec = cumFlatTimeSec * decay + eqFlatTimeSec
                    realFlatDistRiddenM += deltaDistM
                }

                // 2. SUSTAINED CLIMB (+2.8% .. +18.0%): accumulate vertical ascent & 8%-normalized VAM time
                gradePct in 2.8..18.0 -> {
                    val sinGrade = sin(atan(gradePct / 100.0))
                    val deltaGainM = deltaDistM * sinGrade
                    if (deltaGainM <= 0.01) return

                    // Normalize observed VAM at `gradePct` to equivalent 8% reference VAM so gentle 3.5% climbs
                    // (where rolling/aero consumes a share of power) and steep 10% climbs compare apples-to-apples
                    val expectedSpeedAtCur = segmentSpeedMps(
                        flatSpeedMps = flatSpeedMps,
                        gradePct = gradePct,
                        surface = surface,
                        vamMh = vamMh,
                        descentSkillFactor = descentSkillFactor,
                        riderWeightKg = riderWeightKg,
                    ).coerceAtLeast(1.0)
                    val expectedSpeedAt8 = segmentSpeedMps(
                        flatSpeedMps = flatSpeedMps,
                        gradePct = REF_VAM_GRADE * 100.0,
                        surface = surface,
                        vamMh = vamMh,
                        descentSkillFactor = descentSkillFactor,
                        riderWeightKg = riderWeightKg,
                    ).coerceAtLeast(1.0)

                    // Soft-clamp anaerobic surge ratio (0.55..1.35 of current sustainable VAM) so a 60s 400W attack
                    // cannot masquerade as sustainable multi-hour aerobic VAM, while sustained faster climbing adapts smoothly
                    val speedRatio = (speedMps / expectedSpeedAtCur).coerceIn(0.55, 1.35)
                    val eqSpeedAt8Mps = expectedSpeedAt8 * speedRatio
                    val eqVamAt8Mps = (eqSpeedAt8Mps * sin(atan(REF_VAM_GRADE)))
                        .coerceIn(MIN_VAM_MH / 3600.0, MAX_VAM_MH / 3600.0)
                    val eqTimeAt8Sec = deltaGainM / eqVamAt8Mps

                    val decay = exp(-deltaGainM / VAM_DECAY_GAIN_M)
                    cumClimbGainM = cumClimbGainM * decay + deltaGainM
                    cumClimbEq8TimeSec = cumClimbEq8TimeSec * decay + eqTimeAt8Sec
                    realClimbGainRiddenM += deltaGainM
                }

                // 3. DESCENT (-14.0% .. -2.2%): accumulate distance-weighted technical descent skill only
                gradePct in -14.0..-2.2 -> {
                    val nominalDescentMps = segmentSpeedMps(
                        flatSpeedMps = flatSpeedMps,
                        gradePct = gradePct,
                        surface = surface,
                        vamMh = vamMh,
                        descentSkillFactor = 1.0,
                        riderWeightKg = riderWeightKg,
                    ).coerceAtLeast(3.0)

                    val obsRatio = (speedMps / nominalDescentMps).coerceIn(0.60, 1.15)
                    val decay = exp(-deltaDistM / DESCENT_DECAY_DIST_M)
                    cumDescentDistM = cumDescentDistM * decay + deltaDistM
                    cumDescentWeightedRatio = cumDescentWeightedRatio * decay + deltaDistM * obsRatio
                    realDescentDistRiddenM += deltaDistM
                }
            }
        }
    }
}
