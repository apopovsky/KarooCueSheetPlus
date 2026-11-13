package com.karoocuesheetplus.extension

import com.karoocuesheetplus.data.RideNavigationRepository
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.extension.KarooExtension
import timber.log.Timber

class CueSheetExtensionService : KarooExtension(EXTENSION_ID, EXTENSION_VERSION) {

    override val types: List<DataTypeImpl> by lazy {
        listOf(
            CueSheetFullDataType(extension),
            NextWaypointDataType(extension),
        )
    }

    override fun onBonusAction(actionId: String) {
        Timber.i("CueSheet+ BonusAction triggered: $actionId")
        val repo = RideNavigationRepository.getInstance(this)
        when (actionId) {
            "scroll-up" -> repo.scrollUp()
            "scroll-down" -> repo.scrollDown()
            "close-detail" -> repo.closeDetail()
        }
    }

    companion object {
        const val EXTENSION_ID = "karoo-cuesheet-plus"
        const val EXTENSION_VERSION = "1.0.4"
    }
}
