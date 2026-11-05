package com.karoocuesheetplus.simulator

import com.itl.karoo.simulator.BaseKarooSimulatorActivity
import com.itl.karoo.simulator.KarooSimulatorPlugin

class CueSheetSimulatorActivity : BaseKarooSimulatorActivity() {
    override val plugin: KarooSimulatorPlugin = CueSheetSimulatorPlugin()
}
