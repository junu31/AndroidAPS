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
import kotlin.math.min
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

        // top left: now
        val y0 = Pad + 2.dp
        Text(stringResource(R.string.dashboard_card3_now), color = DashColors.Dim, fontSize = 9.5.sp, modifier = Modifier.offset(left, y0))
        Grid2(
            x = left, y = y0 + 14.dp, width = min(widthAt(y0 + 28.dp).value, widthAt(y0 + 58.dp).value).dp, alignEnd = false,
            cells = listOf(
                GridCell("IOB", DashColors.Iob, state.iob.value),
                GridCell("COB", DashColors.Cob, state.cob.value),
                GridCell("Basal", DashColors.Basal, state.basal.value),
                GridCell(stringResource(R.string.dashboard_sens), DashColors.Zt, state.sensitivity.value)
            ),
            onClick = { i -> listOf(state.iob, state.cob, state.basal, state.sensitivity)[i].let { if (it.dialogText.isNotEmpty()) actions.showInfo(it.dialogTitle, it.dialogText) } }
        )

        // top right: BG change, bars like card 1
        Text(
            stringResource(R.string.dashboard_card3_delta), color = DashColors.Dim, fontSize = 9.5.sp, textAlign = TextAlign.End,
            modifier = Modifier
                .offset(cx + Gap / 2, y0)
                .width(cx - Gap / 2 - Pad - 10.dp)
        )
        listOf(
            Triple(R.string.dashboard_delta_5, bg.delta, bg.deltasMgdl[0]),
            Triple(R.string.dashboard_delta_15, bg.shortAvgDelta, bg.deltasMgdl[1]),
            Triple(R.string.dashboard_delta_40, bg.longAvgDelta, bg.deltasMgdl[2])
        ).forEachIndexed { i, (label, value, mgdl) ->
            val y = y0 + 18.dp + (i * 19).dp
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

        // bottom right: today
        val stats = state.stats
        val dash = "–"
        Text(
            stringResource(R.string.dashboard_card3_today), color = DashColors.Dim, fontSize = 9.5.sp, textAlign = TextAlign.End,
            modifier = Modifier
                .offset(cx + Gap / 2, yb)
                .width(cx - Gap / 2 - Pad - 10.dp)
        )
        val tw = min(widthAt(yb + 26.dp).value, widthAt(yb + 56.dp).value).dp
        Grid2(
            x = w - Pad - 10.dp - tw, y = yb + 12.dp, width = tw, alignEnd = true,
            cells = listOf(
                GridCell(stringResource(R.string.dashboard_stat_mean), DashColors.Sub, stats?.mean ?: dash, stats?.unitLabel ?: ""),
                GridCell(stringResource(R.string.dashboard_stat_cv), DashColors.Sub, stats?.cv ?: dash, "%"),
                GridCell(stringResource(R.string.dashboard_stat_total_insulin), DashColors.Sub, stats?.totalInsulin ?: dash, "U"),
                GridCell(stringResource(R.string.dashboard_stat_carbs), DashColors.Sub, stats?.carbs ?: dash, "g")
            ),
            onClick = null
        )

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

private data class GridCell(val label: String, val color: Color, val value: String, val unit: String = "")

/** 2x2 values divided by short grid lines (like the BG statistics card). */
@Composable
private fun Grid2(x: Dp, y: Dp, width: Dp, alignEnd: Boolean, cells: List<GridCell>, onClick: ((Int) -> Unit)?) {
    val rowH = 30.dp
    val cw = (width - 8.dp) / 2
    Box(
        Modifier
            .offset(x + cw + 4.dp, y + 4.dp)
            .size(1.dp, rowH * 2 - 8.dp)
            .background(DashColors.Line)
    )
    Box(
        Modifier
            .offset(x + 2.dp, y + rowH)
            .size(width - 4.dp, 1.dp)
            .background(DashColors.Line)
    )
    cells.forEachIndexed { i, c ->
        val col = i % 2
        val row = i / 2
        Column(
            Modifier
                .offset(x + (cw + 8.dp) * col, y + rowH * row + 2.dp)
                .width(cw)
                .then(if (onClick != null) Modifier.clickable { onClick(i) } else Modifier),
            horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start
        ) {
            Text(c.label, color = c.color, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(c.value, color = DashColors.Text, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                if (c.unit.isNotEmpty()) Text(" " + c.unit, color = DashColors.Dim, fontSize = 8.5.sp, maxLines = 1)
            }
        }
    }
}
