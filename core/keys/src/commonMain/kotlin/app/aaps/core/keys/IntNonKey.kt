package app.aaps.core.keys

import app.aaps.core.keys.interfaces.IntNonPreferenceKey

@Suppress("SpellCheckingInspection")
enum class IntNonKey(
    override val key: String,
    override val defaultValue: Int,
) : IntNonPreferenceKey {

    ObjectivesManualEnacts("ObjectivesmanualEnacts", 0),
    TddCycleOffset("tdd_cycle_offset", 0),
    RangeToDisplay("rangetodisplay", 6), // Personal-fork: Dashboard graph range in hours, the 3.4 key (read once)
    RangeToDisplayMinutes("rangetodisplay_minutes", 0) // Personal-fork: Dashboard graph range in minutes
}