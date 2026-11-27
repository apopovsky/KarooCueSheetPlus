package com.karoocuesheetplus.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.karoocuesheetplus.data.CueSettingsStore
import com.karoocuesheetplus.data.RideNavigationRepository
import com.karoocuesheetplus.domain.CueCategory
import com.karoocuesheetplus.domain.CueListWindowBuilder
import com.karoocuesheetplus.domain.CuePoint
import com.karoocuesheetplus.domain.CuePointMatcher
import com.karoocuesheetplus.domain.CueSheetSnapshot
import com.karoocuesheetplus.domain.CueStrings
import com.karoocuesheetplus.domain.DemoRouteProvider
import com.karoocuesheetplus.domain.ThemePreference
import com.karoocuesheetplus.render.CueSheetRenderer
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.roundToLong

class LiveCueSheetActivity : ComponentActivity() {

    private lateinit var settingsStore: CueSettingsStore
    private lateinit var repo: RideNavigationRepository
    private lateinit var canvasView: ImageView
    private lateinit var switchModePill: TextView

    private var collectorJob: Job? = null
    private var tickerJob: Job? = null

    private var reqWaypointId: String? = null
    private var reqWaypointName: String? = null
    private var reqWaypointRawName: String? = null
    private var reqWaypointDistM: Double? = null
    private var reqWaypointRemM: Double? = null
    private var reqRiderDistM: Double? = null
    private var autoCloseOnDetailExit: Boolean = true

    // Live countdown tracking for smooth 1s updates
    private var hasShownLiveDetail: Boolean = false
    private var activeLiveDetailId: String? = null
    private var liveDetailDeadlineMs: Long = 0L
    private var wasLiveDetailLocked: Boolean = false

