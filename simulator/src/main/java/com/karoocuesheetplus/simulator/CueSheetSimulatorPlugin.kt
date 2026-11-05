package com.karoocuesheetplus.simulator

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.layout.Box
import androidx.glance.layout.Column as GlanceColumn
import androidx.glance.layout.Row as GlanceRow
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth as glanceFillMaxWidth
import androidx.glance.layout.height as glanceHeight
import androidx.glance.layout.size
import androidx.glance.layout.width
import com.itl.karoo.simulator.KarooSimulatorPlugin
import com.itl.karoo.simulator.SimulatorController
import com.itl.karoo.simulator.SimulatorRideState
import com.itl.karoo.simulator.SimulatorState
import com.karoocuesheetplus.domain.CueFormatter
import com.karoocuesheetplus.domain.CueListWindowBuilder
import com.karoocuesheetplus.domain.CueSheetSnapshot
import com.karoocuesheetplus.domain.CueStrings
import com.karoocuesheetplus.domain.GpxRouteParser
import com.karoocuesheetplus.domain.GradientEtaCalculator
import com.karoocuesheetplus.domain.ParsedGpxRoute
import com.karoocuesheetplus.domain.RouteGeometryEngine
import com.karoocuesheetplus.domain.SurfaceProfile
import com.karoocuesheetplus.domain.ThemePreference
import com.karoocuesheetplus.render.CueSheetRenderer
import java.util.Locale
import kotlin.math.roundToInt

class CueSheetSimulatorPlugin : KarooSimulatorPlugin {

    override val name: String = "Cue Sheet+"

    companion object {
        const val BUNDLED_GPX_ASSET = "COURSE_97867009.gpx"
        private const val BASE_SIM_EPOCH_MS = 1_762_344_000_000L // 09:00 AM reference
        private const val DRIVETRAIN_EFF = 0.95
    }

    private var appContext: Context? = null
    private var controllerRef: SimulatorController? = null

    private var loadedRoute by mutableStateOf<ParsedGpxRoute?>(null)
    private var simSurface by mutableStateOf(SurfaceProfile.MTB)
    private var riderDistanceM by mutableDoubleStateOf(0.0)
    private var currentSpeedKmh by mutableDoubleStateOf(0.0)
    private var currentGradePct by mutableDoubleStateOf(0.0)
    private var currentAltitudeM by mutableDoubleStateOf(0.0)

    private var isNightMode by mutableStateOf(true)
    private var includeClimbs by mutableStateOf(true)

    private var manualStartIndex: Int? = null
    private var selectedDetailId: String? = null
    private var detailRemainingSec by mutableIntStateOf(0)

    private var lastSimulatedElapsedSec: Long = 0L
    private var lastAppliedPowerWatts: Double = 200.0
    private var skipNextPhysicsStep: Boolean = false

    private val flatPaceEstimator = GradientEtaCalculator.FlatPaceEstimator(
        initialFlatSpeedKmh = SurfaceProfile.MTB.defaultFlatSeedKmh,
        initialSurface = SurfaceProfile.MTB,
    )
    private var snapshotState by mutableStateOf(CueSheetSnapshot())

    override fun onInit(context: Context) {
        appContext = context.applicationContext
        CueSheetTouchBus.listener = { cmd -> handleTouchCommand(cmd) }

        val filter = IntentFilter(CueTouchKeys.ACTION_SIM_TOUCH)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                val cmd = intent?.getStringExtra(CueTouchKeys.EXTRA_CMD) ?: return
                handleTouchCommand(cmd)
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        loadBundledRioPintoGpx(context)
    }

    private fun loadBundledRioPintoGpx(context: Context) {
        runCatching {
            context.assets.open(BUNDLED_GPX_ASSET).use { stream ->
                GpxRouteParser.parse(stream, fallbackName = "Desafío Río Pinto 2022")
            }
        }.onSuccess { parsed ->
            simSurface = SurfaceProfile.MTB
            applyLoadedRoute(parsed, startDistanceM = 0.0, powerWatts = 200.0)
            controllerRef?.logEvent("GPX: ${parsed.routeName} (${(parsed.totalDistanceM / 1000.0).roundToInt()} km, ${parsed.routePois.size} POIs)")
        }.onFailure { err ->
            controllerRef?.logEvent("GPX error: ${err.message}")
        }
    }

