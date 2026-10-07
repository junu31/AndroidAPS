package app.aaps.ui.ai

import app.aaps.core.interfaces.autotune.AutotuneTrace
import kotlin.math.abs
import kotlin.math.roundToInt

/** Personal-fork: pure helpers for the weekly review (kept free of Android types for unit tests). */
object WeeklyReviewMath {

    /** Consecutive hours with the same current and tuned basal, e.g. 03–06 0.70 → 0.78. [endHour] is exclusive. */
    data class BasalRange(val startHour: Int, val endHour: Int, val current: Double, val tuned: Double) {

        val changePct: Int get() = if (current == 0.0) 0 else ((tuned / current - 1) * 100).roundToInt()
    }

    fun basalRanges(current: List<Double>, tuned: List<Double>): List<BasalRange> {
        require(current.size == 24 && tuned.size == 24)
        val out = ArrayList<BasalRange>()
        var start = 0
        for (h in 1..24) {
            if (h == 24 || !same(current[h], current[start]) || !same(tuned[h], tuned[start])) {
                out.add(BasalRange(start, h, current[start], tuned[start]))
                start = h
            }
        }
        return out
    }

    fun changePct(current: Double, tuned: Double): Int = if (current == 0.0) 0 else ((tuned / current - 1) * 100).roundToInt()

    // Autotune shows basal with 3 decimals; ranges are only merged when equal at that precision
    private fun same(a: Double, b: Double) = abs(a - b) < 0.0005

    // ---------- why Autotune moved a value (same rules as AutotuneCore) ----------

    enum class Direction { UP, DOWN, SAME }

    /**
     * Why a basal range changed. Autotune fixes hour h's leftover deviation in the 1-3 hours before h,
     * so a range [start, end) is driven by the hours start+1 .. end+2 (wrapping at midnight).
     * [perDay] = deviation sum of those hours per tuned day (null = no basal-only data that day).
     */
    data class BasalReason(
        val direction: Direction,
        /** driving hours as a label range: [driverFrom] .. [driverTo] (exclusive, 0..23) */
        val driverFrom: Int,
        val driverTo: Int,
        val perDay: List<Double?>,
        val daysUp: Int,
        val daysDown: Int,
        val total: Double,
        /** stopped at the "autosens max / min" cap */
        val capped: Boolean,
        /** most days had no data for these hours: they only followed their neighbours */
        val noData: Boolean,
        val avgIsf: Double
    ) {

        val daysWithData: Int get() = perDay.count { it != null }
        val avgPerDay: Double get() = if (daysWithData == 0) 0.0 else perDay.filterNotNull().sum() / daysWithData
    }

    fun basalReason(r: BasalRange, trace: AutotuneTrace): BasalReason {
        val drivers = (r.startHour + 1..r.endHour + 2).map { it % 24 }.distinct()
        val perDay = trace.days.map { d -> drivers.mapNotNull { d.hourDeviations.getOrNull(it) }.takeIf { it.isNotEmpty() }?.sum() }
        val hours = (r.startHour until r.endHour).toList()
        val untunedAvg = hours.map { trace.untunedDays.getOrElse(it) { 0 } }.average()
        val pct = r.changePct
        val capped = r.current > 0 && when {
            pct > 0 && trace.capMax > 0 -> r.tuned >= r.current * trace.capMax - 0.002
            pct < 0 && trace.capMin > 0 -> r.tuned <= r.current * trace.capMin + 0.002
            else                        -> false
        }
        return BasalReason(
            direction = if (pct > 0) Direction.UP else if (pct < 0) Direction.DOWN else Direction.SAME,
            driverFrom = (r.startHour + 1) % 24, driverTo = (r.endHour + 3) % 24,
            perDay = perDay,
            daysUp = perDay.count { (it ?: 0.0) > 0 }, daysDown = perDay.count { (it ?: 0.0) < 0 },
            total = perDay.filterNotNull().sum(),
            capped = capped,
            noData = trace.days.isNotEmpty() && untunedAvg >= trace.days.size / 2.0,
            avgIsf = trace.days.map { it.isf }.average()
        )
    }

    /** ISF: correction periods and the median ratio actual / expected BG drop over the whole week */
    data class IsfReason(val points: Int, val medianRatio: Double?, val tunedDays: Int, val capped: Boolean)

    fun isfReason(trace: AutotuneTrace, current: Double, tuned: Double): IsfReason {
        val all = trace.days.flatMap { it.isfRatios }.sorted()
        val median = if (all.isEmpty()) null else if (all.size % 2 == 1) all[all.size / 2] else (all[all.size / 2 - 1] + all[all.size / 2]) / 2
        // AutotuneCore needs at least 10 points on a day to move ISF
        val tunedDays = trace.days.count { it.isfRatios.size >= 10 }
        val capped = current > 0 && ((trace.capMin > 0 && tuned >= current / trace.capMin * 0.995) || (trace.capMax > 0 && tuned <= current / trace.capMax * 1.005)) && tuned != current
        return IsfReason(all.size, median, tunedDays, capped)
    }

    /** IC: meals used, carbs ÷ insulin actually needed, average BG change from meal start to end */
    data class IcReason(val meals: Int, val carbs: Double, val insulin: Double, val avgBgChange: Double, val capped: Boolean) {

        val measuredIc: Double? get() = if (insulin > 0) carbs / insulin else null
    }

    fun icReason(trace: AutotuneTrace, current: Double, tuned: Double): IcReason {
        val meals = trace.days.sumOf { it.meals }
        val capped = current > 0 && tuned != current &&
            ((trace.capMax > 0 && tuned >= current * trace.capMax * 0.995) || (trace.capMin > 0 && tuned <= current * trace.capMin * 1.005))
        return IcReason(
            meals, trace.days.sumOf { it.mealCarbs }, trace.days.sumOf { it.mealInsulin },
            if (meals == 0) 0.0 else trace.days.sumOf { it.mealBgChange } / meals, capped
        )
    }

    /** Data quality of one Autotune day (04:00 → 04:00). */
    data class DayQuality(val dayStart: Long, val bgCount: Int, val carbEntries: Int) {

        /** fewer than ~70 % of the 288 five-minute readings */
        val sensorGap: Boolean get() = bgCount < MIN_BG_READINGS
        val noCarbs: Boolean get() = carbEntries == 0
        val ok: Boolean get() = !sensorGap && !noCarbs
    }

    const val MIN_BG_READINGS = 200
    private const val DAY_MS = 24 * 60 * 60 * 1000L
    private const val START_HOUR_MS = 4 * 60 * 60 * 1000L

    /** Start times of the [days] Autotune days ending at the last 04:00 (same window Autotune uses). */
    fun dayStarts(now: Long, midnightToday: Long, days: Int): List<Long> {
        var end = midnightToday + START_HOUR_MS
        if (end > now) end -= DAY_MS
        return (days downTo 1).map { end - it * DAY_MS }
    }
}
