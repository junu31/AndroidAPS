package app.aaps.plugins.main.general.dashboard.meal

import kotlin.math.abs
import kotlin.math.round

/**
 * Personal-fork: the deterministic part of meal tracking (no AI): food type, evaluation and the
 * history based suggestion. All BG values in mg/dL.
 */
object MealMath {

    const val LOW = 70
    const val HIGH = 180
    const val STEP_MIN = 5

    /** hypo treatment: at most this many g while BG is below [HYPO_BG] around it */
    const val HYPO_CARBS = 20.0
    const val HYPO_BG = 80

    /** needed insulin this far from the profile counts as too little / too much */
    const val RATIO_LIMIT = 15

    /** suggestion: at least this many evaluated meals, at most ±[CAP] of the meal insulin */
    const val MIN_RECORDS = 2
    const val CAP = 0.2
    private val WEIGHTS = listOf(1.0, 0.7, 0.5, 0.35, 0.25)

    /** Rules on the fat / protein of this meal (g); null when unknown. */
    fun typeFor(fat: Double?, protein: Double?): FoodType {
        if (fat == null && protein == null) return FoodType.MIXED
        val f = fat ?: 0.0
        val p = protein ?: 0.0
        return when {
            f >= 25 && p >= 30                                     -> FoodType.FAT_PROTEIN
            f >= 15 || p >= 20                                     -> FoodType.FAT
            fat != null && protein != null && f < 10 && p < 10     -> FoodType.FAST
            else                                                   -> FoodType.MIXED
        }
    }

    /** mean |deviation| per 5 min before the meal: below [DEV_HIGH] high, below [DEV_MEDIUM] medium, else low */
    const val DEV_HIGH = 3.0
    const val DEV_MEDIUM = 6.0

    fun reliabilityFor(deviations: List<Double>): Reliability {
        if (deviations.isEmpty()) return Reliability.UNKNOWN
        val mean = deviations.sumOf { abs(it) } / deviations.size
        return when {
            mean < DEV_HIGH   -> Reliability.HIGH
            mean < DEV_MEDIUM -> Reliability.MEDIUM
            else              -> Reliability.LOW
        }
    }

    /** Carbs entered during the window other than the meal itself. bgNear = lowest BG within ±15 min. */
    data class OtherCarbs(val min: Int, val grams: Double, val bgNear: Int?)

    data class EvalInput(
        val carbs: Double,
        val ic: Double,
        val isfMgdl: Double,
        /** BG every 5 min from the meal to the end of the window, null = missing */
        val bg: List<Int?>,
        /** insulin activity (U/min) at the same times */
        val activity: List<Double>,
        val mealBolus: Double,
        val smb: Double,
        val basalExtra: Double,
        val manualExtra: Double,
        val otherCarbs: List<OtherCarbs> = emptyList(),
        /** COB right before the meal (earlier food, not this meal) and at the end of the window (not absorbed yet) */
        val cobBefore: Double = 0.0,
        val cobAfter: Double = 0.0,
        /** deviations (mg/dL per 5 min) in the 30 min before the meal */
        val startDeviations: List<Double> = emptyList(),
        /** a meal bolus was planned but none was delivered (e.g. suspended loop, pump error) */
        val notDelivered: Boolean = false,
        /** a bolus by hand was for other food / is not answered yet */
        val manualOther: Boolean = false,
        val manualPending: Boolean = false,
        val activityTarget: Boolean = false,
        val profileSwitch: Boolean = false
    )