    private fun applyLoadedRoute(route: ParsedGpxRoute, startDistanceM: Double = 0.0, powerWatts: Double = 200.0) {
        loadedRoute = route
        riderDistanceM = startDistanceM.coerceIn(0.0, route.totalDistanceM)
        manualStartIndex = null
        selectedDetailId = null
        detailRemainingSec = 0
        lastAppliedPowerWatts = powerWatts
        // Seed initial prior strictly from the active SurfaceProfile (never jumping on instantaneous power changes!)
        flatPaceEstimator.updateContext(simSurface)
        flatPaceEstimator.resetSeedKmh(simSurface.defaultFlatSeedKmh)
        updateTerrainTelemetry(route, riderDistanceM, powerWatts)
        rebuildSnapshot(simElapsedSec = lastSimulatedElapsedSec)
    }

    private fun handleTouchCommand(cmd: String) {
        val snap = snapshotState
        when {
            cmd == "scroll_up" -> {
                selectedDetailId = null
                detailRemainingSec = 0
                manualStartIndex = CueListWindowBuilder.computeNextScrollIndex(
                    allPoints = snap.allPoints,
                    currentManualStartIndex = manualStartIndex,
                    delta = -1,
                )
                controllerRef?.logEvent("Touch ▲")
            }
            cmd == "scroll_down" -> {
                selectedDetailId = null
                detailRemainingSec = 0
                manualStartIndex = CueListWindowBuilder.computeNextScrollIndex(
                    allPoints = snap.allPoints,
                    currentManualStartIndex = manualStartIndex,
                    delta = +1,
                )
                controllerRef?.logEvent("Touch ▼")
            }
            cmd == "reset_scroll" -> {
                manualStartIndex = null
                selectedDetailId = null
                detailRemainingSec = 0
            }
            cmd == "close_detail" -> {
                selectedDetailId = null
                detailRemainingSec = 0
                controllerRef?.logEvent("Touch ←")
            }
            cmd.startsWith("row_") -> {
                val idx = cmd.removePrefix("row_").toIntOrNull() ?: return
                val tapped = snap.window.rows.getOrNull(idx) ?: return
                selectedDetailId = tapped.id
                detailRemainingSec = 10
                controllerRef?.logEvent("Detail: ${tapped.name}")
            }
        }
        refreshUiWithoutAdvancingRider()
    }

    private fun refreshUiWithoutAdvancingRider() {
        rebuildSnapshot(simElapsedSec = lastSimulatedElapsedSec)
        skipNextPhysicsStep = true
        controllerRef?.triggerTick()
    }

    override fun onTick(elapsedSeconds: Long, powerWatts: Double, rideState: SimulatorRideState) {
        val route = loadedRoute
        if (route == null) {
            lastSimulatedElapsedSec = elapsedSeconds
            return
        }

        lastAppliedPowerWatts = powerWatts

        if (skipNextPhysicsStep) {
            skipNextPhysicsStep = false
            lastSimulatedElapsedSec = elapsedSeconds
            rebuildSnapshot(simElapsedSec = elapsedSeconds)
            return
        }

        val dtSec = if (elapsedSeconds > lastSimulatedElapsedSec) {
            (elapsedSeconds - lastSimulatedElapsedSec).coerceAtMost(120L).toDouble()
        } else {
            0.0
        }
        lastSimulatedElapsedSec = elapsedSeconds

        val gradePct = GpxRouteParser.gradeAtDistance(route.elevationProfile, riderDistanceM)
        currentGradePct = gradePct
        currentAltitudeM = GradientEtaCalculator.interpolateElevationAt(route.elevationProfile, riderDistanceM)

        if (rideState == SimulatorRideState.RECORDING && dtSec > 0.0) {
            val speedMps = estimateSimulatedGroundSpeedMps(powerWatts = powerWatts, gradePct = gradePct)
            currentSpeedKmh = speedMps * 3.6
            riderDistanceM = (riderDistanceM + speedMps * dtSec).coerceIn(0.0, route.totalDistanceM)
            currentAltitudeM = GradientEtaCalculator.interpolateElevationAt(route.elevationProfile, riderDistanceM)
            currentGradePct = GpxRouteParser.gradeAtDistance(route.elevationProfile, riderDistanceM)

            // Feed ONLY real ground speed & terrain gradient into the backward-looking cumulative estimator
            flatPaceEstimator.observe(
                speedMps = speedMps,
                gradePct = gradePct,
                dtSeconds = dtSec,
            )

            if (selectedDetailId != null && detailRemainingSec > 0) {
                detailRemainingSec = (detailRemainingSec - 1).coerceAtLeast(0)
                if (detailRemainingSec == 0) {
                    selectedDetailId = null
                }
            }
        } else if (rideState != SimulatorRideState.RECORDING) {
            currentSpeedKmh = 0.0
        } else {
            // Power button clicked mid-ride (dtSec == 0): update instantaneous telemetry speed ONLY.
            // Historical ETA must NEVER jump on instantaneous power changes!
            val previewSpeedMps = estimateSimulatedGroundSpeedMps(powerWatts = powerWatts, gradePct = gradePct)
            currentSpeedKmh = previewSpeedMps * 3.6
        }

        rebuildSnapshot(simElapsedSec = elapsedSeconds)
    }

