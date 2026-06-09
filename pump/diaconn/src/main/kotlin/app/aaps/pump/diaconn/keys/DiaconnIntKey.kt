package app.aaps.pump.diaconn.keys

import app.aaps.core.keys.interfaces.BooleanPreferenceKey
import app.aaps.core.keys.interfaces.IntPreferenceKey

enum class DiaconnIntKey(
    override val key: String,
    override val defaultValue: Int,
    override val min: Int = Int.MIN_VALUE,
    override val max: Int = Int.MAX_VALUE,
    override val calculatedDefaultValue: Boolean = false,
    override val engineeringModeOnly: Boolean = false,
    override val defaultedBySM: Boolean = false,
    override val showInApsMode: Boolean = true,
    override val showInNsClientMode: Boolean = true,
    override val showInPumpControlMode: Boolean = true,
    override val dependency: BooleanPreferenceKey? = null,
    override val negativeDependency: BooleanPreferenceKey? = null,
    override val hideParentScreenIfHidden: Boolean = false,
    override val exportable: Boolean = true
) : IntPreferenceKey {

    BolusSpeed("g8_bolusspeed", 5),
    BatteryWarningSleepMuteStartHour(
        "diaconn_g8_batterywarning_sleepmute_start", defaultValue = 23, min = 0, max = 23,
        dependency = DiaconnBooleanKey.BatteryWarningSleepMute
    ),
    BatteryWarningSleepMuteEndHour(
        "diaconn_g8_batterywarning_sleepmute_end", defaultValue = 7, min = 0, max = 23,
        dependency = DiaconnBooleanKey.BatteryWarningSleepMute
    ),
}
