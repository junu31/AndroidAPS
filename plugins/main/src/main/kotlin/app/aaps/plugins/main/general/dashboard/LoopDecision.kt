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
    val facts: List<Pair<String, String>>
)

/** Values read from the oref reason text ("minPredBG 78", "Eventual BG 104"); null when not present. */
object LoopReasonParser {

    private val minPred = Regex("""minPredBG:?\s*(-?\d+(?:[.,]\d+)?)""", RegexOption.IGNORE_CASE)
    private val eventual = Regex("""Eventual BG:?\s*(-?\d+(?:[.,]\d+)?)""", RegexOption.IGNORE_CASE)

    fun minPredBg(reason: String): String? = minPred.find(reason)?.groupValues?.get(1)
    fun eventualBg(reason: String): String? = eventual.find(reason)?.groupValues?.get(1)
}