    override fun onReset() {
        lastSimulatedElapsedSec = 0L
        riderDistanceM = 0.0
        manualStartIndex = null
        selectedDetailId = null
        detailRemainingSec = 0
        flatPaceEstimator.updateContext(simSurface)
        flatPaceEstimator.resetSeedKmh(simSurface.defaultFlatSeedKmh)
        loadedRoute?.let { updateTerrainTelemetry(it, 0.0, lastAppliedPowerWatts) }
        rebuildSnapshot(simElapsedSec = 0L)
    }

    private fun updateTerrainTelemetry(route: ParsedGpxRoute, distM: Double, powerWatts: Double) {
        val grade = GpxRouteParser.gradeAtDistance(route.elevationProfile, distM)
        currentGradePct = grade
        currentAltitudeM = GradientEtaCalculator.interpolateElevationAt(route.elevationProfile, distM)
        currentSpeedKmh = estimateSimulatedGroundSpeedMps(powerWatts, grade) * 3.6
    }

    private fun estimateSimulatedGroundSpeedMps(powerWatts: Double, gradePct: Double): Double {
        if (powerWatts <= 1.0 && gradePct >= -1.2) return 0.0
        val wheelWatts = powerWatts.coerceAtLeast(0.0) * DRIVETRAIN_EFF
        return GradientEtaCalculator.solveGroundSpeedMps(
            wheelPowerWatts = wheelWatts,
            gradePct = gradePct,
            surface = simSurface,
            riderWeightKg = GradientEtaCalculator.DEFAULT_RIDER_WEIGHT_KG,
            descentSkillFactor = 1.0,
        )
    }

    private fun rebuildSnapshot(simElapsedSec: Long) {
        val route = loadedRoute
        val strings = CueStrings.forLocale()
        if (route == null || route.totalDistanceM <= 1.0) {
            snapshotState = CueSheetSnapshot(hasActiveRoute = false, strings = strings)
            return
        }

        val simNowMs = BASE_SIM_EPOCH_MS + simElapsedSec * 1_000L
        val allPoints = RouteGeometryEngine.buildCuePoints(
            routeName = route.routeName,
            indexedPolyline = route.indexedPolyline,
            elevationProfile = route.elevationProfile,
            routePois = route.routePois,
            globalPois = emptyList(),
            climbs = route.climbs,
            includeClimbs = includeClimbs,
            includeGlobalPois = false,
            riderDistanceAlongRouteM = riderDistanceM,
            rejoinExtraDistanceM = 0.0,
            flatSpeedMps = flatPaceEstimator.flatSpeedMps,
            nowEpochMillis = simNowMs,
            surface = simSurface,
            vamMh = flatPaceEstimator.vamMh,
            climbWheelWkg = flatPaceEstimator.climbWheelWkg,
            descentSkillFactor = flatPaceEstimator.descentSkillFactor,
            riderWeightKg = flatPaceEstimator.riderWeightKg,
            strings = strings,
        )

        val window = CueListWindowBuilder.buildWindow(allPoints, manualStartIndex)
        val nextPoint = allPoints.firstOrNull { it.isNextActive } ?: allPoints.lastOrNull()
        val detailPoint = selectedDetailId?.let { id -> allPoints.firstOrNull { it.id == id } }
        if (selectedDetailId != null && detailPoint == null) {
            selectedDetailId = null
            detailRemainingSec = 0
        }

        snapshotState = CueSheetSnapshot(
            routeName = route.routeName,
            hasActiveRoute = true,
            isDemoMode = false,
            riderDistanceAlongRouteM = riderDistanceM,
            totalRouteDistanceM = route.totalDistanceM,
            allPoints = allPoints,
            window = window,
            nextPoint = nextPoint,
            selectedDetailPoint = detailPoint,
            detailRemainingSeconds = if (detailPoint != null) detailRemainingSec else 0,
            learnedFlatSpeedKmh = flatPaceEstimator.flatSpeedKmh,
            learnedClimbWkg = flatPaceEstimator.climbWheelWkg,
            learnedVamMh = flatPaceEstimator.vamMh,
            activeSurface = simSurface,
            isMetricDistance = true,
            isMetricElevation = true,
            is24HourFormat = true,
            themePreference = if (isNightMode) ThemePreference.DARK else ThemePreference.LIGHT,
            strings = strings,
        )
    }

