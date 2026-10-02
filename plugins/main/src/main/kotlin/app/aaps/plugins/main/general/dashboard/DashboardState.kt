package app.aaps.plugins.main.general.dashboard

import androidx.annotation.DrawableRes
import androidx.compose.runtime.Immutable

/**
 * Snapshot of everything the Dashboard tab renders.
 * Built by [DashboardFragment] from the same sources OverviewFragment uses.
 */
@Immutable
data class DashboardState(
    val bg: BgInfo = BgInfo(),
    val loop: LoopInfo? = null,
    val profile: RibbonInfo = RibbonInfo(),
    val target: RibbonInfo = RibbonInfo(),
    val iob: InfoTile = InfoTile(),
    val cob: InfoTile = InfoTile(),
    val basal: InfoTile = InfoTile(),
    val extended: InfoTile? = null,
    val sensitivity: InfoTile = InfoTile(),
    val statusLights: List<StatusLight> = emptyList(),
    val stats: GlucoseStats? = null,
    val loopDecision: LoopDecision? = null,
    /** null = Autotune not enabled (card hidden); "" = never reviewed; otherwise e.g. "6 days ago" */
    val weeklyReviewLast: String? = null,
    val buttons: Buttons = Buttons(),
    val pumpStatus: String = "",
    val calcProgressPct: Int = 100,
    val simpleMode: Boolean = false
)

enum class BgRange { LOW, IN_RANGE, HIGH, UNKNOWN }

@Immutable
data class BgInfo(
    val value: String = "--",
    val range: BgRange = BgRange.UNKNOWN,
    val isActual: Boolean = true,
    @DrawableRes val arrowRes: Int? = null,
    val arrowDescription: String = "",
    /** 0 = flat/none, 1 = 45 degrees, 2 = single, 3 = double / triple (drives the arrow tile intensity) */
    val trendLevel: Int = 0,
    val delta: String = "",
    val shortAvgDelta: String = "",
    val longAvgDelta: String = "",
    /** delta, short avg and long avg delta in mg/dL per 5 min (for the bar chart), null when unknown */
    val deltasMgdl: List<Double?> = listOf(null, null, null),
    val timeAgo: String = "",
    @DrawableRes val qualityIcon: Int = 0,
    val qualityMessage: String = ""
)

enum class Severity { OK, WARNING, CRITICAL, NEUTRAL }

@Immutable
data class LoopInfo(
    @DrawableRes val iconRes: Int,
    val label: String,
    val extra: String,
    val severity: Severity
)

@Immutable
data class RibbonInfo(
    val text: String = "",
    val severity: Severity = Severity.NEUTRAL
)

@Immutable
data class InfoTile(
    val value: String = "",
    val sub: String = "",
    val dialogTitle: String = "",
    val dialogText: String = "",
    val highlight: Boolean = false
)

@Immutable
data class StatusLight(
    @DrawableRes val iconRes: Int,
    val label: String,
    val value: String,
    val color: Int?,
    val sub: String = "",
    val subColor: Int? = null,
    val severity: Severity = Severity.NEUTRAL
)

@Immutable
data class UserAction(
    val title: String,
    @DrawableRes val iconRes: Int,
    val run: () -> Unit
)

@Immutable
data class Buttons(
    val insulin: Boolean = false,
    val insulinWarning: Boolean = false,
    val carbs: Boolean = false,
    val aiCarbs: Boolean = false,
    val wizard: Boolean = false,
    val treatment: Boolean = false,
    val calibration: Boolean = false,
    val cgm: Boolean = false,
    @DrawableRes val cgmIcon: Int = 0,
    val quickWizard: String? = null,
    val acceptTemp: String? = null,
    val userActions: List<UserAction> = emptyList()
)
