package app.aaps.core.interfaces.meal

/**
 * Personal-fork: every AI carbs estimate is kept with the meal records (shown in 「식사 기록 → AI 추정 기록」),
 * whether or not it was used in the calculator.
 */
interface AiEstimateLog {

    /** Store a successful estimate. Returns its id (for [setApplied]). */
    fun addAiEstimate(
        name: String, description: String?, hadImage: Boolean, items: List<Pair<String, Double>>,
        totalCarbs: Double, fat: Double?, protein: Double?, confidence: String?, note: String?
    ): String

    /** The estimate [id] was handed to the calculator / carbs dialog with [carbs] g. */
    fun setApplied(id: String, carbs: Int)
}
