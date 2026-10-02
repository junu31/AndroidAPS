package app.aaps.ui.ai

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
