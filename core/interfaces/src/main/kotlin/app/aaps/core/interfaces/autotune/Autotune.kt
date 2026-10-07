package app.aaps.core.interfaces.autotune

interface Autotune {

    fun aapsAutotune(daysBack: Int, autoSwitch: Boolean, profileToTune: String = "", weekDays: BooleanArray? = null)
    fun atLog(message: String)

    var lastRunSuccess: Boolean
    var calculationRunning: Boolean

    // Personal-fork: used by the Dashboard weekly review (same actions as the Autotune tab buttons)

    /** Last successful run (loaded from storage if needed), null when there is none. */
    fun lastResultSummary(): AutotuneSummary?

    /**
     * Store the tuned profile as a new local profile; a suffix is added if [baseName] exists. Returns the name used.
     * ISF / IC keep the time blocks of the input profile, each scaled by the tuned / current average.
     */
    fun copyTunedToNewProfile(baseName: String): String?

    /** Overwrite the input profile with the tuned values ("Update input profile"). */
    fun updateInputProfileWithTuned(): Boolean

    /** Restore the input profile values from before the overwrite ("Revert input profile"). */
    fun revertInputProfile(): Boolean
}