package com.karoocuesheetplus.data

import android.content.Context
import android.text.format.DateFormat
import com.karoocuesheetplus.domain.ClimbSegment
import com.karoocuesheetplus.domain.CueListWindowBuilder
import com.karoocuesheetplus.domain.CueSheetSnapshot
import com.karoocuesheetplus.domain.CueStrings
import com.karoocuesheetplus.domain.DemoRouteProvider
import com.karoocuesheetplus.domain.ElevationSample
import com.karoocuesheetplus.domain.GradientEtaCalculator
import com.karoocuesheetplus.domain.LatLngPoint
import com.karoocuesheetplus.domain.PolylineDecoder
import com.karoocuesheetplus.domain.RawPoiInput
import com.karoocuesheetplus.domain.RouteGeometryEngine
import com.karoocuesheetplus.domain.SurfaceProfile
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.ActiveRideProfile
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.OnGlobalPOIs
import io.hammerhead.karooext.models.OnLocationChanged
import io.hammerhead.karooext.models.OnNavigationState
import io.hammerhead.karooext.models.OnNavigationState.NavigationState
import io.hammerhead.karooext.models.OnStreamState
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.Symbol
import io.hammerhead.karooext.models.UserProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import kotlin.math.ceil

class RideNavigationRepository private constructor(private val appContext: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val settingsStore = CueSettingsStore.getInstance(appContext)
    private val flatPaceEstimator = GradientEtaCalculator.FlatPaceEstimator(
        initialFlatSpeedKmh = settingsStore.getPersistedLearnedFlatSpeedKmh()
    )

    private var karooSystem: KarooSystemService? = null
    private val consumerIds = ArrayList<String>()
    private var activeViewCount = 0
    private var tickerJob: Job? = null

    // Cached decoded geometry so we only decode polylines when the route actually changes
    private data class CachedRouteGeometry(
        val cacheKey: String,
        val routeName: String?,
        val indexedPolyline: RouteGeometryEngine.IndexedPolyline,
        val elevationProfile: List<ElevationSample>,
        val routePois: List<RawPoiInput>,
        val climbs: List<ClimbSegment>,
        val rejoinExtraDistanceM: Double,
    )

    @Volatile
    private var cachedRoute: CachedRouteGeometry? = null

    @Volatile
    private var globalPois: List<RawPoiInput> = emptyList()

    @Volatile
    private var latestDistanceToDestinationM: Double? = null

    @Volatile
    private var isRiderOnRoute: Boolean = false

    @Volatile
    private var latestRideDistanceM: Double = 0.0

    @Volatile
    private var latestLocation: LatLngPoint? = null

    @Volatile
    private var latestSpeedMps: Double = 0.0

    @Volatile
    private var latestGradePct: Double = 0.0

    @Volatile
    private var detectedKarooSurface: SurfaceProfile = SurfaceProfile.GRAVEL

    @Volatile
    private var riderWeightKg: Double = GradientEtaCalculator.DEFAULT_RIDER_WEIGHT_KG

    @Volatile
    private var riderFtpWatts: Int? = null

    @Volatile
    private var lastPaceSampleMs: Long = 0L

    @Volatile
    private var lastPaceSaveMs: Long = 0L

    @Volatile
    private var isMetricDistance: Boolean = true

    @Volatile
    private var isMetricElevation: Boolean = true

    // Touch interaction state
    @Volatile
    private var manualStartIndex: Int? = null

    @Volatile
    private var manualScrollExpiresAtMs: Long = 0L

    @Volatile
    private var selectedDetailWaypointId: String? = null

    @Volatile
    private var detailExpiresAtMs: Long = 0L

    @Volatile
    private var isDetailLocked: Boolean = false

    // Demo simulation progress (slowly advances so rider can watch POIs transition in Demo Mode)
    private var demoRiderDistanceM: Double = 6_200.0

    private val _snapshot = MutableStateFlow(CueSheetSnapshot())
    val snapshot: StateFlow<CueSheetSnapshot> = _snapshot.asStateFlow()

    init {
        scope.launch {
            settingsStore.settings.collect { newSettings ->
                val resolvedSurface = newSettings.surfacePreference.resolve(detectedKarooSurface)
                flatPaceEstimator.updateContext(resolvedSurface, riderWeightKg, riderFtpWatts)
                flatPaceEstimator.resetSeedKmh(newSettings.defaultFlatSpeedKmh)
                recomputeSnapshot()
            }
        }
    }

    @Synchronized
    fun onViewAttached() {
        activeViewCount++
        if (activeViewCount == 1) {
            startKarooConnection()
            startTicker()
        }
        recomputeSnapshot()
    }

    @Synchronized
    fun onViewDetached() {
        activeViewCount = (activeViewCount - 1).coerceAtLeast(0)
        if (activeViewCount == 0) {
            stopTicker()
            stopKarooConnection()
        }
    }

    fun scrollUp() {
        val current = _snapshot.value
        selectedDetailWaypointId = null
        detailExpiresAtMs = 0L
        val nextIdx = CueListWindowBuilder.computeNextScrollIndex(
            allPoints = current.allPoints,
            currentManualStartIndex = manualStartIndex,
            delta = -1,
        )
        manualStartIndex = nextIdx
        val timeoutSec = settingsStore.settings.value.autoReturnSeconds
        manualScrollExpiresAtMs = System.currentTimeMillis() + (timeoutSec + 2) * 1000L
        recomputeSnapshot()
    }

    fun scrollDown() {
        val current = _snapshot.value
        selectedDetailWaypointId = null
        detailExpiresAtMs = 0L
        val nextIdx = CueListWindowBuilder.computeNextScrollIndex(
            allPoints = current.allPoints,
            currentManualStartIndex = manualStartIndex,
            delta = +1,
        )
        manualStartIndex = nextIdx
        val timeoutSec = settingsStore.settings.value.autoReturnSeconds
        manualScrollExpiresAtMs = System.currentTimeMillis() + (timeoutSec + 2) * 1000L
        recomputeSnapshot()
    }

    fun selectVisibleRow(rowIndex: Int) {
        val rows = _snapshot.value.window.rows
        val point = rows.getOrNull(rowIndex) ?: return
        selectedDetailWaypointId = point.id
        isDetailLocked = false
        val timeoutSec = settingsStore.settings.value.autoReturnSeconds
        detailExpiresAtMs = System.currentTimeMillis() + timeoutSec * 1000L
        recomputeSnapshot()
    }

    fun toggleDetailLock() {
        if (selectedDetailWaypointId == null) return
        isDetailLocked = !isDetailLocked
        if (isDetailLocked) {
            detailExpiresAtMs = Long.MAX_VALUE
        } else {
            val timeoutSec = settingsStore.settings.value.autoReturnSeconds
            detailExpiresAtMs = System.currentTimeMillis() + timeoutSec * 1000L
        }
        recomputeSnapshot()
    }

    fun closeDetail() {
        if (selectedDetailWaypointId != null) {
            selectedDetailWaypointId = null
            isDetailLocked = false
            detailExpiresAtMs = 0L
            recomputeSnapshot()
        } else if (manualStartIndex != null) {
            manualStartIndex = null
            manualScrollExpiresAtMs = 0L
            recomputeSnapshot()
        }
    }

    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = scope.launch {
            while (isActive) {
                val now = System.currentTimeMillis()

                // Update cumulative distance/ascent-weighted historical pace & VAM estimator
                if (lastPaceSampleMs > 0L) {
                    val dt = (now - lastPaceSampleMs) / 1000.0
                    flatPaceEstimator.observe(latestSpeedMps, latestGradePct, dt)
                    if (latestSpeedMps > 2.0 && now - lastPaceSaveMs > 120_000L) {
                        settingsStore.saveLearnedFlatSpeedKmh(flatPaceEstimator.flatSpeedKmh)
                        lastPaceSaveMs = now
                    }
                }
                lastPaceSampleMs = now

                // Expire detail modal (unless locked) or manual scroll automatically
                if (selectedDetailWaypointId != null && !isDetailLocked && now >= detailExpiresAtMs) {
                    selectedDetailWaypointId = null
                    detailExpiresAtMs = 0L
                }
                if (manualStartIndex != null && now >= manualScrollExpiresAtMs) {
                    manualStartIndex = null
                    manualScrollExpiresAtMs = 0L
                }

                // Advance demo simulation slightly (~35 m/sec in demo so testing feels alive)
                if (settingsStore.settings.value.demoModeEnabled) {
                    demoRiderDistanceM += 18.0
                    if (demoRiderDistanceM > 43_500.0) {
                        demoRiderDistanceM = 1_500.0
                    }
                }

                recomputeSnapshot()
                val intervalSec = settingsStore.settings.value.refreshIntervalSeconds.coerceIn(1, 30)
                delay(intervalSec * 1000L)
            }
        }
    }

    private fun stopTicker() {
        tickerJob?.cancel()
        tickerJob = null
        lastPaceSampleMs = 0L
    }

    private fun startKarooConnection() {
        if (karooSystem != null) return
        val service = KarooSystemService(appContext)
        karooSystem = service
        service.connect { connected ->
            Timber.i("CueSheet+ connected to KarooSystemService: $connected")
            if (!connected) return@connect
            registerConsumers(service)
        }
    }

    private fun registerConsumers(service: KarooSystemService) {
        clearConsumers(service)

        // 1. UserProfile (Metric vs Imperial, Rider Weight, FTP)
        consumerIds += service.addConsumer<UserProfile>(UserProfile.Params) { profile ->
            isMetricDistance = profile.preferredUnit.distance == UserProfile.PreferredUnit.UnitType.METRIC
            isMetricElevation = profile.preferredUnit.elevation == UserProfile.PreferredUnit.UnitType.METRIC
            if (profile.weight in 40f..140f) {
                riderWeightKg = profile.weight.toDouble()
            }
            if (profile.ftp in 90..550) {
                riderFtpWatts = profile.ftp
            }
            val resolvedSurface = settingsStore.settings.value.surfacePreference.resolve(detectedKarooSurface)
            flatPaceEstimator.updateContext(resolvedSurface, riderWeightKg, riderFtpWatts)
            recomputeSnapshot()
        }

        // 1b. ActiveRideProfile (Zero-Config Road vs Gravel vs MTB surface auto-detection!)
        consumerIds += service.addConsumer<ActiveRideProfile>(ActiveRideProfile.Params) { active ->
            val rp = active.profile
            detectedKarooSurface = SurfaceProfile.fromKarooRideProfile(
                routingPreference = rp.routingPreference,
                activityType = rp.defaultActivityType,
                profileName = rp.name,
            )
            val resolvedSurface = settingsStore.settings.value.surfacePreference.resolve(detectedKarooSurface)
            flatPaceEstimator.updateContext(resolvedSurface, riderWeightKg, riderFtpWatts)
            recomputeSnapshot()
        }

        // 2. Active Navigation Route, POIs, Polylines & Climbs
        consumerIds += service.addConsumer<OnNavigationState>(OnNavigationState.Params) { event ->
            handleNavigationState(event.state)
            recomputeSnapshot()
        }

        // 3. Account Global POIs
        consumerIds += service.addConsumer<OnGlobalPOIs>(OnGlobalPOIs.Params) { event ->
            globalPois = event.pois.map { it.toRawPoiInput(isGlobal = true) }
            recomputeSnapshot()
        }

        // 4. Rider GPS Location (fallback projection when off-route or stationary)
        consumerIds += service.addConsumer<OnLocationChanged>(OnLocationChanged.Params) { loc ->
            latestLocation = LatLngPoint(loc.lat, loc.lng)
        }

        // 5. Distance to Destination + OnRoute flag
        consumerIds += service.addConsumer<OnStreamState>(
            OnStreamState.StartStreaming(DataType.Type.DISTANCE_TO_DESTINATION)
        ) { streamEvent ->
            val streaming = streamEvent.state as? StreamState.Streaming
            if (streaming != null) {
                val values = streaming.dataPoint.values
                latestDistanceToDestinationM = values[DataType.Field.DISTANCE_TO_DESTINATION]
                    ?: streaming.dataPoint.singleValue
                val onRouteVal = values[DataType.Field.ON_ROUTE]
                if (onRouteVal != null) {
                    isRiderOnRoute = onRouteVal >= 0.5
                }
                Timber.d("DTD stream: dtd=$latestDistanceToDestinationM, onRoute=$isRiderOnRoute")
            } else {
                latestDistanceToDestinationM = null
            }
        }

        // 5b. Cumulative Ride Distance / Odometer (m)
        consumerIds += service.addConsumer<OnStreamState>(
            OnStreamState.StartStreaming(DataType.Type.DISTANCE)
        ) { streamEvent ->
            val streaming = streamEvent.state as? StreamState.Streaming
            if (streaming != null) {
                val dist = streaming.dataPoint.values[DataType.Field.DISTANCE]
                    ?: streaming.dataPoint.singleValue
                if (dist != null && dist >= 0.0) {
                    latestRideDistanceM = dist
                }
            }
        }

        // 6. Smoothed 3s Speed (m/s)
        consumerIds += service.addConsumer<OnStreamState>(
            OnStreamState.StartStreaming(DataType.Type.SMOOTHED_3S_AVERAGE_SPEED)
        ) { streamEvent ->
            val streaming = streamEvent.state as? StreamState.Streaming
            latestSpeedMps = streaming?.dataPoint?.values?.get(DataType.Field.SMOOTHED_3S_AVERAGE_SPEED)
                ?: streaming?.dataPoint?.singleValue
                ?: 0.0
        }

        // 7. Instantaneous Elevation Grade (%)
        consumerIds += service.addConsumer<OnStreamState>(
            OnStreamState.StartStreaming(DataType.Type.ELEVATION_GRADE)
        ) { streamEvent ->
            val streaming = streamEvent.state as? StreamState.Streaming
            latestGradePct = streaming?.dataPoint?.values?.get(DataType.Field.ELEVATION_GRADE)
                ?: streaming?.dataPoint?.singleValue
                ?: 0.0
        }
    }

    private fun handleNavigationState(state: NavigationState) {
        when (state) {
            is NavigationState.Idle -> {
                cachedRoute = null
                latestDistanceToDestinationM = null
                manualStartIndex = null
                selectedDetailWaypointId = null
            }

            is NavigationState.NavigatingRoute -> {
                val key = "${state.name}_${state.routeDistance.toLong()}_${state.routePolyline.hashCode()}_${state.routeElevationPolyline?.hashCode()}_${state.pois.size}_${state.climbs.size}_${state.rejoinPolyline?.hashCode()}_${state.reversed}"
                val existing = cachedRoute
                if (existing != null && existing.cacheKey == key) return

                val decodedLatLng = PolylineDecoder.decodeLatLng(state.routePolyline, precision = 5)
                val indexed = RouteGeometryEngine.buildIndexedPolyline(decodedLatLng, state.routeDistance)
                val elevProfile = PolylineDecoder.decodeElevation(state.routeElevationPolyline, precision = 1)

                val rejoinExtraM = if (!state.rejoinPolyline.isNullOrBlank() && state.rejoinDistance != null) {
                    val rejoinPts = PolylineDecoder.decodeLatLng(state.rejoinPolyline, precision = 5)
                    RouteGeometryEngine.buildIndexedPolyline(rejoinPts).totalDistanceM
                } else {
                    0.0
                }

                cachedRoute = CachedRouteGeometry(
                    cacheKey = key,
                    routeName = state.name,
                    indexedPolyline = indexed,
                    elevationProfile = elevProfile,
                    routePois = state.pois.map { it.toRawPoiInput(isGlobal = false) },
                    climbs = state.climbs.map {
                        ClimbSegment(
                            startDistanceM = it.startDistance,
                            lengthM = it.length,
                            gradePct = it.grade,
                            totalElevationM = it.totalElevation,
                        )
                    },
                    rejoinExtraDistanceM = rejoinExtraM,
                )
            }

            is NavigationState.NavigatingToDestination -> {
                val key = "dest_${state.destination.id}_${state.polyline.hashCode()}_${state.elevationPolyline?.hashCode()}"
                val existing = cachedRoute
                if (existing != null && existing.cacheKey == key) return

                val decodedLatLng = PolylineDecoder.decodeLatLng(state.polyline, precision = 5)
                val indexed = RouteGeometryEngine.buildIndexedPolyline(decodedLatLng)
                val elevProfile = PolylineDecoder.decodeElevation(state.elevationPolyline, precision = 1)
                val destPoi = state.destination.toRawPoiInput(isGlobal = false)
                    .copy(distancesAlongRouteM = listOf(indexed.totalDistanceM))

                cachedRoute = CachedRouteGeometry(
                    cacheKey = key,
                    routeName = state.destination.name ?: CueStrings.forLocale().destinationFallback,
                    indexedPolyline = indexed,
                    elevationProfile = elevProfile,
                    routePois = listOf(destPoi),
                    climbs = state.climbs.map {
                        ClimbSegment(
                            startDistanceM = it.startDistance,
                            lengthM = it.length,
                            gradePct = it.grade,
                            totalElevationM = it.totalElevation,
                        )
                    },
                    rejoinExtraDistanceM = 0.0,
                )
            }
        }
    }

    private fun Symbol.POI.toRawPoiInput(isGlobal: Boolean): RawPoiInput {
        return RawPoiInput(
            id = id,
            lat = lat,
            lng = lng,
            type = type,
            name = name,
            distancesAlongRouteM = distancesAlongRoute,
            isGlobal = isGlobal,
        )
    }

    private fun recomputeSnapshot() {
        val now = System.currentTimeMillis()
        val settings = settingsStore.settings.value
        val is24h = runCatching { DateFormat.is24HourFormat(appContext) }.getOrDefault(true)
        val detailRemSec = if (selectedDetailWaypointId != null && detailExpiresAtMs > now) {
            ceil((detailExpiresAtMs - now) / 1000.0).toInt().coerceAtLeast(1)
        } else {
            0
        }

        if (settings.demoModeEnabled) {
            _snapshot.value = DemoRouteProvider.buildDemoSnapshot(
                settings = settings,
                manualStartIndex = manualStartIndex,
                selectedDetailId = selectedDetailWaypointId,
                detailRemainingSeconds = detailRemSec,
                isDetailLocked = isDetailLocked,
                simulatedRiderDistM = demoRiderDistanceM,
                nowEpochMillis = now,
                isMetricDistance = isMetricDistance,
                isMetricElevation = isMetricElevation,
                is24HourFormat = is24h,
            )
            return
        }

        val route = cachedRoute
        val activeSurface = settings.surfacePreference.resolve(detectedKarooSurface)
        val strings = CueStrings.forLocale()
        if (route == null || route.indexedPolyline.totalDistanceM <= 1.0) {
            _snapshot.value = CueSheetSnapshot(
                hasActiveRoute = false,
                isDemoMode = false,
                learnedFlatSpeedKmh = flatPaceEstimator.flatSpeedKmh,
                learnedClimbWkg = flatPaceEstimator.climbWheelWkg,
                learnedVamMh = flatPaceEstimator.vamMh,
                activeSurface = activeSurface,
                isMetricDistance = isMetricDistance,
                isMetricElevation = isMetricElevation,
                is24HourFormat = is24h,
                themePreference = settings.themePreference,
                strings = strings,
            )
            return
        }

        val totalDistM = route.indexedPolyline.totalDistanceM
        val riderDistM = resolveRiderDistanceAlongRoute(route, totalDistM)

        val allPoints = RouteGeometryEngine.buildCuePoints(
            routeName = route.routeName,
            indexedPolyline = route.indexedPolyline,
            elevationProfile = route.elevationProfile,
            routePois = route.routePois,
            globalPois = globalPois,
            climbs = route.climbs,
            includeClimbs = settings.includeClimbs,
            includeGlobalPois = settings.includeGlobalPois,
            riderDistanceAlongRouteM = riderDistM,
            rejoinExtraDistanceM = if (isRiderOnRoute) 0.0 else route.rejoinExtraDistanceM,
            flatSpeedMps = flatPaceEstimator.flatSpeedMps,
            nowEpochMillis = now,
            surface = activeSurface,
            vamMh = flatPaceEstimator.vamMh,
            climbWheelWkg = flatPaceEstimator.climbWheelWkg,
            descentSkillFactor = flatPaceEstimator.descentSkillFactor,
            riderWeightKg = riderWeightKg,
            collapseCircuitPois = settings.collapseCircuitPois,
            strings = strings,
        )

        val window = CueListWindowBuilder.buildWindow(allPoints, manualStartIndex)
        val nextPoint = allPoints.firstOrNull { it.isNextActive } ?: allPoints.lastOrNull()
        val detailPoint = selectedDetailWaypointId?.let { id -> allPoints.firstOrNull { it.id == id } }

        _snapshot.value = CueSheetSnapshot(
            routeName = route.routeName,
            hasActiveRoute = true,
            isDemoMode = false,
            riderDistanceAlongRouteM = riderDistM,
            totalRouteDistanceM = totalDistM,
            allPoints = allPoints,
            window = window,
            nextPoint = nextPoint,
            selectedDetailPoint = detailPoint,
            detailRemainingSeconds = if (detailPoint != null) detailRemSec else 0,
            isDetailLocked = if (detailPoint != null) isDetailLocked else false,
            learnedFlatSpeedKmh = flatPaceEstimator.flatSpeedKmh,
            learnedClimbWkg = flatPaceEstimator.climbWheelWkg,
            learnedVamMh = flatPaceEstimator.vamMh,
            activeSurface = activeSurface,
            isMetricDistance = isMetricDistance,
            isMetricElevation = isMetricElevation,
            is24HourFormat = is24h,
            themePreference = settings.themePreference,
            strings = strings,
        )
    }

    private fun resolveRiderDistanceAlongRoute(route: CachedRouteGeometry, totalDistM: Double): Double {
        val dtd = latestDistanceToDestinationM
        val loc = latestLocation
        val rideDistM = latestRideDistanceM

        // If no GPS lock yet, rider is at the start of the route (0.0)
        if (loc == null && (dtd == null || dtd <= 0.0)) {
            Timber.d("resolveRiderDist: No GPS fix and no valid DTD -> 0.0")
            return 0.0
        }

        // Validate DTD:
        // A DTD near 0 (< 100m) can only indicate finished if the rider has covered substantial distance or is right at the finish point.
        val isDtdPlausible = if (dtd != null && dtd >= 0.0) {
            if (dtd < 100.0 && totalDistM > 500.0) {
                rideDistM > (totalDistM * 0.7) || (loc != null && route.indexedPolyline.points.isNotEmpty() &&
                    RouteGeometryEngine.haversineMeters(loc, route.indexedPolyline.points.last()) < 150.0)
            } else {
                dtd <= totalDistM + 1000.0
            }
        } else {
            false
        }

        if (isRiderOnRoute && isDtdPlausible && dtd != null) {
            val distAlong = (totalDistM - dtd).coerceIn(0.0, totalDistM)
            Timber.d("resolveRiderDist: OnRoute with valid DTD=$dtd -> $distAlong m (total=$totalDistM)")
            return distAlong
        }

        if (loc != null) {
            val proj = RouteGeometryEngine.projectOntoPolyline(route.indexedPolyline, loc)
            if (proj != null) {
                // If ride hasn't started yet and projected point is far along the route with large cross-track distance,
                // don't leap forward to a midpoint that happens to be near rider's home
                if (rideDistM < 200.0 && proj.distanceAlongRouteM > 500.0 && proj.crossTrackDistanceM > 100.0) {
                    Timber.d("resolveRiderDist: Pre-ride off-route near mid-route (along=${proj.distanceAlongRouteM}, cross=${proj.crossTrackDistanceM}) -> 0.0")
                    return 0.0
                }
                Timber.d("resolveRiderDist: Projected onto route: along=${proj.distanceAlongRouteM} m (cross=${proj.crossTrackDistanceM} m)")
                return proj.distanceAlongRouteM.coerceIn(0.0, totalDistM)
            }
        }

        if (isDtdPlausible && dtd != null) {
            val distAlong = (totalDistM - dtd).coerceIn(0.0, totalDistM)
            Timber.d("resolveRiderDist: Fallback DTD=$dtd -> $distAlong m")
            return distAlong
        }

        Timber.d("resolveRiderDist: Defaulting to 0.0")
        return 0.0
    }

    private fun clearConsumers(service: KarooSystemService) {
        consumerIds.forEach { id ->
            runCatching { service.removeConsumer(id) }
        }
        consumerIds.clear()
    }

    private fun stopKarooConnection() {
        karooSystem?.let { service ->
            clearConsumers(service)
            runCatching { service.disconnect() }
        }
        karooSystem = null
    }

    companion object {
        @Volatile
        private var instance: RideNavigationRepository? = null

        fun getInstance(context: Context): RideNavigationRepository {
            return instance ?: synchronized(this) {
                instance ?: RideNavigationRepository(context.applicationContext).also { instance = it }
            }
        }
    }
}