    // Fallback state when Karoo OS navigation is idle (e.g. emulator / Settings preview)
    private var fallbackDetailOpen: Boolean = true
    private var fallbackSelectedRowId: String? = null
    private var fallbackDetailLocked: Boolean = false
    private var fallbackDeadlineMs: Long = 0L
    private var fallbackManualIndex: Int? = null
    private var fallbackFilterOpen: Boolean = false
    private var fallbackFilterScrollIndex: Int = 0
    private var fallbackOpenedAtMs: Long = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settingsStore = CueSettingsStore.getInstance(this)
        repo = RideNavigationRepository.getInstance(this)

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }

        canvasView = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            scaleType = ImageView.ScaleType.FIT_XY
            setupTouchHandling(this)
        }
        root.addView(canvasView)

        val backFab = FrameLayout(this).apply {
            val fabW = dp(52)
            val fabH = dp(50)
            layoutParams = FrameLayout.LayoutParams(fabW, fabH).apply {
                gravity = Gravity.BOTTOM or Gravity.START
                bottomMargin = dp(12)
                leftMargin = 0
            }
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#E61E293B"))
                val r = dp(25).toFloat()
                cornerRadii = floatArrayOf(0f, 0f, r, r, r, r, 0f, 0f)
                setStroke(dp(1), Color.parseColor("#38BDF8"))
            }
            isClickable = true
            isFocusable = true
            elevation = dp(8).toFloat()

            addView(TextView(context).apply {
                text = "←"
                setTextColor(Color.parseColor("#F5C518"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ).apply { rightMargin = dp(4) }
            })
            setOnClickListener {
                repo.closeDetail()
                finish()
            }
        }
        root.addView(backFab)

        switchModePill = TextView(this).apply {
            text = "☰ Cue Sheet+"
            setTextColor(Color.parseColor("#F5C518"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#E61E293B"))
                cornerRadius = dp(18).toFloat()
                setStroke(dp(1), Color.parseColor("#38BDF8"))
            }
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.END
                bottomMargin = dp(14)
                rightMargin = dp(12)
            }
            isClickable = true
            isFocusable = true
            elevation = dp(8).toFloat()
            visibility = View.GONE
            setOnClickListener {
                autoCloseOnDetailExit = false
                fallbackDetailOpen = false
                fallbackSelectedRowId = null
                fallbackDetailLocked = false
                repo.closeDetail()
                renderCurrentState()
            }
        }
        root.addView(switchModePill)

        setContentView(root)
        handleIncomingIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        repo.onViewAttached()
        collectorJob?.cancel()
        collectorJob = lifecycleScope.launch {
            repo.snapshot.collect {
                renderCurrentState()
            }
        }
        tickerJob?.cancel()
        tickerJob = lifecycleScope.launch {
            while (isActive) {
                renderCurrentState()
                delay(1_000L)
            }
        }
        renderCurrentState()
    }

    override fun onStop() {
        tickerJob?.cancel()
        tickerJob = null
        collectorJob?.cancel()
        collectorJob = null
        repo.onViewDetached()
        super.onStop()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        repo.onConfigurationChanged(newConfig)
        renderCurrentState()
    }

    private fun handleIncomingIntent(intent: Intent?) {
        val viewMode = intent?.getStringExtra(EXTRA_VIEW_MODE)
        val openDetail = intent?.action != ACTION_VIEW_CUESHEET && viewMode != "LIST"

        reqWaypointId = intent?.getStringExtra(EXTRA_WAYPOINT_ID)
        reqWaypointName = intent?.getStringExtra(EXTRA_WAYPOINT_NAME)
        reqWaypointRawName = intent?.getStringExtra(EXTRA_WAYPOINT_RAW_NAME)
        reqWaypointDistM = intent?.getDoubleExtra(EXTRA_WAYPOINT_DISTANCE_M, -1.0)?.takeIf { it >= 0.0 }
        reqWaypointRemM = intent?.getDoubleExtra(EXTRA_WAYPOINT_REMAINING_M, -1.0)?.takeIf { it >= 0.0 }
        reqRiderDistM = intent?.getDoubleExtra(EXTRA_RIDER_DISTANCE_M, -1.0)?.takeIf { it >= 0.0 }
        autoCloseOnDetailExit = (intent?.getBooleanExtra(EXTRA_AUTO_CLOSE_ACTIVITY, true) ?: true) && openDetail

        hasShownLiveDetail = false
        activeLiveDetailId = null
        liveDetailDeadlineMs = 0L
        wasLiveDetailLocked = false

        val now = System.currentTimeMillis()
        val timeoutMs = settingsStore.settings.value.autoReturnSeconds * 1000L
        fallbackDetailOpen = openDetail
        fallbackSelectedRowId = null
        fallbackDetailLocked = false
        fallbackFilterOpen = false
        fallbackFilterScrollIndex = 0
        fallbackManualIndex = null
        fallbackOpenedAtMs = now
        fallbackDeadlineMs = if (openDetail) now + timeoutMs else 0L

        if (openDetail) {
            repo.openExternalWaypointDetail(
                waypointId = reqWaypointId,
                waypointName = reqWaypointName,
                waypointRawName = reqWaypointRawName,
                distanceAlongRouteM = reqWaypointDistM,
            )
        } else {
            repo.openExternalCueSheetList(
                waypointId = reqWaypointId,
                waypointName = reqWaypointName,
                waypointRawName = reqWaypointRawName,
                distanceAlongRouteM = reqWaypointDistM,
            )
        }
    }

    private fun renderCurrentState() {
        if (isFinishing) return
        val now = System.currentTimeMillis()
        val liveSnap = repo.snapshot.value

        val displayedSnap: CueSheetSnapshot = if (liveSnap.hasActiveRoute && liveSnap.allPoints.isNotEmpty()) {
            val detail = liveSnap.selectedDetailPoint
            if (detail != null) {
                hasShownLiveDetail = true
                if (detail.id != activeLiveDetailId || (wasLiveDetailLocked && !liveSnap.isDetailLocked)) {
                    activeLiveDetailId = detail.id
                    liveDetailDeadlineMs = now + settingsStore.settings.value.autoReturnSeconds * 1000L
                }
                wasLiveDetailLocked = liveSnap.isDetailLocked
                if (liveSnap.isDetailLocked) {
                    liveSnap.copy(detailRemainingSeconds = 0)
                } else {
                    val remMs = liveDetailDeadlineMs - now
                    if (remMs <= 0L) {
                        repo.closeDetail()
                        if (autoCloseOnDetailExit) {
                            finish()
                            return
                        }
                        liveSnap.copy(selectedDetailPoint = null, detailRemainingSeconds = 0)
                    } else {
                        val remSec = ceil(remMs / 1000.0).toInt().coerceAtLeast(1)
                        liveSnap.copy(detailRemainingSeconds = remSec)
                    }
                }
            } else {
                activeLiveDetailId = null
                wasLiveDetailLocked = false
                if (hasShownLiveDetail && autoCloseOnDetailExit) {
                    finish()
                    return
                }
                liveSnap
            }
        } else {
            buildFallbackSnapshot(now) ?: return
        }

        val isNight = resolveIsNight(displayedSnap.themePreference, displayedSnap.isSystemNightMode)
        val bmp = CueSheetRenderer.renderFullPage(displayedSnap, 420, 680, isNight)
        canvasView.setImageBitmap(bmp)

        switchModePill.visibility = if (displayedSnap.selectedDetailPoint != null) View.VISIBLE else View.GONE
    }

    private fun buildFallbackSnapshot(nowMs: Long): CueSheetSnapshot? {
        val settings = settingsStore.settings.value
        val strings = CueStrings.forLocale()

        val remSec = if (fallbackDetailOpen && !fallbackDetailLocked) {
            val remMs = fallbackDeadlineMs - nowMs
            if (remMs <= 0L) {
                fallbackDetailOpen = false
                fallbackSelectedRowId = null
                if (autoCloseOnDetailExit) {
                    finish()
                    return null
                }
                0
            } else {
                ceil(remMs / 1000.0).toInt().coerceAtLeast(1)
            }
        } else {
            0
        }

        val base = DemoRouteProvider.buildDemoSnapshot(
            settings = settings,
            manualStartIndex = fallbackManualIndex,
            selectedDetailId = if (fallbackDetailOpen) fallbackSelectedRowId else null,
            detailRemainingSeconds = remSec,
            isDetailLocked = fallbackDetailLocked,
            simulatedRiderDistM = reqRiderDistM ?: 6_200.0,
            nowEpochMillis = nowMs,
            isMetricDistance = true,
            isMetricElevation = true,
            is24HourFormat = DateFormat.is24HourFormat(this),
            isFilterOpen = fallbackFilterOpen,
            filterScrollIndex = fallbackFilterScrollIndex,
            strings = strings,
        )

        if (!fallbackDetailOpen || fallbackSelectedRowId != null) {
            return base
        }

        val reqDisplayName = reqWaypointName?.trim().takeUnless { it.isNullOrEmpty() }
            ?: reqWaypointRawName?.trim().takeUnless { it.isNullOrEmpty() }

        val targetDetail: CuePoint = if (reqDisplayName != null) {
            val existingByName = base.allPoints.firstOrNull {
                it.name.equals(reqDisplayName, ignoreCase = true) ||
                    (reqWaypointRawName != null && it.name.equals(reqWaypointRawName, ignoreCase = true))
            }
            if (existingByName != null) {
                existingByName
            } else {
                val category = CueCategory.fromKarooPoiTypeAndName(null, reqWaypointRawName ?: reqDisplayName)
                val remDistM = (reqWaypointRemM ?: 2_400.0).coerceAtLeast(0.0)
                val flatMps = (settings.defaultFlatSpeedKmh / 3.6).coerceAtLeast(1.0)
                val eteSec = (remDistM / flatMps).roundToLong().coerceAtLeast(60L)
                val demoSpark = base.nextPoint?.sparklineElevationsM ?: emptyList()
                CuePoint(
                    id = reqWaypointId ?: "ext_next",
                    name = reqDisplayName,
                    subtitle = CueCategory.resolveSubtitle(category, null, reqWaypointRawName ?: reqDisplayName, strings),
                    category = category,
                    distanceAlongRouteM = reqWaypointDistM ?: ((reqRiderDistM ?: 0.0) + remDistM),
                    deltaDistanceM = remDistM,
                    isPassed = false,
                    isNextActive = true,
                    ascentFromRiderM = base.nextPoint?.ascentFromRiderM ?: 0.0,
                    descentFromRiderM = base.nextPoint?.descentFromRiderM ?: 0.0,
                    averageGradePct = base.nextPoint?.averageGradePct ?: 0.0,
                    eteSeconds = eteSec,
                    etaEpochMillis = nowMs + eteSec * 1000L,
                    sparklineElevationsM = demoSpark,
                )
            }
        } else {
            CuePointMatcher.findBestMatch(
                allPoints = base.allPoints,
                waypointId = reqWaypointId,
                waypointName = reqWaypointName,
                waypointRawName = reqWaypointRawName,
                distanceAlongRouteM = reqWaypointDistM,
            ) ?: base.nextPoint ?: base.allPoints.first()
        }

        return base.copy(
            selectedDetailPoint = targetDetail,
            detailRemainingSeconds = if (fallbackDetailLocked) 0 else remSec,
            isDetailLocked = fallbackDetailLocked,
        )
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouchHandling(view: ImageView) {
        view.setOnTouchListener { v, event ->
            if (event.action == MotionEvent.ACTION_UP && v.width > 0 && v.height > 0) {
                val relX = event.x / v.width.toFloat()
                val relY = event.y / v.height.toFloat()
                val liveSnap = repo.snapshot.value

                if (liveSnap.hasActiveRoute && liveSnap.allPoints.isNotEmpty()) {
                    handleLiveTouch(liveSnap, relX, relY)
                } else {
                    handleFallbackTouch(relX, relY)
                }
                renderCurrentState()
            }
            true
        }
    }

    private fun handleLiveTouch(snap: CueSheetSnapshot, relX: Float, relY: Float) {
        if (snap.isFilterOpen && snap.availableCategories.isNotEmpty()) {
            if (relY <= 0.11f) {
                when {
                    relX <= 0.56f -> repo.closeDetail()
                    relX <= 0.775f -> repo.scrollUp()
                    else -> repo.scrollDown()
                }
            } else {
                val rowIdx = (((relY - 0.10f) / 0.18f).toInt()).coerceIn(0, 4)
                repo.selectVisibleRow(rowIdx)
            }
            return
        }

        if (snap.selectedDetailPoint != null) {
            if (relY <= 0.12f && relX > 0.80f) {
                repo.toggleDetailLock()
                liveDetailDeadlineMs = System.currentTimeMillis() + settingsStore.settings.value.autoReturnSeconds * 1000L
            } else if (relY <= 0.12f || !snap.isDetailLocked) {
                repo.closeDetail()
                if (autoCloseOnDetailExit) {
                    finish()
                }
            }
            return
        }

        if (relY <= 0.11f) {
            when {
                relX <= 0.24f -> repo.toggleFilterMenu()
                relX <= 0.56f -> repo.closeDetail()
                relX <= 0.775f -> repo.scrollUp()
                else -> repo.scrollDown()
            }
        } else {
            val rowIdx = (((relY - 0.10f) / 0.18f).toInt()).coerceIn(0, 4)
            repo.selectVisibleRow(rowIdx)
        }
    }

    private fun handleFallbackTouch(relX: Float, relY: Float) {
        val now = System.currentTimeMillis()
        val settings = settingsStore.settings.value
        val snap = buildFallbackSnapshot(now) ?: return

        if (fallbackFilterOpen && snap.availableCategories.isNotEmpty()) {
            val maxFilterStart = (snap.availableCategories.size - 5).coerceAtLeast(0)
            if (relY <= 0.11f) {
                when {
                    relX <= 0.56f -> {
                        fallbackFilterOpen = false
                        fallbackFilterScrollIndex = 0
                    }
                    relX <= 0.775f -> {
                        fallbackFilterScrollIndex = (fallbackFilterScrollIndex - 1).coerceIn(0, maxFilterStart)
                    }
                    else -> {
                        fallbackFilterScrollIndex = (fallbackFilterScrollIndex + 1).coerceIn(0, maxFilterStart)
                    }
                }
            } else {
                val rowIdx = (((relY - 0.10f) / 0.18f).toInt()).coerceIn(0, 4)
                val effectiveStart = fallbackFilterScrollIndex.coerceIn(0, maxFilterStart)
                val tappedCat = snap.availableCategories.getOrNull(effectiveStart + rowIdx)
                if (tappedCat != null) {
                    fallbackManualIndex = null
                    settingsStore.toggleCategory(tappedCat.category)
                }
            }
            return
        }

        if (fallbackDetailOpen) {
            if (relY <= 0.12f && relX > 0.80f) {
                fallbackDetailLocked = !fallbackDetailLocked
                if (!fallbackDetailLocked) {
                    fallbackDeadlineMs = now + settings.autoReturnSeconds * 1000L
                }
            } else if (relY <= 0.12f || !fallbackDetailLocked) {
                fallbackDetailOpen = false
                fallbackSelectedRowId = null
                fallbackDetailLocked = false
                if (autoCloseOnDetailExit) {
                    finish()
                }
            }
            return
        }

        if (relY <= 0.11f) {
            when {
                relX <= 0.24f -> {
                    if (snap.availableCategories.isNotEmpty()) {
                        fallbackFilterOpen = true
                        fallbackFilterScrollIndex = 0
                    }
                }
                relX <= 0.56f -> {
                    fallbackManualIndex = null
                }
                relX <= 0.775f -> {
                    fallbackManualIndex = CueListWindowBuilder.computeNextScrollIndex(
                        snap.allPoints,
                        fallbackManualIndex,
                        -1,
                    )
                }
                else -> {
                    fallbackManualIndex = CueListWindowBuilder.computeNextScrollIndex(
                        snap.allPoints,
                        fallbackManualIndex,
                        +1,
                    )
                }
            }
        } else {
            val rowIdx = (((relY - 0.10f) / 0.18f).toInt()).coerceIn(0, 4)
            val tapped = snap.window.rows.getOrNull(rowIdx)
            if (tapped != null) {
                fallbackSelectedRowId = tapped.id
                fallbackDetailOpen = true
                fallbackDetailLocked = false
                fallbackDeadlineMs = now + settings.autoReturnSeconds * 1000L
            }
        }
    }

    private fun resolveIsNight(themePref: ThemePreference, sysNightFallback: Boolean): Boolean = when (themePref) {
        ThemePreference.DARK -> true
        ThemePreference.LIGHT -> false
        ThemePreference.AUTO -> {
            when (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) {
                Configuration.UI_MODE_NIGHT_YES -> true
                Configuration.UI_MODE_NIGHT_NO -> false
                else -> sysNightFallback
            }
        }
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).roundToInt()

    companion object {
        const val ACTION_VIEW_WAYPOINT_DETAIL = "com.karoocuesheetplus.ACTION_VIEW_WAYPOINT_DETAIL"
        const val ACTION_VIEW_CUESHEET = "com.karoocuesheetplus.ACTION_VIEW_CUESHEET"
        const val EXTRA_VIEW_MODE = "extra_view_mode"
        const val EXTRA_WAYPOINT_ID = "extra_waypoint_id"
        const val EXTRA_WAYPOINT_NAME = "extra_waypoint_name"
        const val EXTRA_WAYPOINT_RAW_NAME = "extra_waypoint_raw_name"
        const val EXTRA_WAYPOINT_DISTANCE_M = "extra_waypoint_distance_m"
        const val EXTRA_WAYPOINT_REMAINING_M = "extra_waypoint_remaining_m"
        const val EXTRA_RIDER_DISTANCE_M = "extra_rider_distance_m"
        const val EXTRA_AUTO_CLOSE_ACTIVITY = "extra_auto_close_activity"
    }
}
