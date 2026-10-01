package app.aaps.plugins.main.general.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.max

private const val GAP_MS = 15 * 60 * 1000L

@Composable
fun BgChart(model: GraphModel, modifier: Modifier = Modifier, height: Dp = 250.dp) {
    val tm = rememberTextMeasurer()
    var selected by remember(model) { mutableStateOf<GraphPoint?>(null) }
    val hourFmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

    Canvas(
        modifier
            .fillMaxWidth()
            .height(height)
            .pointerInput(model) {
                detectTapGestures { off ->
                    val left = 34.dp.toPx()
                    val right = size.width - 8.dp.toPx()
                    val t = model.fromTime + ((off.x - left) / (right - left) * (model.endTime - model.fromTime)).toLong()
                    val nearest = model.bg.minByOrNull { abs(it.x - t) }
                    selected = if (nearest != null && abs(nearest.x - t) < (model.endTime - model.fromTime) / 30) nearest else null
                }
            }
    ) {
        if (model.isEmpty) return@Canvas
        val left = 34.dp.toPx()
        val right = size.width - 8.dp.toPx()
        val top = 14.dp.toPx()
        val bottom = size.height - 20.dp.toPx()
        val span = (model.endTime - model.fromTime).toFloat()
        fun x(t: Long) = left + (t - model.fromTime) / span * (right - left)
        fun y(v: Double) = (bottom - (v / model.maxY) * (bottom - top)).toFloat().coerceIn(top - 4f, bottom)

        val labelStyle = TextStyle(color = DashColors.Dim, fontSize = 10.sp)

        // target range band
        drawRoundRect(
            color = DashColors.InRange.copy(alpha = 0.07f),
            topLeft = Offset(left, y(model.highMark)),
            size = Size(right - left, y(model.lowMark) - y(model.highMark)),
            cornerRadius = CornerRadius(6.dp.toPx())
        )
        val dash = PathEffect.dashPathEffect(floatArrayOf(6f, 8f))
        listOf(model.lowMark, model.highMark).forEach {
            drawLine(DashColors.InRange.copy(alpha = 0.35f), Offset(left, y(it)), Offset(right, y(it)), 1.dp.toPx(), pathEffect = dash)
        }
        // grid (subtle): horizontal every 40 mg/dL (2 mmol/L) like the classic graph, vertical dotted line at each labelled hour
        val gridStep = if (model.isMgdl) 40.0 else 2.0
        val markGap = if (model.isMgdl) 12.0 else 0.6
        var g = 0.0
        while (g <= model.maxY) {
            drawLine(DashColors.Grid, Offset(left, y(g)), Offset(right, y(g)), 1f)
            if (abs(g - model.lowMark) > markGap && abs(g - model.highMark) > markGap)
                label(tm, fmtValue(g, model.isMgdl), Offset(left - 6.dp.toPx(), y(g)), labelStyle, alignRight = true)
            g += gridStep
        }
        listOf(model.lowMark, model.highMark).forEach {
            label(tm, fmtValue(it, model.isMgdl), Offset(left - 6.dp.toPx(), y(it)), labelStyle.copy(color = DashColors.Sub), alignRight = true)
        }
        // time axis (local hours)
        val hourStep = when {
            model.rangeHours <= 6  -> 1
            model.rangeHours <= 12 -> 2
            else                   -> 4
        }
        val hourMs = 3600_000L
        val gridDash = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 4.dp.toPx()))
        var t = (model.fromTime / hourMs + 1) * hourMs
        while (t <= model.endTime) {
            val localHour = (((t + TimeZone.getDefault().getOffset(t)) / hourMs) % 24).toInt()
            if (localHour % hourStep == 0) {
                drawLine(DashColors.Grid, Offset(x(t), top), Offset(x(t), bottom), 1f, pathEffect = gridDash)
                label(tm, hourFmt.format(Date(t)), Offset(x(t), size.height - 8.dp.toPx()), labelStyle, center = true)
            }
            t += hourMs
        }

        clipRect(left = left - 2f, top = 0f, right = right + 2f, bottom = bottom) {
        // activity (scaled to 80% of chart)
        if (model.activity.size > 1) {
            val maxAct = model.activity.maxOf { abs(it.y) }.takeIf { it > 0 } ?: 1.0
            val p = Path()
            model.activity.forEachIndexed { i, pt ->
                val yy = bottom - (pt.y / maxAct * 0.8 * (bottom - top)).toFloat()
                if (i == 0) p.moveTo(x(pt.x), yy) else p.lineTo(x(pt.x), yy)
            }
            drawPath(p, DashColors.Activity.copy(alpha = 0.6f), style = Stroke(1.5.dp.toPx()))
        }

        // basal band (bottom 14%)
        val basalMax = (model.basal + model.basalProfile).maxOfOrNull { it.y }?.takeIf { it > 0 }
        if (basalMax != null) {
            val bandH = (bottom - top) * 0.14f
            fun by(v: Double) = bottom - (v.coerceAtLeast(0.0) / basalMax).toFloat() * bandH
            if (model.basal.size > 1) {
                val p = Path().apply {
                    moveTo(x(model.basal.first().x), bottom)
                    model.basal.forEach { lineTo(x(it.x), by(it.y)) }
                    lineTo(x(model.basal.last().x), bottom)
                    close()
                }
                drawPath(p, DashColors.Basal.copy(alpha = 0.25f))
                val l = Path()
                model.basal.forEachIndexed { i, it -> if (i == 0) l.moveTo(x(it.x), by(it.y)) else l.lineTo(x(it.x), by(it.y)) }
                drawPath(l, DashColors.Basal.copy(alpha = 0.7f), style = Stroke(1.dp.toPx()))
            }
            if (model.basalProfile.size > 1) {
                val l = Path()
                model.basalProfile.forEachIndexed { i, it -> if (i == 0) l.moveTo(x(it.x), by(it.y)) else l.lineTo(x(it.x), by(it.y)) }
                drawPath(l, DashColors.Basal, style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 6f))))
            }
        }

        // temp target line
        if (model.targetLine.size > 1) {
            val l = Path()
            model.targetLine.forEachIndexed { i, it -> if (i == 0) l.moveTo(x(it.x), y(it.y)) else l.lineTo(x(it.x), y(it.y)) }
            drawPath(l, DashColors.Target.copy(alpha = 0.6f), style = Stroke(1.5.dp.toPx()))
        }

        // BG curve with gradient fill, broken on data gaps
        val bg = model.bg.sortedBy { it.x }
        if (bg.isNotEmpty()) {
            val segments = ArrayList<List<GraphPoint>>()
            var current = ArrayList<GraphPoint>()
            bg.forEach { pt ->
                if (current.isNotEmpty() && pt.x - current.last().x > GAP_MS) {
                    segments.add(current); current = ArrayList()
                }
                current.add(pt)
            }
            segments.add(current)
            segments.forEach { seg ->
                if (seg.size < 2) return@forEach
                val line = Path()
                seg.forEachIndexed { i, pt -> if (i == 0) line.moveTo(x(pt.x), y(pt.y)) else line.lineTo(x(pt.x), y(pt.y)) }
                val fill = Path().apply {
                    addPath(line)
                    lineTo(x(seg.last().x), bottom)
                    lineTo(x(seg.first().x), bottom)
                    close()
                }
                drawPath(fill, Brush.verticalGradient(listOf(DashColors.InRange.copy(alpha = 0.25f), Color.Transparent), startY = top, endY = bottom))
                drawPath(line, DashColors.InRange, style = Stroke(2.2.dp.toPx()))
            }
            bg.forEach { pt ->
                val c = when {
                    pt.y < model.lowMark  -> DashColors.Low
                    pt.y > model.highMark -> DashColors.High
                    else                  -> null
                }
                if (c != null || bg.size < 40) drawCircle(c ?: DashColors.InRange, 2.6.dp.toPx(), Offset(x(pt.x), y(pt.y)))
            }
        }

        // predictions
        model.predictions.forEach { (kind, pts) ->
            if (pts.size < 2) return@forEach
            val p = Path()
            pts.forEachIndexed { i, pt -> if (i == 0) p.moveTo(x(pt.x), y(pt.y)) else p.lineTo(x(pt.x), y(pt.y)) }
            drawPath(p, kind.color(), style = Stroke(1.8.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f))))
        }

        }

        // now line + current point
        drawLine(DashColors.Sub.copy(alpha = 0.5f), Offset(x(model.now), top), Offset(x(model.now), bottom), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 6f)))
        model.bg.maxByOrNull { it.x }?.let { last ->
            val c = when {
                last.y < model.lowMark  -> DashColors.Low
                last.y > model.highMark -> DashColors.High
                else                    -> DashColors.InRange
            }
            drawCircle(c.copy(alpha = 0.2f), 9.dp.toPx(), Offset(x(last.x), y(last.y)))
            drawCircle(DashColors.Bg, 6.dp.toPx(), Offset(x(last.x), y(last.y)))
            drawCircle(c, 4.dp.toPx(), Offset(x(last.x), y(last.y)))
        }

        // treatment markers, drawn like the classic graph: ▲ on the BG curve, SMB ▲ on the low mark line
        fun triangle(cx: Float, cy: Float, size: Float) = Path().apply {
            moveTo(cx, cy - size)
            lineTo(cx + size, cy + size * 0.67f)
            lineTo(cx - size, cy + size * 0.67f)
            close()
        }
        val markerStyle = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Bold)
        model.markers.forEach { m ->
            val mx = x(m.x)
            if (mx < left || mx > right) return@forEach
            when (m.kind) {
                MarkerKind.BOLUS   -> {
                    val c = if (m.invalid) DashColors.Low else DashColors.Iob
                    val my = y(m.y)
                    drawPath(triangle(mx, my, 6.dp.toPx()), c)
                    drawPath(triangle(mx, my, 6.dp.toPx()), DashColors.Bg, style = Stroke(1.dp.toPx()))
                    label(tm, m.label, Offset(mx + 8.dp.toPx(), my - 10.dp.toPx()), markerStyle.copy(color = c))
                }

                MarkerKind.CARBS   -> {
                    val c = if (m.invalid) DashColors.Low else DashColors.Cob
                    val my = y(m.y)
                    drawPath(triangle(mx, my, 6.dp.toPx()), c)
                    drawPath(triangle(mx, my, 6.dp.toPx()), DashColors.Bg, style = Stroke(1.dp.toPx()))
                    label(tm, m.label, Offset(mx - 8.dp.toPx(), my - 10.dp.toPx()), markerStyle.copy(color = c), alignRight = true)
                }

                MarkerKind.SMB     -> drawPath(triangle(mx, y(model.lowMark), 4.dp.toPx()), DashColors.Iob)

                MarkerKind.PROFILE -> drawCircle(DashColors.Basal, 3.dp.toPx(), Offset(mx, top))

                MarkerKind.THERAPY -> {
                    val my = y(m.y)
                    drawCircle(DashColors.Sub, 4.dp.toPx(), Offset(mx, my), style = Stroke(1.5.dp.toPx()))
                }
            }
        }

        // tap tooltip
        selected?.let { s ->
            val text = hourFmt.format(Date(s.x)) + "  " + fmtValue(s.y, model.isMgdl)
            val layout = tm.measure(text, TextStyle(color = DashColors.Text, fontSize = 12.sp, fontWeight = FontWeight.Bold))
            val px = x(s.x)
            val py = y(s.y)
            drawCircle(DashColors.Text, 4.dp.toPx(), Offset(px, py))
            val w = layout.size.width + 16.dp.toPx()
            val h = layout.size.height + 8.dp.toPx()
            val bx = (px - w / 2).coerceIn(left, right - w)
            val byy = max(top, py - h - 10.dp.toPx())
            drawRoundRect(DashColors.Card2, Offset(bx, byy), Size(w, h), CornerRadius(8.dp.toPx()))
            drawText(layout, topLeft = Offset(bx + 8.dp.toPx(), byy + 4.dp.toPx()))
        }
    }
}

private fun PredictionKind.color(): Color = when (this) {
    PredictionKind.IOB   -> DashColors.Iob
    PredictionKind.COB   -> DashColors.Cob
    PredictionKind.A_COB -> DashColors.Cob.copy(alpha = 0.5f)
    PredictionKind.UAM   -> DashColors.Uam
    PredictionKind.ZT    -> DashColors.Zt
}

private fun fmtValue(v: Double, isMgdl: Boolean): String =
    if (isMgdl) v.toInt().toString() else String.format(Locale.getDefault(), "%.1f", v)

private fun DrawScope.label(tm: TextMeasurer, text: String, anchor: Offset, style: TextStyle, alignRight: Boolean = false, center: Boolean = false) {
    val layout = tm.measure(text, style)
    val dx = when {
        alignRight -> -layout.size.width.toFloat()
        center     -> -layout.size.width / 2f
        else       -> 0f
    }
    drawText(layout, topLeft = Offset(anchor.x + dx, anchor.y - layout.size.height / 2f))
}
