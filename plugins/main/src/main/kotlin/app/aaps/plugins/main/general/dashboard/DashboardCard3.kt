package app.aaps.plugins.main.general.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.aaps.plugins.main.R
import kotlin.math.max
import kotlin.math.sqrt

/** Card 3 geometry: the four panels are carved by a circle around the ring. */
private val Card3Height = 176.dp
private val Pad = 8.dp
private val Gap = 6.dp
private val CarveRadius = 82.dp
private val RingSize = 156.dp

/**
 * Personal-fork: third BG card. The ring of card 2 in the middle, four panels around it whose inner edges
 * follow the ring: now (IOB, COB, Basal, Sens), BG change, last boluses (opens the treatments screen), today.
 */
@Composable
internal fun Card3(state: DashboardState, actions: DashboardActions, color: Color) {
    val bg = state.bg
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(Card3Height)
    ) {
        val w = maxWidth
        val cx = w / 2
        val cy = Card3Height / 2
        val left = Pad + 10.dp
        // usable width of a row centred at y on the left (or mirrored on the right) without entering the circle
        fun widthAt(y: Dp): Dp {
            val dy = (y - cy).value
            val r = CarveRadius.value
            return cx - sqrt(max(0f, r * r - dy * dy)).dp - Pad - 18.dp
        }

        // panels
        Canvas(Modifier.fillMaxSize()) {
            val circle = Path().apply { addOval(Rect(Offset(cx.toPx(), cy.toPx()), CarveRadius.toPx())) }
            val qw = (cx - Pad - Gap / 2).toPx()
            val qh = (cy - Pad - Gap / 2).toPx()
            listOf(
                Offset(Pad.toPx(), Pad.toPx()), Offset(Pad.toPx(), (cy + Gap / 2).toPx()),
                Offset((cx + Gap / 2).toPx(), Pad.toPx()), Offset((cx + Gap / 2).toPx(), (cy + Gap / 2).toPx())
            ).forEach { o ->
                val panel = Path().apply { addRoundRect(RoundRect(Rect(o, androidx.compose.ui.geometry.Size(qw, qh)), CornerRadius(14.dp.toPx()))) }
                drawPath(Path.combine(PathOperation.Difference, panel, circle), Color.White.copy(alpha = 0.04f))
            }
        }

        // top left: now (IOB, COB, Basal, Sens) as four rows, each ending where the circle is
        val y0 = Pad + 4.dp
        listOf(
            Triple("IOB", DashColors.Iob, state.iob),
            Triple("COB", DashColors.Cob, state.cob),
            Triple("Basal", DashColors.Basal, state.basal),
            Triple(stringResource(R.string.dashboard_sens), DashColors.Zt, state.sensitivity)
        ).forEachIndexed { i, (label, dot, tile) ->
            val y = y0 + 2.dp + (i * 18).dp
            Row(
                Modifier
                    .offset(left, y)
                    .width(widthAt(y + 8.dp))
                    .height(16.dp)
                    .clickable { if (tile.dialogText.isNotEmpty()) actions.showInfo(tile.dialogTitle, tile.dialogText) },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(dot)
                )
                Text(label, color = DashColors.Sub, fontSize = 10.sp, maxLines = 1, modifier = Modifier.offset(x = 5.dp))
                Text(
                    tile.value.ifEmpty { "–" }, color = DashColors.Text, fontSize = 11.5.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End, modifier = Modifier.weight(1f)
                )
            }
        }

        // top right: BG change, bars like card 1
        listOf(
            Triple(R.string.dashboard_delta_5, bg.delta, bg.deltasMgdl[0]),
            Triple(R.string.dashboard_delta_15, bg.shortAvgDelta, bg.deltasMgdl[1]),
            Triple(R.string.dashboard_delta_40, bg.longAvgDelta, bg.deltasMgdl[2])
        ).forEachIndexed { i, (label, value, mgdl) ->
            val y = y0 + 6.dp + (i * 22).dp
            val rw = widthAt(y + 7.dp)
            Box(
                Modifier
                    .offset(w - Pad - 10.dp - rw, y)
                    .width(rw)
            ) { DeltaBar(stringResource(label), value, mgdl, compact = true) }
        }

        // bottom left: last boluses, opens the treatments screen
        val yb = cy + Gap / 2 + 2.dp
        val bw = widthAt(yb + 20.dp)
        Box(
            Modifier
                .offset(Pad, cy + Gap / 2)
                .size(cx - Pad - Gap / 2 - 30.dp, cy - Pad - Gap / 2)
                .clip(RoundedCornerShape(14.dp))
                .clickable { actions.onRecentBoluses() }
        )
        Text(stringResource(R.string.dashboard_card3_boluses) + " ›", color = DashColors.Dim, fontSize = 9.5.sp, modifier = Modifier.offset(left, yb))
        state.recentBoluses.take(5).forEachIndexed { i, b ->
            Row(
                Modifier
                    .offset(left, yb + 15.dp + (i * 12).dp)
                    .width(bw),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(b.time, color = DashColors.Dim, fontSize = 9.sp, maxLines = 1, modifier = Modifier.width(30.dp))
                Text(
                    if (b.smb) "SMB" else stringResource(R.string.dashboard_stat_bolus), color = if (b.smb) DashColors.Iob else DashColors.Text,
                    fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                )
                Text(b.amount + " U", color = DashColors.Text, fontSize = 9.5.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            }
        }

        // bottom right: last loop (SMB) decision; tap for the full explanation
        var showDecision by remember { mutableStateOf(false) }
        Box(
            Modifier
                .offset(cx + Gap / 2 + 30.dp, cy + Gap / 2)
                .size(cx - Pad - Gap / 2 - 30.dp, cy - Pad - Gap / 2)
                .clip(RoundedCornerShape(14.dp))
                .clickable { showDecision = true }
        )
        val decision = state.loopDecision
        val lines = buildList {
            add(Triple(stringResource(R.string.dashboard_loop_title) + " ›", DashColors.Dim, 9.5.sp))
            if (decision == null) add(Triple(stringResource(R.string.dashboard_loop_none), DashColors.Sub, 9.5.sp))
            else {
                add(Triple(decision.runTimeText, DashColors.Dim, 9.sp))
                val kindColor = when (decision.kind) {
                    DecisionKind.UP   -> DashColors.High
                    DecisionKind.DOWN -> DashColors.Iob
                    DecisionKind.NONE -> DashColors.Text
                }
                add(Triple(decision.decision, kindColor, 10.sp))
                if (decision.summary.isNotEmpty()) add(Triple(decision.summary, DashColors.Sub, 9.sp))
            }
        }
        // right aligned lines, each as wide as the circle allows at its height
        var ly = yb
        lines.forEachIndexed { i, (text, c, size) ->
            val lh = if (i == 2) 26.dp else 14.dp
            val lw = widthAt(ly + lh / 2)
            Text(
                text, color = c, fontSize = size, fontWeight = if (i == 2) FontWeight.Bold else FontWeight.Normal,
                maxLines = if (i >= 2) 2 else 1, lineHeight = 12.sp, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End,
                modifier = Modifier
                    .offset(w - Pad - 10.dp - lw, ly)
                    .width(lw)
            )
            ly += lh
        }
        if (showDecision) LoopDecisionDialog(decision) { showDecision = false }

        // the ring of card 2; tapping it opens the loop menu
        BgRing(
            bg, color, state.loop, RingSize,
            Modifier
                .offset(cx - RingSize / 2, cy - RingSize / 2)
                .clip(RoundedCornerShape(RingSize / 2))
                .clicks({ actions.onLoopClick() }, { actions.onLoopLongClick() })
        )
    }
}
