package com.karoocuesheetplus

import android.app.Application
import timber.log.Timber

class CueSheetApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Timber.plant(Timber.DebugTree())
    }
}
