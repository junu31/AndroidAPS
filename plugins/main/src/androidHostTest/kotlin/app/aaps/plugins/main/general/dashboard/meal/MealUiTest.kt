package app.aaps.plugins.main.general.dashboard.meal

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class MealUiTest {

    @Test
    fun `search finds a name from one letter, ignoring spaces`() {
        assertThat(MealUi.matches("쉘위", "쉘")).isTrue()
        assertThat(MealUi.matches("짜장면 배달", "짜장면배")).isTrue()
        assertThat(MealUi.matches("짜장면", "피자")).isFalse()
        assertThat(MealUi.matches("짜장면", "")).isTrue()
    }

    @Test
    fun `linking an AI name still needs two letters`() {
        assertThat(MealUi.similar("쉘위", "쉘")).isFalse()
        assertThat(MealUi.similar("짜장면 배달", "짜장면")).isTrue()
    }
}
