package app.aaps.core.interfaces.autotune

/**
 * Personal-fork: what Autotune looked at on each tuned day, so the weekly review can say why a value moved.
 * Recorded from the same data AutotuneCore uses; it does not change the calculation. All BG values in mg/dL.
 */
data class AutotuneDayTrace(
    val dayStart: Long,
    /** per hour 0..23: sum of deviations in basal-only periods (meals / corrections excluded), null = no such data */
    val hourDeviations: List<Double?>,
    /** ISF used for that day's tuning */
    val isf: Double,
    /** correction periods used for ISF and their ratio actual / expected BG drop */
    val isfRatios: List<Double>,
    /** meals used for IC */
    val meals: Int,
    val mealCarbs: Double,
    /** starting IOB + insulin given + insulin needed for the BG difference at the end */
    val mealInsulin: Double,
    /** sum over meals of (BG at the end − BG at the start) */
    val mealBgChange: Double
)

data class AutotuneTrace(
    val days: List<AutotuneDayTrace>,
    /** per hour: on how many days the hour had no data and only followed its neighbours */
    val untunedDays: List<Int>,
    /** the caps (setting "autosens max / min"), e.g. 1.2 / 0.7 */
    val capMax: Double,
    val capMin: Double
)