    fun evaluate(i: EvalInput): MealEval {
        val known = i.bg.filterNotNull()
        val base = MealEval(
            mealBolus = i.mealBolus, smb = i.smb, basalExtra = i.basalExtra, manualExtra = i.manualExtra,
            reliability = reliabilityFor(i.startDeviations), startDeviation = i.startDeviations.takeIf { it.isNotEmpty() }?.let { r2(it.average()) }
        )
        fun excluded(r: ExcludeReason) = base.copy(grade = MealGrade.EXCLUDED, exclude = r)
        val start = i.bg.take(3).firstOrNull { it != null }
        if (i.bg.isEmpty() || known.size < i.bg.size * 0.7 || start == null) return excluded(ExcludeReason.SENSOR_GAP)
        val tail = i.bg.takeLast(3).filterNotNull()
        if (tail.isEmpty()) return excluded(ExcludeReason.SENSOR_GAP)
        val end = tail.average()
        val stats = base.copy(
            tirPct = (known.count { it in LOW..HIGH } * 100.0 / known.size).toInt(),
            maxBg = known.max(),
            maxAtMin = i.bg.indexOf(known.max()) * STEP_MIN,
            minBg = known.min(),
            startBg = start,
            endBg = end.toInt(),
            risingAtEnd = rising(i.bg)
        )
        if (i.notDelivered) return stats.copy(exclude = ExcludeReason.NOT_DELIVERED)
        if (i.manualOther) return stats.copy(exclude = ExcludeReason.MANUAL_OTHER)
        if (i.activityTarget) return stats.copy(exclude = ExcludeReason.ACTIVITY)
        if (i.profileSwitch) return stats.copy(exclude = ExcludeReason.PROFILE_SWITCH)
        val (hypo, other) = i.otherCarbs.partition { it.grams <= HYPO_CARBS && it.bgNear != null && it.bgNear < HYPO_BG }
        if (other.isNotEmpty()) return stats.copy(exclude = ExcludeReason.OTHER_MEAL)
        val hypoCarbs = hypo.sumOf { it.grams }
        val used = i.activity.sumOf { it } * STEP_MIN
        if (i.ic <= 0 || i.isfMgdl <= 0 || i.carbs <= 0) return stats.copy(exclude = ExcludeReason.NO_INSULIN)
        // IOB from before the meal needs no extra term: its action is in "used" and its effect in the BG change.
        // Carbs are different: earlier food still absorbing is taken out, this meal's carbs not absorbed yet are added.
        val needed = used + (end - start) / i.isfMgdl + (i.cobAfter - i.cobBefore - hypoCarbs) / i.ic
        val expected = i.carbs / i.ic
        if (needed <= 0.1) return stats.copy(exclude = ExcludeReason.NO_INSULIN, usedInsulin = r2(used), hypoCarbs = hypoCarbs)
        val ratio = (needed / expected - 1) * 100
        // a low in the first 2 h and a high after it: the amount may be right, the timing is not
        val early = i.bg.take(24).filterNotNull()
        val late = i.bg.drop(24).filterNotNull()
        val grade = when {
            early.any { it < LOW } && late.any { it > HIGH } -> MealGrade.TIMING
            known.any { it < LOW }                            -> MealGrade.OVER
            ratio >= RATIO_LIMIT                              -> MealGrade.UNDER
            ratio <= -RATIO_LIMIT                             -> MealGrade.OVER
            else                                              -> MealGrade.OK
        }
        return stats.copy(
            needsAnswer = i.manualPending,
            grade = grade, hypoCarbs = hypoCarbs, cobBefore = i.cobBefore, cobAfter = i.cobAfter, usedInsulin = r2(used), neededInsulin = r2(needed), expectedInsulin = r2(expected),
            ratioPct = round(ratio).toInt()
        )
    }

    /** still going up at the end: last 15 min clearly above the 15 min half an hour earlier, and high */
    private fun rising(bg: List<Int?>): Boolean {
        if (bg.size < 10) return false
        val last = bg.takeLast(3).filterNotNull()
        val before = bg.subList(bg.size - 9, bg.size - 6).filterNotNull()
        if (last.isEmpty() || before.isEmpty()) return false
        return last.average() - before.average() > 10 && last.average() > 140
    }

    data class Suggestion(
        val records: Int,
        /** g of carbs per 1 U in the history (like an IC) */
        val gramsPerUnit: Double,
        val historyInsulin: Double,
        val currentInsulin: Double,
        /** what goes into the correction field (rounded to the bolus step, 0 = nothing) */
        val units: Double,
        val capped: Boolean,
        val blockedByHypo: Boolean
    )

    /** [meals] of one food, newest first. Null when there are not enough evaluated meals. */
    fun suggest(meals: List<MealRecord>, carbs: Double, currentIc: Double, step: Double): Suggestion? {
        val usable = meals.filter { it.usable }.take(WEIGHTS.size)
        if (usable.count { it.countable } < MIN_RECORDS || carbs <= 0 || currentIc <= 0) return null
        // newer meals and calmer starts weigh more
        val w = usable.indices.map { WEIGHTS[it] * usable[it].eval!!.reliability.weight }
        val perGram = usable.indices.sumOf { w[it] * usable[it].eval!!.neededInsulin / usable[it].carbs } / w.sum()
        val history = carbs * perGram
        val current = carbs / currentIc
        var diff = history - current
        val cap = current * CAP
        val capped = abs(diff) > cap
        diff = diff.coerceIn(-cap, cap)
        val hypo = usable.take(3).any { (it.eval?.minBg ?: 999) < LOW }
        val blocked = hypo && diff > 0
        if (blocked) diff = 0.0
        val units = if (step > 0) round(diff / step) * step else diff
        return Suggestion(usable.size, r2(1 / perGram), r2(history), r2(current), r2(units), capped, blocked)
    }

    /** One step longer / shorter evaluation when the last meals say so, null = keep. */
    fun timeAdvice(meals: List<MealRecord>, type: FoodType): FoodType? {
        val last = meals.filter { it.eval != null && it.eval.grade != MealGrade.EXCLUDED && !it.excludedByUser }.take(3)
        if (last.size < 2) return null
        val types = FoodType.entries
        if (last.count { it.eval!!.risingAtEnd } >= 2 && type != FoodType.FAT_PROTEIN) return types[type.ordinal + 1]
        val settled = last.all { e -> e.eval!!.let { it.maxAtMin <= 120 && !it.risingAtEnd && abs(it.endBg - it.startBg) <= 20 } }
        if (settled && type != FoodType.FAST) return types[type.ordinal - 1]
        return null
    }

    private fun r2(v: Double) = round(v * 100) / 100
}
