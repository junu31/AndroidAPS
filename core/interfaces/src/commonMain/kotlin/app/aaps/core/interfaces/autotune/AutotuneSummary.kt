package app.aaps.core.interfaces.autotune

/**
 * Personal-fork: plain-data view of the last Autotune run, used by the Dashboard weekly review.
 * ISF is in mg/dL per U, IC in g per U, basal is hourly (24 values, U/h).
 */
data class AutotuneSummary(
    val runTime: Long,
    val days: Int,
    val profileName: String,
    val currentBasal: List<Double>,
    val tunedBasal: List<Double>,
    val currentIsfMgdl: Double,
    val tunedIsfMgdl: Double,
    val currentIc: Double,
    val tunedIc: Double,
    /** the input profile still has its original values (can be overwritten with the tuned ones) */
    val canUpdate: Boolean,
    /** the input profile was overwritten with the tuned values (can be reverted) */
    val canRevert: Boolean,
    /** what each tuned day looked at (null for runs saved before this was recorded) */
    val trace: AutotuneTrace? = null
)
