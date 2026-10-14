package app.aaps.plugins.main.general.dashboard.meal

/*
 * Personal-fork: meal tracking. Stored as JSON (Gson), so every field has a default
 * (Gson then uses the no-arg constructor and missing fields keep their defaults).
 */

/** Food type picked from fat / protein; decides how long after the meal it is evaluated. */
enum class FoodType(val hours: Int) { FAST(3), MIXED(4), FAT(5), FAT_PROTEIN(6) }

enum class MealGrade { OK, UNDER, OVER, TIMING, EXCLUDED }

/** How far the start can be trusted, from the deviations (BG change not explained by insulin) before the meal. */
enum class Reliability(val weight: Double) { HIGH(1.0), MEDIUM(0.6), LOW(0.3), UNKNOWN(0.6) }

/** Why a meal could not be used (shown to the user, never used for a suggestion). */
enum class ExcludeReason { NONE, OTHER_MEAL, SENSOR_GAP, ACTIVITY, PROFILE_SWITCH, NO_INSULIN, USER, NOT_DELIVERED }

data class MealFood(
    val id: String = "",
    val name: String = "",
    val type: FoodType = FoodType.MIXED,
    val tracked: Boolean = false,
    val created: Long = 0
)

/** Something that happened during the evaluation window, minutes after the meal. */
data class MealEvent(val min: Int = 0, val kind: String = "", val amount: Double = 0.0) {

    companion object {

        const val MEAL_BOLUS = "meal"
        const val BOLUS = "bolus"
        const val SMB = "smb"
        const val CARBS = "carbs"
    }
}

data class MealEval(
    val grade: MealGrade = MealGrade.EXCLUDED,
    val exclude: ExcludeReason = ExcludeReason.NONE,
    val tirPct: Int = 0,
    val maxBg: Int = 0,
    val maxAtMin: Int = 0,
    val minBg: Int = 0,
    val startBg: Int = 0,
    val endBg: Int = 0,
    val mealBolus: Double = 0.0,
    val smb: Double = 0.0,
    val basalExtra: Double = 0.0,
    val manualExtra: Double = 0.0,
    val hypoCarbs: Double = 0.0,
    /** carbs still on board right before the meal (other food) and at the end of the window */
    val cobBefore: Double = 0.0,
    val cobAfter: Double = 0.0,
    /** insulin that acted during the window (bolus + net basal, from the insulin activity) */
    val usedInsulin: Double = 0.0,
    /** insulin the meal really needed: used + (end BG − start BG) / ISF + (COB after − COB before − hypo carbs) / IC */
    val neededInsulin: Double = 0.0,
    /** carbs / IC of the profile at the meal */
    val expectedInsulin: Double = 0.0,
    val ratioPct: Int = 0,
    val risingAtEnd: Boolean = false,
    val reliability: Reliability = Reliability.UNKNOWN,
    /** mean deviation per 5 min in the 30 min before the meal (mg/dL, signed), null = no autosens data */
    val startDeviation: Double? = null
)

data class MealRecord(
    val id: String = "",
    val foodId: String = "",
    val foodName: String = "",
    /** carbs time (bolus time + carb time offset) */
    val time: Long = 0,
    val deliverTime: Long = 0,
    val carbs: Double = 0.0,
    val fat: Double? = null,
    val protein: Double? = null,
    val type: FoodType = FoodType.MIXED,
    val tracked: Boolean = true,
    /** the wizard total (replaced by the delivered meal bolus once confirmed) and the part that came from the history ("기록 반영") */
    val bolus: Double = 0.0,
    val historyAdjust: Double = 0.0,
    /** false until the wizard bolus is found in the database (the user may still cancel the confirmation) */
    val confirmed: Boolean = false,
    // profile at the meal
    val profileName: String = "",
    val ic: Double = 0.0,
    val isfMgdl: Double = 0.0,
    val percentage: Int = 100,
    val evalAt: Long = 0,
    // state when the meal was entered (BG mg/dL, its change over 15 min, IOB, COB), null = not recorded
    val entryBg: Int? = null,
    val entryDelta15: Int? = null,
    val entryIob: Double? = null,
    val entryCob: Double? = null,
    val eval: MealEval? = null,
    /** BG every 5 min from [time] (mg/dL, 0 = missing), kept so the graph survives a phone change */
    val curve: List<Int> = emptyList(),
    val events: List<MealEvent> = emptyList(),
    val memoTags: List<String> = emptyList(),
    val memo: String = "",
    val excludedByUser: Boolean = false,
    val seen: Boolean = false,
    /** an AI carbs estimate (「AI 추정 기록」), not a tracked meal: no evaluation */
    val legacy: Boolean = false,
    /** AI: advice how to enter the carbs */
    val legacyText: String = "",
    val aiDescription: String = "",
    val aiHadImage: Boolean = false,
    /** AI: the items with their carbs, e.g. "짜장면 60 g · 단무지 2 g" */
    val aiItems: String = "",
    val aiConfidence: String? = null,
    /** AI: carbs handed to the calculator / carbs dialog, null = not used */
    val appliedCarbs: Double? = null
) {

    /** evaluated and usable for a suggestion */
    val usable: Boolean get() = !legacy && !excludedByUser && eval != null && eval.grade != MealGrade.EXCLUDED && eval.neededInsulin > 0 && carbs > 0

    /** usable and counted for the minimum number of meals (a low reliability start only adds weight) */
    val countable: Boolean get() = usable && eval?.reliability != Reliability.LOW
}

data class MealData(
    val version: Int = 1,
    val foods: List<MealFood> = emptyList(),
    val meals: List<MealRecord> = emptyList(),
    val aiHistoryImported: Boolean = false,
    /** the old AI history was imported again with all fields (image / text, applied carbs) */
    val aiHistoryV2: Boolean = false
)
