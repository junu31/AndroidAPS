package app.aaps.plugins.main.general.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.aaps.plugins.main.R
import kotlin.math.sqrt

/** Today's glucose / insulin statistics shown in the Dashboard stats card. Values are preformatted for display. */
@Immutable
data class GlucoseStats(
    /** % of readings: very low, low, in range, high, very high */
    val rangePct: List<Double>,
    /** Boundaries between the five ranges, already in the user's units */
    val thresholds: List<String>,
    val unitLabel: String,
    val mean: String,
    val eA1c: String,
    val cv: String,
    val totalInsulin: String,
    val bolus: String,
    val basal: String,
    val carbs: String,
    val cgmActive: String
)

/** Range split and summary values computed from BG readings (mg/dL). Pure so it can be unit tested. */
data class BgSummary(
    val rangePct: List<Double>,
    val meanMgdl: Double,
    val cvPct: Double,
    val eA1cPct: Double,
    val cgmActivePct: Double
)

object GlucoseStatsCalculator {

    const val VERY_LOW_MGDL = 54.0
    const val VERY_HIGH_MGDL = 250.0
    private const val READING_INTERVAL_MS = 5 * 60 * 1000L

    /**
     * @param valuesMgdl BG readings of the period
     * @param lowMgdl    low / in-range boundary (user's low mark)
     * @param highMgdl   in-range / high boundary (user's high mark)
     * @param periodMs   length of the period, used for the expected number of 5-minute readings
     */
    fun summarize(valuesMgdl: List<Double>, lowMgdl: Double, highMgdl: Double, periodMs: Long): BgSummary? {
        if (valuesMgdl.isEmpty()) return null
        val n = valuesMgdl.size.toDouble()
        val counts = IntArray(5)
        valuesMgdl.forEach { v ->
            val bucket = when {
                v < VERY_LOW_MGDL  -> 0
                v < lowMgdl        -> 1
                v <= highMgdl      -> 2
                v <= VERY_HIGH_MGDL -> 3
                else               -> 4
            }
            counts[bucket]++
        }
        val mean = valuesMgdl.average()
        val sd = sqrt(valuesMgdl.sumOf { (it - mean) * (it - mean) } / n)
        val expected = (periodMs / READING_INTERVAL_MS).coerceAtLeast(1)
        return BgSummary(
            rangePct = counts.map { it * 100.0 / n },
            meanMgdl = mean,
            cvPct = sd / mean * 100.0,
            // same estimate as AAPS statistics (DexcomTirImpl)
            eA1cPct = (mean + 46.7) / 28.7,
            cgmActivePct = (n * 100.0 / expected).coerceAtMost(100.0)
        )
    }
}

private val RangeColors = listOf(DashColors.Low, DashColors.Low, DashColors.Accent, DashColors.High, DashColors.High)

