package app.aaps.plugins.main.general.dashboard

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class DashboardWizardInputsTest {

    private fun inputs(usePercentage: Boolean, useBg: Boolean = true) = dashboardWizardInputs(
        bg = 150.0, useBg = useBg, carbs = 40, usePercentage = usePercentage, enteredPercentage = 80, enteredCorrection = 0.5, basePercentage = 100,
        carbTime = 0, useCob = false, useIob = true, useTt = false, useTrend = false, alarm = false, notes = "", profileName = null
    )

    @Test fun `percent mode replaces the base percentage and drops the correction`() {
        val i = inputs(usePercentage = true)
        assertThat(i.percentage).isEqualTo(80)
        assertThat(i.directCorrection).isEqualTo(0.0)
    }

    @Test fun `unit mode keeps the base percentage and the correction`() {
        val i = inputs(usePercentage = false)
        assertThat(i.percentage).isEqualTo(100)
        assertThat(i.directCorrection).isEqualTo(0.5)
    }

    @Test fun `bg off sends no bg`() {
        assertThat(inputs(usePercentage = false, useBg = false).bg).isEqualTo(0.0)
    }

    @Test fun `base percentage falls back to 100 without a recent bg`() {
        val min = 60_000L
        assertThat(dashboardWizardBasePercentage(120, null, 100 * min, 30)).isEqualTo(100)
        assertThat(dashboardWizardBasePercentage(120, 60 * min, 100 * min, 30)).isEqualTo(100)
        assertThat(dashboardWizardBasePercentage(120, 80 * min, 100 * min, 30)).isEqualTo(120)
    }
}
