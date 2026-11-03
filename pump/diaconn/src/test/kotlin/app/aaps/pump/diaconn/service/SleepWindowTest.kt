package app.aaps.pump.diaconn.service

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class SleepWindowTest {

    @Test
    fun `window over midnight`() {
        assertThat(isInSleepWindow(23, 7, 23)).isTrue()
        assertThat(isInSleepWindow(23, 7, 0)).isTrue()
        assertThat(isInSleepWindow(23, 7, 6)).isTrue()
        assertThat(isInSleepWindow(23, 7, 7)).isFalse()
        assertThat(isInSleepWindow(23, 7, 22)).isFalse()
    }

    @Test
    fun `window inside a day`() {
        assertThat(isInSleepWindow(1, 5, 1)).isTrue()
        assertThat(isInSleepWindow(1, 5, 5)).isFalse()
    }

    @Test
    fun `same start and end is all day`() {
        assertThat(isInSleepWindow(8, 8, 3)).isTrue()
    }
}