@Composable
fun StatsCard(stats: GlucoseStats) {
    // no card: sits directly on the screen background (option B)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Droplet()
            Text(
                stringResource(R.string.dashboard_tir_title), color = DashColors.Text, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .weight(1f)
            )
            Text(
                stringResource(R.string.dashboard_tir_period_today), color = DashColors.Sub, fontSize = 11.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(DashColors.Card2)
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            )
        }

        val labels = listOf(
            R.string.dashboard_tir_very_low, R.string.dashboard_tir_low, R.string.dashboard_tir_in_range,
            R.string.dashboard_tir_high, R.string.dashboard_tir_very_high
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 12.dp)) {
            labels.forEachIndexed { i, label ->
                val c = RangeColors[i]
                Column(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (i == 2) c.copy(alpha = 0.08f) else Color.Transparent)
                        .border(1.5.dp, c, RoundedCornerShape(12.dp))
                        .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(stringResource(label), color = DashColors.Sub, fontSize = 10.5.sp, maxLines = 1)
                    Text(
                        stats.rangePct.getOrNull(i)?.let { String.format(java.util.Locale.getDefault(), "%.1f%%", it) } ?: "–",
                        color = c, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.padding(top = 3.dp)
                    )
                }
            }
        }

        ThresholdBar(stats.thresholds)
        Text(
            stringResource(R.string.dashboard_tir_unit, stats.unitLabel), color = DashColors.Dim, fontSize = 10.5.sp,
            textAlign = TextAlign.End, modifier = Modifier.fillMaxWidth()
        )

        val cells = listOf(
            Triple(R.string.dashboard_stat_mean, stats.mean, stats.unitLabel),
            Triple(R.string.dashboard_stat_ea1c, stats.eA1c, "%"),
            Triple(R.string.dashboard_stat_cv, stats.cv, "%"),
            Triple(R.string.dashboard_stat_total_insulin, stats.totalInsulin, "U"),
            Triple(R.string.dashboard_stat_bolus, stats.bolus, "U"),
            Triple(R.string.dashboard_stat_basal, stats.basal, "U"),
            Triple(R.string.dashboard_stat_carbs, stats.carbs, "g"),
            Triple(R.string.dashboard_stat_cgm_active, stats.cgmActive, "%")
        )
        Box(
            Modifier
                .padding(top = 12.dp)
                .fillMaxWidth()
                .height(1.dp)
                .background(DashColors.Line)
        )
        cells.chunked(4).forEachIndexed { rowIndex, row ->
            if (rowIndex > 0) Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(DashColors.Line)
            )
            Row(Modifier.height(IntrinsicSize.Min)) {
                row.forEachIndexed { i, (label, value, unit) ->
                    if (i > 0) Box(
                        Modifier
                            .width(1.dp)
                            .fillMaxHeight()
                            .background(DashColors.Line)
                    )
                    StatCell(stringResource(label), value, unit)
                }
            }
        }
    }
}

@Composable
private fun RowScope.StatCell(label: String, value: String, unit: String) {
    Column(
        Modifier
            .weight(1f)
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(label, color = DashColors.Sub, fontSize = 10.5.sp, maxLines = 1)
        Text(value, color = DashColors.Text, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.padding(top = 4.dp))
        Text(unit, color = DashColors.Dim, fontSize = 10.5.sp, maxLines = 1)
    }
}

/** Coloured line split into the five ranges, with the four boundary values as pills (evenly spaced). */
@Composable
private fun ThresholdBar(thresholds: List<String>) {
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .height(26.dp)
    ) {
        val segments = 5
        Canvas(Modifier.fillMaxWidth().height(26.dp)) {
            val y = size.height / 2
            val w = size.width / segments
            RangeColors.forEachIndexed { i, c ->
                drawLine(
                    if (i == 1) c.copy(alpha = 0.75f) else c, Offset(i * w, y), Offset((i + 1) * w, y),
                    strokeWidth = 4.dp.toPx(), cap = StrokeCap.Round
                )
            }
        }
        val pillWidth = 44.dp
        thresholds.forEachIndexed { i, t ->
            Text(
                t, color = DashColors.Text, fontSize = 10.5.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 1,
                modifier = Modifier
                    .offset(x = maxWidth * (i + 1) / segments - pillWidth / 2)
                    .width(pillWidth)
                    .align(Alignment.CenterStart)
                    .clip(RoundedCornerShape(999.dp))
                    .background(DashColors.Bg)
                    .border(1.5.dp, DashColors.Sub, RoundedCornerShape(999.dp))
                    .padding(vertical = 2.dp)
            )
        }
    }
}

@Composable
private fun Droplet() {
    Canvas(Modifier.size(16.dp)) {
        val w = size.width
        val h = size.height
        val p = Path().apply {
            moveTo(w / 2, 0f)
            cubicTo(w * 0.85f, h * 0.4f, w, h * 0.55f, w, h * 0.68f)
            cubicTo(w, h * 0.9f, w * 0.75f, h, w / 2, h)
            cubicTo(w * 0.25f, h, 0f, h * 0.9f, 0f, h * 0.68f)
            cubicTo(0f, h * 0.55f, w * 0.15f, h * 0.4f, w / 2, 0f)
            close()
        }
        drawPath(p, DashColors.Accent)
    }
}
