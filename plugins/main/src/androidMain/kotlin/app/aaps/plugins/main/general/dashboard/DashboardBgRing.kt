package app.aaps.plugins.main.general.dashboard

import kotlin.math.PI
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
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

/** 4.0 home ring (AapsSpacing.bgRingStrokeWidth) and its trend arc */
private val RingStroke = 8.dp
private const val TREND_SWEEP = 40f

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
    // shrunk so the loop arrow head stays inside the ring band (the 4.0 band is wider than the old line)
    val iconSize = with(density) { ((outerRadius - RingStroke - 4.dp).toPx() / LOOP_ICON_EXTENT * 24f).toDp() }
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val c = Offset(this.size.width / 2, this.size.height / 2)
            // Same drawing as the 4.0 home ring (BgInfoSection): a faint full ring, a bright 40 degree arc in the trend
            // direction and 1 to 3 triangles pointing outward
            val strokeWidth = RingStroke.toPx()
            val ringRadius = outerRadius.toPx() - strokeWidth / 2
            val topLeft = Offset(c.x - ringRadius, c.y - ringRadius)
            val arcSize = Size(ringRadius * 2, ringRadius * 2)
            drawArc(color.copy(alpha = 0.3f), 0f, 360f, false, topLeft, arcSize, style = Stroke(strokeWidth, cap = StrokeCap.Round))
            bg.trendAngle?.let { angle ->
                val centerAngle = -angle // 4.0 angles: 0 = right, -90 = up
                drawArc(color, centerAngle - TREND_SWEEP / 2, TREND_SWEEP, false, topLeft, arcSize, style = Stroke(strokeWidth, cap = StrokeCap.Round))
                val triHeight = strokeWidth * 1.2f
                val triHalfBase = strokeWidth * 1.6f
                val n = bg.trendCount.coerceIn(1, 3)
                val baseDist = ringRadius + strokeWidth * 0.2f
                val angularSpacing = ((triHalfBase * 1.6 / ringRadius) * 180.0 / PI).toFloat()
                for (i in 0 until n) {
                    val triAngle = centerAngle + (i - (n - 1) / 2f) * angularSpacing
                    val triRad = triAngle.toDouble() / 180.0 * PI
                    val dirX = cos(triRad).toFloat()
                    val dirY = sin(triRad).toFloat()
                    val baseX = c.x + baseDist * dirX
                    val baseY = c.y + baseDist * dirY
                    val tipX = baseX + triHeight * dirX
                    val tipY = baseY + triHeight * dirY
                    drawPath(Path().apply {
                        moveTo(tipX, tipY)
                        lineTo(baseX - triHalfBase * dirY, baseY + triHalfBase * dirX)
                        lineTo(baseX + triHalfBase * dirY, baseY - triHalfBase * dirX)
                        close()
                    }, color)
                }
            }
        }
        loop?.let {
            Icon(
                painterResource(it.ringIconRes), contentDescription = it.label,
                tint = it.ringTint?.let { argb -> Color(argb) } ?: Color.Unspecified,
                modifier = Modifier.size(iconSize)
            )
            LoopRingText(it, iconSize)
        }
        // the loop arrow head reaches into the right side of the ring, so the value sits a bit to the left
        Column(Modifier.offset(x = -size * 0.03f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                bg.value,
                color = color,
                fontSize = if (bg.value.length <= 3) 25.sp else 20.sp,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1,
                textDecoration = if (bg.isActual) null else TextDecoration.LineThrough
            )
            val glyph = loop?.ringGlyph ?: LoopGlyph.NONE
            if (glyph != LoopGlyph.NONE) LoopGlyphMark(glyph, loop?.ringTint?.let { Color(it) } ?: color)
        }
    }
}

/** Centre line of the loop icon ring band, in its 24-unit viewport (between the inner 7.16 and outer 9.94 radius). */
private const val LOOP_BAND_RADIUS = 8.55f

/** Loop status written along the bottom of the loop ring (the bottom of every loop icon is a solid band). */
@Composable
private fun LoopRingText(loop: LoopInfo, iconSize: Dp) {
    val text = if (loop.extra.isNotEmpty()) "${loop.label} · ${loop.extra}" else loop.label
    val band = Color(loop.ringColor)
    // dark text on light rings, white on dark ones (LGS purple)
    val textColor = if (band.luminance() < 0.3f) Color.White else DashColors.Bg
    Canvas(Modifier.size(iconSize)) {
        val unit = size.width / 24f
        val r = LOOP_BAND_RADIUS * unit
        val c = Offset(size.width / 2, size.height / 2)
        // left -> bottom -> right, so the text reads upright along the bottom
        val path = android.graphics.Path().apply { addArc(c.x - r, c.y - r, c.x + r, c.y + r, 180f, -180f) }
        val half = (Math.PI * r).toFloat()
        val maxWidth = half * 0.8f
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = textColor.toArgb()
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            textSize = 9.5.sp.toPx()
        }
        // shrink to fit the half circle, then cut what still does not fit
        while (paint.measureText(text) > maxWidth && paint.textSize > 7.sp.toPx()) paint.textSize -= 0.5f
        var shown = text
        while (paint.measureText(shown) > maxWidth && shown.length > 2) shown = shown.dropLast(2) + "…"
        val w = paint.measureText(shown)
        drawIntoCanvas { canvas -> canvas.nativeCanvas.drawTextOnPath(shown, path, (half - w) / 2, paint.textSize * 0.35f, paint) }
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