    @Composable
    override fun GlanceFieldContent(state: SimulatorState, viewSizePx: Pair<Int, Int>) {
        val snap = snapshotState
        val wPx = viewSizePx.first
        val hPx = viewSizePx.second
        val isFullScreen = hPx >= 500

        // Karoo 3 simulated density = 2.0 (320 dpi)
        val widthDp = (wPx / 2f).dp
        val heightDp = (hPx / 2f).dp

        val bitmap = if (isFullScreen) {
            CueSheetRenderer.renderFullPage(
                snapshot = snap,
                widthPx = wPx,
                heightPx = hPx,
                isNightMode = isNightMode,
            )
        } else {
            CueSheetRenderer.renderSingleField(
                snapshot = snap,
                widthPx = wPx,
                heightPx = hPx,
                isWideSlot = wPx > 300,
                isNightMode = isNightMode,
            )
        }

        Box(modifier = GlanceModifier.size(widthDp, heightDp)) {
            Image(
                provider = ImageProvider(bitmap),
                contentDescription = "Cue Sheet+",
                modifier = GlanceModifier.fillMaxSize(),
            )

            // Interactive touch hitboxes in Full-Page mode
            if (isFullScreen && snap.hasActiveRoute) {
                if (snap.selectedDetailPoint != null) {
                    val closeAction = actionRunCallback<CueSheetTouchAction>(
                        parameters = actionParametersOf(CueTouchKeys.Command to "close_detail")
                    )
                    Box(
                        modifier = GlanceModifier
                            .fillMaxSize()
                            .clickable(closeAction)
                    ) {}
                } else {
                    val headerH = (heightDp.value * 0.10f).dp
                    val rowH = (heightDp.value * 0.18f).dp
                    val titleW = (widthDp.value * 0.56f).dp
                    val btnW = (widthDp.value * 0.22f).dp

                    GlanceColumn(modifier = GlanceModifier.fillMaxSize()) {
                        GlanceRow(
                            modifier = GlanceModifier
                                .glanceFillMaxWidth()
                                .glanceHeight(headerH)
                        ) {
                            Box(
                                modifier = GlanceModifier
                                    .width(titleW)
                                    .fillMaxHeight()
                                    .clickable(
                                        actionRunCallback<CueSheetTouchAction>(
                                            actionParametersOf(CueTouchKeys.Command to "reset_scroll")
                                        )
                                    )
                            ) {}
                            Box(
                                modifier = GlanceModifier
                                    .width(btnW)
                                    .fillMaxHeight()
                                    .clickable(
                                        actionRunCallback<CueSheetTouchAction>(
                                            actionParametersOf(CueTouchKeys.Command to "scroll_up")
                                        )
                                    )
                            ) {}
                            Box(
                                modifier = GlanceModifier
                                    .width(btnW)
                                    .fillMaxHeight()
                                    .clickable(
                                        actionRunCallback<CueSheetTouchAction>(
                                            actionParametersOf(CueTouchKeys.Command to "scroll_down")
                                        )
                                    )
                            ) {}
                        }

                        val rowCount = snap.window.rows.size.coerceAtMost(5)
                        for (rowIdx in 0 until rowCount) {
                            Box(
                                modifier = GlanceModifier
                                    .glanceFillMaxWidth()
                                    .glanceHeight(rowH)
                                    .clickable(
                                        actionRunCallback<CueSheetTouchAction>(
                                            actionParametersOf(CueTouchKeys.Command to "row_$rowIdx")
                                        )
                                    )
                            ) {}
                        }
                    }
                }
            }
        }
    }

