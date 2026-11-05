package com.karoocuesheetplus.extension

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.widget.RemoteViews
import com.karoocuesheetplus.R
import com.karoocuesheetplus.data.CueSettingsStore
import com.karoocuesheetplus.data.RideNavigationRepository
import com.karoocuesheetplus.domain.CueSheetSnapshot
import com.karoocuesheetplus.domain.DemoRouteProvider
import com.karoocuesheetplus.domain.ThemePreference
import com.karoocuesheetplus.render.CueSheetRenderer
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.UpdateGraphicConfig
import io.hammerhead.karooext.models.ViewConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class NextWaypointDataType(extension: String) : DataTypeImpl(extension, TYPE_ID) {

    override fun startStream(emitter: Emitter<StreamState>) {
        // Expose remaining distance (m) to next waypoint for any numeric stream consumers
        val streamScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        emitter.onNext(StreamState.Searching)
        emitter.setCancellable { streamScope.cancel() }
    }

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        emitter.onNext(UpdateGraphicConfig(showHeader = false))

        val isWideSlot = config.gridSize.first > 35 ||
            (config.viewSize.first > 0 && config.viewSize.second > 0 && config.viewSize.first >= config.viewSize.second * 2)

        val defaultW = if (isWideSlot) 480 else 238
        val defaultH = 155
        val rawW = if (config.viewSize.first > 40) config.viewSize.first else defaultW
        val rawH = if (config.viewSize.second > 40) config.viewSize.second else defaultH

        if (config.preview) {
            val settings = CueSettingsStore.getInstance(context).settings.value
            val demoSnap = DemoRouteProvider.buildDemoSnapshot(
                settings = settings,
                manualStartIndex = null,
                selectedDetailId = null,
                detailRemainingSeconds = 0,
            )
            emitter.updateView(buildRemoteViews(context, demoSnap, rawW, rawH, isWideSlot))
            return
        }

        val repo = RideNavigationRepository.getInstance(context)
        val viewScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        repo.onViewAttached()

        viewScope.launch {
            repo.snapshot.collect { snap ->
                emitter.updateView(buildRemoteViews(context, snap, rawW, rawH, isWideSlot))
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
        isWideSlot: Boolean,
    ): RemoteViews {
        return when (snapshot.themePreference) {
            ThemePreference.DARK -> createSingleThemeRv(context, snapshot, widthPx, heightPx, isWideSlot, isNight = true)
            ThemePreference.LIGHT -> createSingleThemeRv(context, snapshot, widthPx, heightPx, isWideSlot, isNight = false)
            ThemePreference.AUTO -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val light = createSingleThemeRv(context, snapshot, widthPx, heightPx, isWideSlot, isNight = false)
                    val dark = createSingleThemeRv(context, snapshot, widthPx, heightPx, isWideSlot, isNight = true)
                    RemoteViews(light, dark)
                } else {
                    val sysNight = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                        Configuration.UI_MODE_NIGHT_YES
                    createSingleThemeRv(context, snapshot, widthPx, heightPx, isWideSlot, isNight = sysNight)
                }
            }
        }
    }

    private fun createSingleThemeRv(
        context: Context,
        snapshot: CueSheetSnapshot,
        widthPx: Int,
        heightPx: Int,
        isWideSlot: Boolean,
        isNight: Boolean,
    ): RemoteViews {
        val rv = RemoteViews(context.packageName, R.layout.remote_next_waypoint)
        val bmp = CueSheetRenderer.renderSingleField(snapshot, widthPx, heightPx, isWideSlot, isNight)
        rv.setImageViewBitmap(R.id.img_next_waypoint_canvas, bmp)
        return rv
    }

    companion object {
        const val TYPE_ID = "next-waypoint"
    }
}
