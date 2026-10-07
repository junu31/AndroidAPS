package app.aaps.ui.ai

import app.aaps.core.interfaces.autotune.AutotuneDayTrace
import app.aaps.core.interfaces.autotune.AutotuneTrace
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class WeeklyReviewMathTest {

    private val hour = 60 * 60 * 1000L

    @Test
    fun `groups consecutive hours with the same values`() {
        val current = List(24) { if (it < 6) 0.70 else 0.80 }
        val tuned = List(24) { if (it in 3..5) 0.78 else current[it] }
        val r = WeeklyReviewMath.basalRanges(current, tuned)
        assertThat(r.map { it.startHour to it.endHour }).containsExactly(0 to 3, 3 to 6, 6 to 24).inOrder()
        assertThat(r[1].current).isEqualTo(0.70)
        assertThat(r[1].tuned).isEqualTo(0.78)
        assertThat(r[1].changePct).isEqualTo(11)
        assertThat(r[2].changePct).isEqualTo(0)
    }

    @Test
    fun `hours differing only in the third decimal stay separate`() {
        val current = List(24) { 0.700 }
        val tuned = List(24) { if (it < 12) 0.683 else 0.687 }
        val r = WeeklyReviewMath.basalRanges(current, tuned)
        assertThat(r.map { it.startHour to it.endHour }).containsExactly(0 to 12, 12 to 24).inOrder()
    }

    @Test
    fun `day quality flags sensor gaps and missing carbs`() {
        assertThat(WeeklyReviewMath.DayQuality(0, 280, 3).ok).isTrue()
        assertThat(WeeklyReviewMath.DayQuality(0, 150, 3).sensorGap).isTrue()
        assertThat(WeeklyReviewMath.DayQuality(0, 280, 0).noCarbs).isTrue()
    }

    @Test
    fun `day window ends at the last 4 am`() {
        val midnight = 1_000 * 24 * hour
        // 10:00 -> window ends today 04:00
        val after = WeeklyReviewMath.dayStarts(midnight + 10 * hour, midnight, 7)
        assertThat(after).hasSize(7)
        assertThat(after.last()).isEqualTo(midnight + 4 * hour - 24 * hour)
        // 02:00 -> today 04:00 is in the future, window ends yesterday 04:00
        val before = WeeklyReviewMath.dayStarts(midnight + 2 * hour, midnight, 7)
        assertThat(before.last()).isEqualTo(midnight + 4 * hour - 48 * hour)
        assertThat(before.first()).isEqualTo(before.last() - 6 * 24 * hour)
    }

    private fun day(hours: Map<Int, Double>, ratios: List<Double> = emptyList()) =
        AutotuneDayTrace(0, List(24) { hours[it] }, 33.0, ratios, 2, 100.0, 22.0, 30.0)

    @Test
    fun `basal range is explained by the 1-3 hours after it`() {
        val trace = AutotuneTrace(listOf(day(mapOf(1 to 20.0, 5 to 10.0, 6 to 99.0)), day(mapOf(3 to -5.0)), day(emptyMap())), List(24) { 0 }, 1.2, 0.7)
        val r = WeeklyReviewMath.basalReason(WeeklyReviewMath.BasalRange(0, 3, 0.70, 0.84), trace)
        // range 00-03 is driven by hours 1..5; hour 6 does not count
        assertThat(r.driverFrom).isEqualTo(1)
        assertThat(r.driverTo).isEqualTo(6)
        assertThat(r.perDay).containsExactly(30.0, -5.0, null).inOrder()
        assertThat(r.daysUp).isEqualTo(1)
        assertThat(r.daysDown).isEqualTo(1)
        assertThat(r.capped).isTrue()
        assertThat(r.direction).isEqualTo(WeeklyReviewMath.Direction.UP)
    }

    @Test
    fun `late evening range wraps past midnight`() {
        val trace = AutotuneTrace(listOf(day(mapOf(23 to 4.0, 0 to 6.0, 1 to 1.0))), List(24) { 0 }, 1.2, 0.7)
        val r = WeeklyReviewMath.basalReason(WeeklyReviewMath.BasalRange(21, 23, 0.7, 0.7), trace)
        assertThat(r.driverFrom).isEqualTo(22)
        assertThat(r.driverTo).isEqualTo(2)
        assertThat(r.perDay).containsExactly(11.0)
    }

    @Test
    fun `isf median and ic totals`() {
        val trace = AutotuneTrace(listOf(day(emptyMap(), listOf(0.8, 0.9, 1.0)), day(emptyMap(), listOf(1.1))), List(24) { 0 }, 1.2, 0.7)
        val isf = WeeklyReviewMath.isfReason(trace, 33.0, 31.0)
        assertThat(isf.points).isEqualTo(4)
        assertThat(isf.medianRatio).isWithin(1e-9).of(0.95)
        assertThat(isf.tunedDays).isEqualTo(0)
        val ic = WeeklyReviewMath.icReason(trace, 5.3, 5.0)
        assertThat(ic.meals).isEqualTo(4)
        assertThat(ic.measuredIc).isWithin(1e-9).of(200.0 / 44.0)
        assertThat(ic.avgBgChange).isWithin(1e-9).of(15.0)
    }
}
