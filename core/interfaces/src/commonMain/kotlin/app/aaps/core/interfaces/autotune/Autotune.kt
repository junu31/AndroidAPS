package app.aaps.core.interfaces.autotune

interface Autotune {

    suspend fun aapsAutotune(daysBack: Int, autoSwitch: Boolean, profileToTune: String = "", weekDays: BooleanArray? = null)
    fun atLog(message: String)

    var lastRunSuccess: Boolean
    var calculationRunning: Boolean

    // Personal-fork: used by the Dashboard weekly review (same actions as the Autotune tab buttons)

    /** false when Autotune cannot be used (it is off, or forced off outside engineering builds without the option file). */
    val usable: Boolean

    /** Last successful run (loaded from storage if needed), null when there is none. */
    fun lastResultSummary(): AutotuneSummary?

    /** Store the tuned profile as a new local profile; a suffix is added if [baseName] exists. Returns the name used. */
    suspend fun copyTunedToNewProfile(baseName: String): String?

    /** Overwrite the input profile with the tuned values ("Update input profile"). */
    suspend fun updateInputProfileWithTuned(): Boolean

    /** Restore the input profile values from before the overwrite ("Revert input profile"). */
    suspend fun revertInputProfile(): Boolean
}