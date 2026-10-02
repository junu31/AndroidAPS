package app.aaps.plugins.main.general.dashboard

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class LoopReasonParserTest {

    private val oref = "COB: 32, Dev: -4, BGI: -3.2, ISF: 45, CR: 10, Target: 100, minPredBG 78, minGuardBG 74, IOBpredBG 70, " +
        "COBpredBG 128, UAMpredBG 96; Eventual BG 104 >= 100, temp 0.84 >~ req 0U/hr. setting 30m zero temp."

    @Test
    fun `reads min predicted and eventual BG from oref reason`() {
        assertThat(LoopReasonParser.minPredBg(oref)).isEqualTo("78")
        assertThat(LoopReasonParser.eventualBg(oref)).isEqualTo("104")
    }

    @Test
    fun `reads decimal mmol values`() {
        val mmol = "minPredBG 4.3, minGuardBG 4.1; Eventual BG 5,8 < 5.5"
        assertThat(LoopReasonParser.minPredBg(mmol)).isEqualTo("4.3")
        assertThat(LoopReasonParser.eventualBg(mmol)).isEqualTo("5,8")
    }

    @Test
    fun `missing values give null`() {
        assertThat(LoopReasonParser.minPredBg("no data")).isNull()
        assertThat(LoopReasonParser.eventualBg("no data")).isNull()
    }
}
