package app.aaps.plugins.main.general.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.aaps.plugins.main.R
import kotlin.math.roundToInt

private val FabSize = 52.dp
private val FabMargin = 12.dp

/** Space kept free for the bottom action bar, so the button can't be dropped behind it. */
private val BottomReserve = 100.dp

private val AiPurple = Color(0xFFA78BFA)

/**
 * Draggable floating button for the last loop decision (Dashboard).
 * Tap: local summary popover (no API). Popover "Why?": AI explanation dialog.
 * Drag: move; the position is stored as fractions via [DashboardActions.onFabMoved].
 */
@Composable
fun LoopFabOverlay(decision: LoopDecision?, position: Pair<Float, Float>?, actions: DashboardActions) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val marginPx = with(density) { FabMargin.toPx() }
        val maxX = with(density) { (maxWidth - FabSize - FabMargin * 2).toPx() }.coerceAtLeast(1f)
        val maxY = with(density) { (maxHeight - FabSize - BottomReserve - FabMargin).toPx() }.coerceAtLeast(1f)
        var offset by remember(position, maxX, maxY) {
            mutableStateOf(Offset((position?.first ?: 1f) * maxX, (position?.second ?: 1f) * maxY))
        }
        var open by remember { mutableStateOf(false) }

        if (open) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.55f))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { open = false }
            )
            // open towards the side with more room
            val fabTop = with(density) { (offset.y + marginPx).toDp() }
            val above = offset.y > maxY / 2
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(
                        start = 16.dp, end = 16.dp,
                        top = if (above) 0.dp else fabTop + FabSize + 8.dp,
                        bottom = if (above) maxHeight - fabTop + 8.dp else 0.dp
                    ),
                contentAlignment = if (above) Alignment.BottomCenter else Alignment.TopCenter
            ) {
                LoopPopover(
                    decision,
                    onClose = { open = false },
                    onWhy = {
                        open = false
                        actions.onLoopExplain()
                    }
                )
            }
        }

        val dot = when (decision?.kind) {
            DecisionKind.UP   -> DashColors.High
            DecisionKind.DOWN -> DashColors.Iob
            DecisionKind.NONE -> DashColors.Sub
            null              -> null
        }
        Box(
            Modifier
                .offset { IntOffset((offset.x + marginPx).roundToInt(), (offset.y + marginPx).roundToInt()) }
                .size(FabSize)
        ) {
            Box(
                Modifier
                    .size(FabSize)
                    .shadow(8.dp, RoundedCornerShape(16.dp))
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF13302C))
                    .border(1.dp, DashColors.Accent.copy(alpha = 0.55f), RoundedCornerShape(16.dp))
                    .pointerInput(maxX, maxY) {
                        detectDragGestures(onDragEnd = { actions.onFabMoved(offset.x / maxX, offset.y / maxY) }) { change, drag ->
                            change.consume()
                            offset = Offset((offset.x + drag.x).coerceIn(0f, maxX), (offset.y + drag.y).coerceIn(0f, maxY))
                        }
                    }
                    .clickable { open = !open },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painterResource(R.drawable.ic_dashboard_loop_explain), contentDescription = stringResource(R.string.dashboard_loop_title),
                    tint = DashColors.Accent, modifier = Modifier.size(24.dp)
                )
            }
            dot?.let {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 3.dp, y = (-3).dp)
                        .size(14.dp)
                        .clip(CircleShape)
                        .background(DashColors.Bg)
                        .padding(2.dp)
                        .clip(CircleShape)
                        .background(it)
                )
            }
        }
    }
}

@Composable
private fun LoopPopover(decision: LoopDecision?, onClose: () -> Unit, onWhy: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xFF191527))
            .border(1.dp, AiPurple.copy(alpha = 0.45f), RoundedCornerShape(18.dp))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.ic_dashboard_loop_explain), contentDescription = null, tint = DashColors.Accent, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.dashboard_loop_title), color = DashColors.Text, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            decision?.let { Text(it.runTimeText, color = DashColors.Sub, fontSize = 11.sp) }
        }
        if (decision == null) {
            Text(stringResource(R.string.dashboard_loop_none), color = DashColors.Sub, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp))
        } else {
            val color = when (decision.kind) {
                DecisionKind.UP   -> DashColors.High
                DecisionKind.DOWN -> DashColors.Iob
                DecisionKind.NONE -> DashColors.Text
            }
            Text(decision.decision, color = color, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp))
            if (decision.summary.isNotEmpty())
                Text(decision.summary, color = DashColors.Text, fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 6.dp))
            if (decision.facts.isNotEmpty())
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .padding(top = 10.dp)
                        .horizontalScroll(rememberScrollState())
                ) {
                    decision.facts.forEach { (k, v) ->
                        Row(
                            Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(DashColors.Card2)
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text("$k ", color = DashColors.Sub, fontSize = 11.5.sp)
                            Text(v, color = DashColors.Text, fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 12.dp)) {
            PopoverButton(stringResource(R.string.dashboard_loop_close), DashColors.Card, DashColors.Sub, Modifier.weight(1f), onClose)
            if (decision != null)
                PopoverButton("✦ " + stringResource(R.string.dashboard_loop_why), AiPurple, Color(0xFF1B1230), Modifier.weight(1f), onWhy)
        }
    }
}

@Composable
private fun PopoverButton(text: String, bg: Color, fg: Color, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .height(42.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .border(1.dp, if (bg == DashColors.Card) DashColors.Line else bg, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = fg, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}
