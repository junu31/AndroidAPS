package app.aaps.plugins.main.general.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.sin

/** Gap between the trend circle and the edge of the ring box, room for the triangles. */
private val TrendMargin = 14.dp

/** Radius of the outer edge of the loop icon (arrow head included) in its 24-unit viewport. */
private const val LOOP_ICON_EXTENT = 11.94f

/**
 * Personal-fork: BG value inside the original loop icon, with a larger circle around it whose
 * triangle points in the trend direction (two triangles for fast changes). Colors follow the BG range.
 */
@Composable
fun BgRing(
    bg: BgInfo,
    color: Color,
    loop: LoopInfo?,
    size: Dp,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val outerRadius = size / 2 - TrendMargin
    // shrunk so the loop arrow head stays inside the trend circle
    val iconSize = with(density) { ((outerRadius - 7.dp).toPx() / LOOP_ICON_EXTENT * 24f).toDp() }
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val c = Offset(this.size.width / 2, this.size.height / 2)
            val r = outerRadius.toPx()
            drawCircle(color.copy(alpha = 0.55f), radius = r, center = c, style = Stroke(3.dp.toPx()))
            bg.trendAngle?.let { angle ->
                val a = Math.toRadians(-angle.toDouble())
                val offsets = if (bg.trendFast) listOf(0.2, -0.2) else listOf(0.0)
                offsets.forEach { off ->
                    val t = a + off
                    fun pt(radius: Float, rad: Double) = Offset(c.x + radius * cos(rad).toFloat(), c.y + radius * sin(rad).toFloat())
                    val tip = pt(r + 13.dp.toPx(), t)
                    val b1 = pt(r + 2.dp.toPx(), t - 0.17)
                    val b2 = pt(r + 2.dp.toPx(), t + 0.17)
                    drawPath(Path().apply { moveTo(tip.x, tip.y); lineTo(b1.x, b1.y); lineTo(b2.x, b2.y); close() }, color)
                }
            }
        }
        loop?.let {
            Icon(
                painterResource(it.ringIconRes), contentDescription = it.label,
                tint = it.ringTint?.let { argb -> Color(argb) } ?: Color.Unspecified,
                modifier = Modifier.size(iconSize)
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                bg.value,
                color = color,
                fontSize = if (bg.value.length <= 3) 29.sp else 23.sp,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1,
                textDecoration = if (bg.isActual) null else TextDecoration.LineThrough
            )
            val glyph = loop?.ringGlyph ?: LoopGlyph.NONE
            if (glyph != LoopGlyph.NONE) LoopGlyphMark(glyph, loop?.ringTint?.let { Color(it) } ?: color)
            else if (bg.delta.isNotEmpty()) Text(bg.delta, color = DashColors.Sub, fontSize = 10.sp, maxLines = 1)
        }
    }
}

/** The pause bars / cross from the middle of the original loop icons, moved under the BG value. */
@Composable
private fun LoopGlyphMark(glyph: LoopGlyph, color: Color) {
    Canvas(Modifier.size(12.dp)) {
        val w = size.width
        when (glyph) {
            LoopGlyph.PAUSE -> {
                val bar = w * 0.3f
                drawRect(color, topLeft = Offset(0f, 0f), size = Size(bar, w))
                drawRect(color, topLeft = Offset(w - bar, 0f), size = Size(bar, w))
            }

            LoopGlyph.CROSS -> {
                val s = w * 0.22f
                drawLine(color, Offset(0f, 0f), Offset(w, w), s)
                drawLine(color, Offset(w, 0f), Offset(0f, w), s)
            }

            LoopGlyph.NONE  -> Unit
        }
    }
}
