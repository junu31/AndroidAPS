package app.aaps.plugins.main.general.dashboard.ai

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class AiFpuTest {

    @Test fun fatAndProteinCoefficients() {
        assertThat(aiFpuCarbs(10.0, 20.0)).isWithin(1e-9).of(8.5)
        assertThat(aiFpuCarbs(0.0, 0.0)).isEqualTo(0.0)
    }
}
