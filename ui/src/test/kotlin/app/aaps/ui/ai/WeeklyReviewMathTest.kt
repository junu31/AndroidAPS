package app.aaps.ui.ai

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
}