    @Composable
    override fun ExtraControls(simulatorState: SimulatorState, controller: SimulatorController) {
        controllerRef = controller
        val context = LocalContext.current
        val route = loadedRoute
        val snap = snapshotState
        val strings = snap.strings
        val isEs = strings.langCode == "es"

        val gpxLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocument()
        ) { uri ->
            if (uri != null) {
                var displayName = if (isEs) "Ruta GPX" else "GPX Route"
                runCatching {
                    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                        val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (nameIdx >= 0 && cursor.moveToFirst()) {
                            displayName = cursor.getString(nameIdx).removeSuffix(".gpx")
                        }
                    }
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        GpxRouteParser.parse(stream, fallbackName = displayName)
                    }
                }.onSuccess { parsed ->
                    if (parsed != null && parsed.totalDistanceM > 10.0) {
                        applyLoadedRoute(parsed, startDistanceM = 0.0, powerWatts = simulatorState.currentPowerWatts)
                        controller.logEvent("GPX: ${parsed.routeName} (${parsed.routePois.size} POIs)")
                        refreshUiWithoutAdvancingRider()
                    }
                }.onFailure { err ->
                    controller.logEvent("GPX error: ${err.message}")
                }
            }
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1A222B)),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Row 1: Route header + Day/Night + Climbs
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = route?.routeName ?: strings.noActiveRouteTitle,
                            fontSize = 14.sp,
                            color = Color.White,
                        )
                        val totalKm = (route?.totalDistanceM ?: 0.0) / 1000.0
                        val climbsWord = if (isEs) "puertos" else "climbs"
                        Text(
                            text = String.format(
                                Locale.US,
                                "%.1f km • %d POIs • %d %s",
                                totalKm,
                                route?.routePois?.size ?: 0,
                                route?.climbs?.size ?: 0,
                                climbsWord,
                            ),
                            fontSize = 11.sp,
                            color = Color(0xFF94A3B8),
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        OutlinedButton(
                            onClick = {
                                isNightMode = !isNightMode
                                refreshUiWithoutAdvancingRider()
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            modifier = Modifier.height(30.dp),
                        ) {
                            Text(
                                text = if (isNightMode) {
                                    if (isEs) "☀ Día" else "☀ Day"
                                } else {
                                    if (isEs) "🌙 Noche" else "🌙 Night"
                                },
                                fontSize = 11.sp,
                                color = Color.White,
                            )
                        }
                        OutlinedButton(
                            onClick = {
                                includeClimbs = !includeClimbs
                                refreshUiWithoutAdvancingRider()
                            },
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = if (includeClimbs) Color(0xFF2D1E12) else Color.Transparent,
                            ),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            modifier = Modifier.height(30.dp),
                        ) {
                            Text(
                                text = if (includeClimbs) {
                                    if (isEs) "⛰ Cimas ON" else "⛰ Climbs ON"
                                } else {
                                    if (isEs) "⛰ Cimas OFF" else "⛰ Climbs OFF"
                                },
                                fontSize = 11.sp,
                                color = if (includeClimbs) Color(0xFFF97316) else Color.LightGray,
                            )
                        }
                    }
                }

                // Row 2: Bike / Terrain Physics Selector (Road Aero vs Gravel vs MTB Ripio vs MTB Mud/Tech)
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = if (isEs) "Bici y Terreno simulado:" else "Simulated Bike & Terrain:",
                        fontSize = 11.sp,
                        color = Color(0xFF94A3B8),
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        val surfaceOptions = listOf(
                            SurfaceProfile.MTB to ("🚵 " + strings.surfaceMtb),
                            SurfaceProfile.MTB_TECHNICAL to ("🪨 " + strings.surfaceMtbTech),
                            SurfaceProfile.GRAVEL to ("🌾 " + strings.surfaceGravel),
                            SurfaceProfile.ROAD to ("🚴 " + strings.surfaceRoad),
                        )
                        surfaceOptions.forEach { (surf, label) ->
                            FilterChip(
                                selected = simSurface == surf,
                                onClick = {
                                    simSurface = surf
                                    flatPaceEstimator.updateContext(surf)
                                    flatPaceEstimator.resetSeedKmh(surf.defaultFlatSeedKmh)
                                    route?.let { updateTerrainTelemetry(it, riderDistanceM, simulatorState.currentPowerWatts) }
                                    controller.logEvent("Surface → $label")
                                    refreshUiWithoutAdvancingRider()
                                },
                                label = { Text(label, fontSize = 11.sp) },
                            )
                        }
                    }
                }

                // Row 3: Route Progress Scrubber Slider (km 0.0 .. totalKm)
                if (route != null && route.totalDistanceM > 100.0) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = String.format(
                                    Locale.US,
                                    if (isEs) "Posición: %.1f / %.1f km" else "Position: %.1f / %.1f km",
                                    riderDistanceM / 1000.0,
                                    route.totalDistanceM / 1000.0,
                                ),
                                fontSize = 12.sp,
                                color = Color(0xFF38BDF8),
                            )
                            Text(
                                text = String.format(
                                    Locale.US,
                                    "Alt %d m • %+.1f%% • %.1f km/h",
                                    currentAltitudeM.roundToInt(),
                                    currentGradePct,
                                    currentSpeedKmh,
                                ),
                                fontSize = 12.sp,
                                color = Color(0xFFFACC15),
                            )
                        }
                        Slider(
                            value = riderDistanceM.toFloat(),
                            onValueChange = { newDist ->
                                riderDistanceM = newDist.toDouble().coerceIn(0.0, route.totalDistanceM)
                                updateTerrainTelemetry(route, riderDistanceM, simulatorState.currentPowerWatts)
                                refreshUiWithoutAdvancingRider()
                            },
                            valueRange = 0f..route.totalDistanceM.toFloat(),
                        )
                    }

                    // Quick Jump presets along the route + Open External GPX
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        val totalM = route.totalDistanceM
                        val presets = if (isEs) {
                            listOf(
                                "Largada (0k)" to 0.0,
                                "Vados (14k)" to (totalM * 0.17),
                                "Subida (44k)" to (totalM * 0.52),
                                "Mirador (55k)" to (totalM * 0.65),
                                "Final (78k)" to (totalM * 0.92),
                            )
                        } else {
                            listOf(
                                "Start (0k)" to 0.0,
                                "Fords (14k)" to (totalM * 0.17),
                                "Climb (44k)" to (totalM * 0.52),
                                "Summit (55k)" to (totalM * 0.65),
                                "Finish (78k)" to (totalM * 0.92),
                            )
                        }
                        presets.forEach { (label, targetM) ->
                            AssistChip(
                                onClick = {
                                    riderDistanceM = targetM.coerceIn(0.0, totalM)
                                    manualStartIndex = null
                                    selectedDetailId = null
                                    updateTerrainTelemetry(route, riderDistanceM, simulatorState.currentPowerWatts)
                                    refreshUiWithoutAdvancingRider()
                                },
                                label = { Text(label, fontSize = 11.sp) },
                            )
                        }
                        AssistChip(
                            onClick = { gpxLauncher.launch(arrayOf("*/*")) },
                            label = { Text(if (isEs) "📂 Otro GPX" else "📂 Load GPX", fontSize = 11.sp) },
                        )
                    }
                }

                // Row 4: Direct Screen Navigation Controls
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    OutlinedButton(
                        onClick = { handleTouchCommand("scroll_up") },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(if (isEs) "▲ Subir" else "▲ Up")
                    }
                    OutlinedButton(
                        onClick = { handleTouchCommand("scroll_down") },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(if (isEs) "▼ Bajar" else "▼ Down")
                    }
                    if (snap.selectedDetailPoint != null) {
                        Button(
                            onClick = { handleTouchCommand("close_detail") },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                            modifier = Modifier.weight(1.4f),
                        ) {
                            Text(if (isEs) "← Cerrar" else "← Back")
                        }
                    } else {
                        Button(
                            onClick = {
                                val activeRowIdx = snap.window.rows.indexOfFirst { it.isNextActive }
                                    .let { if (it >= 0) it else 0 }
                                handleTouchCommand("row_$activeRowIdx")
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                            modifier = Modifier.weight(1.4f),
                        ) {
                            Text(if (isEs) "🔍 Detalle" else "🔍 Detail")
                        }
                    }
                }
            }
        }
    }

    override fun getTelemetrySummary(): String {
        val snap = snapshotState
        val isEs = snap.strings.langCode == "es"
        val finishRemSec = snap.allPoints.lastOrNull()?.eteSeconds ?: 0L
        val totalProjectedSec = lastSimulatedElapsedSec + finishRemSec
        val totalProjectedStr = CueFormatter.formatEte(totalProjectedSec)

        return String.format(
            Locale.US,
            if (isEs) {
                "Perfil: %s | V_llano acum: %.1f km/h | VAM acum: %.0f m/h | Total proy: %s"
            } else {
                "Profile: %s | Avg Flat: %.1f km/h | Avg VAM: %.0f m/h | Projected total: %s"
            },
            snap.strings.surfaceShortLabel(simSurface),
            snap.learnedFlatSpeedKmh,
            snap.learnedVamMh,
            totalProjectedStr,
        )
    }
}
