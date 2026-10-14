package app.aaps.plugins.main.general.dashboard.meal

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class MealMathTest {

    /** 4 h window: 49 samples */
    private fun input(bg: List<Int?>, usedInsulin: Double, other: List<MealMath.OtherCarbs> = emptyList()) = MealMath.EvalInput(
        carbs = 70.0, ic = 10.0, isfMgdl = 50.0, bg = bg,
        activity = List(bg.size) { usedInsulin / MealMath.STEP_MIN / bg.size },
        mealBolus = 7.0, smb = 1.0, basalExtra = 0.5, manualExtra = 0.0, otherCarbs = other
    )

    private fun flat(v: Int, n: Int = 49) = List<Int?>(n) { v }

    @Test
    fun `type follows fat and protein rules`() {
        assertThat(MealMath.typeFor(45.0, 60.0)).isEqualTo(FoodType.FAT_PROTEIN)
        assertThat(MealMath.typeFor(18.0, 24.0)).isEqualTo(FoodType.FAT)
        assertThat(MealMath.typeFor(5.0, 25.0)).isEqualTo(FoodType.FAT)
        assertThat(MealMath.typeFor(3.0, 4.0)).isEqualTo(FoodType.FAST)
        assertThat(MealMath.typeFor(12.0, 8.0)).isEqualTo(FoodType.MIXED)
        assertThat(MealMath.typeFor(null, null)).isEqualTo(FoodType.MIXED)
        // only one value known: never "fast"
        assertThat(MealMath.typeFor(3.0, null)).isEqualTo(FoodType.MIXED)
    }

    @Test
    fun `needed insulin adds the BG left over`() {
        // used 8.5 U, ended where it started -> needed 8.5, expected 7.0 -> +21 % = too little
        val e = MealMath.evaluate(input(flat(120), 8.5))
        assertThat(e.neededInsulin).isWithin(0.01).of(8.5)
        assertThat(e.ratioPct).isEqualTo(21)
        assertThat(e.grade).isEqualTo(MealGrade.UNDER)
        // ended 50 higher: one more unit (ISF 50) was needed
        val higher = MealMath.evaluate(input(List(49) { 120 + it * 50 / 48 }, 7.0))
        assertThat(higher.neededInsulin).isWithin(0.05).of(8.0)
    }

    @Test
    fun `close to the profile is ok`() {
        val e = MealMath.evaluate(input(flat(120), 7.3))
        assertThat(e.grade).isEqualTo(MealGrade.OK)
        assertThat(e.tirPct).isEqualTo(100)
    }

    @Test
    fun `low means too much, early low with late high means timing`() {
        val low = flat(120).toMutableList().also { it[30] = 65 }
        assertThat(MealMath.evaluate(input(low, 7.0)).grade).isEqualTo(MealGrade.OVER)
        val timing = flat(120).toMutableList().also { it[10] = 62; it[36] = 220 }
        assertThat(MealMath.evaluate(input(timing, 7.0)).grade).isEqualTo(MealGrade.TIMING)
    }

    @Test
    fun `other carbs exclude, small hypo treatment counts`() {
        val other = MealMath.evaluate(input(flat(120), 7.0, listOf(MealMath.OtherCarbs(90, 35.0, 130))))
        assertThat(other.grade).isEqualTo(MealGrade.EXCLUDED)
        assertThat(other.exclude).isEqualTo(ExcludeReason.OTHER_MEAL)
        // 15 g at BG 72: treatment, 1.5 U (IC 10) less was needed
        val hypo = MealMath.evaluate(input(flat(120), 8.5, listOf(MealMath.OtherCarbs(90, 15.0, 72))))
        assertThat(hypo.exclude).isEqualTo(ExcludeReason.NONE)
        assertThat(hypo.neededInsulin).isWithin(0.01).of(7.0)
    }

    @Test
    fun `sensor gap excludes`() {
        val gaps = flat(120).mapIndexed { i, v -> if (i % 2 == 0) null else v }
        assertThat(MealMath.evaluate(input(gaps, 7.0)).exclude).isEqualTo(ExcludeReason.SENSOR_GAP)
    }

    private fun meal(carbs: Double, needed: Double, minBg: Int = 100, rising: Boolean = false, maxAt: Int = 150, start: Int = 120, end: Int = 120) = MealRecord(
        id = "x", carbs = carbs, confirmed = true,
        eval = MealEval(grade = MealGrade.OK, neededInsulin = needed, minBg = minBg, risingAtEnd = rising, maxAtMin = maxAt, startBg = start, endBg = end)
    )

    @Test
    fun `suggestion compares the history with the current profile`() {
        // history 70 g -> 8.5 U (8.24 g/U); now 80 g with IC 9 -> 8.89 U, history 9.71 U -> +0.8 U
        val s = MealMath.suggest(listOf(meal(70.0, 8.5), meal(70.0, 8.5)), 80.0, 9.0, 0.1)!!
        assertThat(s.gramsPerUnit).isWithin(0.01).of(8.24)
        assertThat(s.units).isWithin(0.001).of(0.8)
        assertThat(s.capped).isFalse()
        // profile already changed enough: nothing left
        assertThat(MealMath.suggest(listOf(meal(70.0, 8.5), meal(70.0, 8.5)), 70.0, 8.24, 0.1)!!.units).isWithin(0.001).of(0.0)
    }

    @Test
    fun `suggestion needs two meals, is capped and never increases after a low`() {
        assertThat(MealMath.suggest(listOf(meal(70.0, 8.5)), 70.0, 10.0, 0.1)).isNull()
        val capped = MealMath.suggest(listOf(meal(70.0, 14.0), meal(70.0, 14.0)), 70.0, 10.0, 0.1)!!
        assertThat(capped.capped).isTrue()
        assertThat(capped.units).isWithin(0.001).of(1.4)
        val hypo = MealMath.suggest(listOf(meal(70.0, 8.5, minBg = 65), meal(70.0, 8.5)), 70.0, 10.0, 0.1)!!
        assertThat(hypo.blockedByHypo).isTrue()
        assertThat(hypo.units).isWithin(0.001).of(0.0)
    }

    @Test
    fun `time advice goes longer when still rising and shorter when settled early`() {
        assertThat(MealMath.timeAdvice(listOf(meal(70.0, 8.0, rising = true), meal(70.0, 8.0, rising = true)), FoodType.FAT)).isEqualTo(FoodType.FAT_PROTEIN)
        assertThat(MealMath.timeAdvice(listOf(meal(70.0, 8.0, maxAt = 60), meal(70.0, 8.0, maxAt = 90)), FoodType.FAT)).isEqualTo(FoodType.MIXED)
        assertThat(MealMath.timeAdvice(listOf(meal(70.0, 8.0)), FoodType.FAT)).isNull()
    }
}
