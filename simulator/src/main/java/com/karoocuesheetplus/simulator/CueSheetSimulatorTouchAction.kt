package com.karoocuesheetplus.simulator

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback

object CueTouchKeys {
    val Command = ActionParameters.Key<String>("cuesheet_cmd")
    const val ACTION_SIM_TOUCH = "com.karoocuesheetplus.simulator.TOUCH_ACTION"
    const val EXTRA_CMD = "cmd"
}

object CueSheetTouchBus {
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    var listener: ((String) -> Unit)? = null

    fun dispatch(command: String): Boolean {
        val target = listener ?: return false
        mainHandler.post { target(command) }
        return true
    }
}

class CueSheetTouchAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        val cmd = parameters[CueTouchKeys.Command] ?: return
        if (!CueSheetTouchBus.dispatch(cmd)) {
            val intent = Intent(CueTouchKeys.ACTION_SIM_TOUCH).apply {
                putExtra(CueTouchKeys.EXTRA_CMD, cmd)
                setPackage(context.packageName)
            }
            context.sendBroadcast(intent)
        }
    }
}
