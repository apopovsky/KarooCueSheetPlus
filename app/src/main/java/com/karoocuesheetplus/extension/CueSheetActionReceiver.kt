package com.karoocuesheetplus.extension

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.karoocuesheetplus.data.RideNavigationRepository
import timber.log.Timber

class CueSheetActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        Timber.d("CueSheetActionReceiver received action: $action")
        val repo = RideNavigationRepository.getInstance(context)

        when (action) {
            ACTION_SCROLL_UP -> repo.scrollUp()
            ACTION_SCROLL_DOWN -> repo.scrollDown()
            ACTION_SELECT_ROW -> {
                val rowIdx = intent.getIntExtra(EXTRA_ROW_INDEX, -1)
                if (rowIdx in 0..4) {
                    repo.selectVisibleRow(rowIdx)
                }
            }
            ACTION_CLOSE_DETAIL -> repo.closeDetail()
            ACTION_TOGGLE_DETAIL_LOCK -> repo.toggleDetailLock()
        }
    }

    companion object {
        const val ACTION_SCROLL_UP = "com.karoocuesheetplus.ACTION_SCROLL_UP"
        const val ACTION_SCROLL_DOWN = "com.karoocuesheetplus.ACTION_SCROLL_DOWN"
        const val ACTION_SELECT_ROW = "com.karoocuesheetplus.ACTION_SELECT_ROW"
        const val ACTION_CLOSE_DETAIL = "com.karoocuesheetplus.ACTION_CLOSE_DETAIL"
        const val ACTION_TOGGLE_DETAIL_LOCK = "com.karoocuesheetplus.ACTION_TOGGLE_DETAIL_LOCK"
        private const val EXTRA_ROW_INDEX = "extra_row_index"

        private const val REQ_SCROLL_UP = 1001
        private const val REQ_SCROLL_DOWN = 1002
        private const val REQ_CLOSE_DETAIL = 1003
        private const val REQ_TOGGLE_LOCK = 1004
        private const val REQ_ROW_BASE = 1010

        fun scrollUpPendingIntent(context: Context): PendingIntent =
            buildBroadcast(context, REQ_SCROLL_UP, ACTION_SCROLL_UP)

        fun scrollDownPendingIntent(context: Context): PendingIntent =
            buildBroadcast(context, REQ_SCROLL_DOWN, ACTION_SCROLL_DOWN)

        fun closeDetailPendingIntent(context: Context): PendingIntent =
            buildBroadcast(context, REQ_CLOSE_DETAIL, ACTION_CLOSE_DETAIL)

        fun toggleDetailLockPendingIntent(context: Context): PendingIntent =
            buildBroadcast(context, REQ_TOGGLE_LOCK, ACTION_TOGGLE_DETAIL_LOCK)

        fun selectRowPendingIntent(context: Context, rowIndex: Int): PendingIntent {
            val intent = Intent(context, CueSheetActionReceiver::class.java).apply {
                action = ACTION_SELECT_ROW
                putExtra(EXTRA_ROW_INDEX, rowIndex)
            }
            return PendingIntent.getBroadcast(
                context,
                REQ_ROW_BASE + rowIndex,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        private fun buildBroadcast(context: Context, reqCode: Int, actionName: String): PendingIntent {
            val intent = Intent(context, CueSheetActionReceiver::class.java).apply {
                action = actionName
            }
            return PendingIntent.getBroadcast(
                context,
                reqCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
    }
}
