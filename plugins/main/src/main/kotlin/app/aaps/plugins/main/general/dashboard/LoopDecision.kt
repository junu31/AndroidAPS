package app.aaps.plugins.main.general.dashboard

import androidx.compose.runtime.Immutable

/** Kind of change the last loop run made; drives the floating button dot colour. */
enum class DecisionKind { UP, DOWN, NONE }

/**
 * Last loop decision for the Dashboard floating button.
 * Built locally from the loop result (no API call).
 */
@Immutable
data class LoopDecision(
    val runTime: Long,
    /** e.g. "19:02 · 1 min ago" */
    val runTimeText: String,
    val decision: String,
    val kind: DecisionKind,
    /** short local explanation, empty when no simple rule matched */
    val summary: String,
    val facts: List<Pair<String, String>>,
    /** full oref reason text (input for the AI explanation) */
    val reason: String = "",
    val math: LoopMath? = null
)

/** Values read from the oref reason text ("minPredBG 78", "Eventual BG 104"); null when not present. */
object LoopReasonParser {

    private val minPred = Regex("""minPredBG:?\s*(-?\d+(?:[.,]\d+)?)""", RegexOption.IGNORE_CASE)
    private val eventual = Regex("""Eventual BG:?\s*(-?\d+(?:[.,]\d+)?)""", RegexOption.IGNORE_CASE)

    fun minPredBg(reason: String): String? = minPred.find(reason)?.groupValues?.get(1)
    fun eventualBg(reason: String): String? = eventual.find(reason)?.groupValues?.get(1)

    /** numeric value after a label like "Dev: 12" or "minGuardBG 98" (user's units) */
    fun number(reason: String, label: String): Double? =
        Regex("""(?<![A-Za-z])${Regex.escape(label)}:?\s*(-?\d+(?:[.,]\d+)?)""").find(reason)?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull()
}

/** One oref prediction curve (mg/dL every 5 min) for the "how the numbers came out" chart. */
@Immutable
data class PredCurve(val kind: PredKind, val values: List<Int>)

enum class PredKind { IOB, COB, UAM, ZT }

/** A labelled step of the eventual BG sum, e.g. "IOB 1.0 U × ISF 33" → "− 33". */
@Immutable
data class MathStep(val label: String, val value: String, val kind: StepKind = StepKind.PLAIN)

enum class StepKind { PLAIN, DOWN, UP }

/**
 * How the eventual BG and the lowest predicted BG were worked out (OpenAPS SMB), pre-formatted in the user's units.
 * Rebuilt from the loop result itself: the reason text (Dev, BGI, ISF, minGuardBG ...) and the prediction curves.
 */
@Immutable
data class LoopMath(
    val eventual: String,
    val eventualSteps: List<MathStep>,
    val deviationNote: String,
    val minPred: String,
    val minPredNote: String,
    val minPredWarn: Boolean,
    val curves: List<PredCurve>,
    /** mg/dL, for the chart */
    val minPredMgdl: Double,
    val targetMgdl: Double,
    val thresholdMgdl: Double,
    val targetText: String,
    val thresholdText: String,
    val verdicts: List<Pair<String, Boolean>>
)
