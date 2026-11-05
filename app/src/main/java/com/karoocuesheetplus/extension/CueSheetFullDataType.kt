package com.karoocuesheetplus.extension

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.view.View
import android.widget.RemoteViews
import com.karoocuesheetplus.R
import com.karoocuesheetplus.data.CueSettingsStore
import com.karoocuesheetplus.data.RideNavigationRepository
import com.karoocuesheetplus.domain.CueSheetSnapshot
import com.karoocuesheetplus.domain.DemoRouteProvider
import com.karoocuesheetplus.domain.ThemePreference
import com.karoocuesheetplus.render.CueSheetRenderer
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.UpdateGraphicConfig
import io.hammerhead.karooext.models.ViewConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.min
import kotlin.math.roundToInt

class CueSheetFullDataType(extension: String) : DataTypeImpl(extension, TYPE_ID) {

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        emitter.onNext(UpdateGraphicConfig(showHeader = false))

        val (targetW, targetH) = computeRenderDimensions(config.viewSize, defaultW = 480, defaultH = 740)

        if (config.preview) {
            val settings = CueSettingsStore.getInstance(context).settings.value
            val demoSnap = DemoRouteProvider.buildDemoSnapshot(
                settings = settings,
                manualStartIndex = 0, // Show passed ✓ row + active row + upcoming + pinned Meta in Page Preview!
                selectedDetailId = null,
                detailRemainingSeconds = 0,
            )
            emitter.updateView(buildRemoteViews(context, demoSnap, targetW, targetH))
            return
        }

        val repo = RideNavigationRepository.getInstance(context)
        val viewScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        repo.onViewAttached()

        viewScope.launch {
            repo.snapshot.collect { snap ->
                val rv = buildRemoteViews(context, snap, targetW, targetH)
                emitter.updateView(rv)
            }
        }

        emitter.setCancellable {
            viewScope.cancel()
            repo.onViewDetached()
        }
    }

    private fun buildRemoteViews(
        context: Context,
        snapshot: CueSheetSnapshot,
        widthPx: Int,
        heightPx: Int,
    ): RemoteViews {
        return when (snapshot.themePreference) {
            ThemePreference.DARK -> createSingleThemeRemoteViews(context, snapshot, widthPx, heightPx, isNight = true)
            ThemePreference.LIGHT -> createSingleThemeRemoteViews(context, snapshot, widthPx, heightPx, isNight = false)
            ThemePreference.AUTO -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val lightRv = createSingleThemeRemoteViews(context, snapshot, widthPx, heightPx, isNight = false)
                    val darkRv = createSingleThemeRemoteViews(context, snapshot, widthPx, heightPx, isNight = true)
                    RemoteViews(lightRv, darkRv)
                } else {
                    val sysNight = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                        Configuration.UI_MODE_NIGHT_YES
                    createSingleThemeRemoteViews(context, snapshot, widthPx, heightPx, isNight = sysNight)
                }
            }
        }
    }

    private fun createSingleThemeRemoteViews(
        context: Context,
        snapshot: CueSheetSnapshot,
        widthPx: Int,
        heightPx: Int,
        isNight: Boolean,
    ): RemoteViews {
        val rv = RemoteViews(context.packageName, R.layout.remote_cuesheet_full)
        val bmp = CueSheetRenderer.renderFullPage(snapshot, widthPx, heightPx, isNight)
        rv.setImageViewBitmap(R.id.img_cuesheet_canvas, bmp)

        val isDetailOpen = snapshot.selectedDetailPoint != null
        rv.setViewVisibility(R.id.overlay_list_touch, if (isDetailOpen) View.GONE else View.VISIBLE)
        rv.setViewVisibility(R.id.overlay_detail_touch, if (isDetailOpen) View.VISIBLE else View.GONE)

        if (isDetailOpen) {
            rv.setOnClickPendingIntent(
                R.id.touch_close_detail,
                CueSheetActionReceiver.closeDetailPendingIntent(context)
            )
            rv.setOnClickPendingIntent(
                R.id.touch_lock_detail,
                CueSheetActionReceiver.toggleDetailLockPendingIntent(context)
            )
            val bodyIntent = if (!snapshot.isDetailLocked) {
                CueSheetActionReceiver.closeDetailPendingIntent(context)
            } else {
                null
            }
            rv.setOnClickPendingIntent(R.id.touch_detail_body, bodyIntent)
        } else {
            rv.setOnClickPendingIntent(
                R.id.touch_scroll_up,
                CueSheetActionReceiver.scrollUpPendingIntent(context)
            )
            rv.setOnClickPendingIntent(
                R.id.touch_scroll_down,
                CueSheetActionReceiver.scrollDownPendingIntent(context)
            )
            val rowIds = intArrayOf(
                R.id.touch_row_0,
                R.id.touch_row_1,
                R.id.touch_row_2,
                R.id.touch_row_3,
                R.id.touch_row_4,
            )
            val visibleCount = snapshot.window.rows.size
            for (i in rowIds.indices) {
                if (i < visibleCount) {
                    rv.setOnClickPendingIntent(
                        rowIds[i],
                        CueSheetActionReceiver.selectRowPendingIntent(context, i)
                    )
                } else {
                    rv.setViewVisibility(rowIds[i], View.INVISIBLE)
                }
            }
        }
        return rv
    }

    companion object {
        const val TYPE_ID = "cuesheet-full"

        fun computeRenderDimensions(viewSize: Pair<Int, Int>, defaultW: Int, defaultH: Int): Pair<Int, Int> {
            val rawW = if (viewSize.first > 50) viewSize.first else defaultW
            val rawH = if (viewSize.second > 50) viewSize.second else defaultH
            // Cap max bitmap dimension to 420x700 to stay ultra-lightweight over RemoteViews IPC while razor sharp on 480x800
            val scale = min(1.0f, min(420f / rawW.toFloat(), 700f / rawH.toFloat()))
            return Pair((rawW * scale).roundToInt().coerceAtLeast(160), (rawH * scale).roundToInt().coerceAtLeast(160))
        }
    }
}
