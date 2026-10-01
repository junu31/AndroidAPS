package app.aaps.plugins.main.general.dashboard

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class GlucoseStatsCalculatorTest {

    private val hour = 60 * 60 * 1000L

    @Test
    fun `empty readings give no summary`() {
        assertThat(GlucoseStatsCalculator.summarize(emptyList(), 72.0, 180.0, hour)).isNull()
    }

    @Test
    fun `readings are split into five ranges using low and high marks`() {
        // very low, low, in range (incl. both marks), high (incl. 250), very high
        val values = listOf(50.0, 60.0, 72.0, 120.0, 180.0, 200.0, 250.0, 300.0)
        val s = GlucoseStatsCalculator.summarize(values, 72.0, 180.0, 8 * 5 * 60 * 1000L)!!
        assertThat(s.rangePct).containsExactly(12.5, 12.5, 37.5, 25.0, 12.5).inOrder()
    }

    @Test
    fun `mean, cv and estimated HbA1c`() {
        val s = GlucoseStatsCalculator.summarize(listOf(100.0, 140.0), 72.0, 180.0, 2 * 5 * 60 * 1000L)!!
        assertThat(s.meanMgdl).isWithin(1e-9).of(120.0)
        // population SD = 20 -> CV = 20 / 120
        assertThat(s.cvPct).isWithin(1e-9).of(20.0 / 120.0 * 100.0)
        assertThat(s.eA1cPct).isWithin(1e-9).of((120.0 + 46.7) / 28.7)
    }

    @Test
    fun `cgm active is readings over expected 5 minute slots, capped at 100`() {
        val half = GlucoseStatsCalculator.summarize(List(6) { 120.0 }, 72.0, 180.0, hour)!!
        assertThat(half.cgmActivePct).isWithin(1e-9).of(50.0)
        val over = GlucoseStatsCalculator.summarize(List(20) { 120.0 }, 72.0, 180.0, hour)!!
        assertThat(over.cgmActivePct).isEqualTo(100.0)
    }
}
