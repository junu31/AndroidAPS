package app.aaps.plugins.main.general.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import app.aaps.plugins.main.R

private val AiPurple = Color(0xFFA78BFA)

/**
 * Small speech-bubble badge next to the BG value: marks the value as tappable (opens the last loop decision).
 * The dot shows the decision kind: yellow = more insulin / SMB, blue = less, grey = unchanged.
 */
@Composable
fun LoopDecisionBadge(decision: LoopDecision?, modifier: Modifier = Modifier) {
    val dot = when (decision?.kind) {
        DecisionKind.UP   -> DashColors.High
        DecisionKind.DOWN -> DashColors.Iob
        DecisionKind.NONE -> DashColors.Sub
        null              -> null
    }
    Box(modifier.size(20.dp)) {
        Icon(
            painterResource(R.drawable.ic_dashboard_loop_explain), contentDescription = stringResource(R.string.dashboard_loop_title),
            tint = DashColors.Accent, modifier = Modifier.size(18.dp)
        )
        dot?.let {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 2.dp, y = (-2).dp)
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(it)
            )
        }
    }
}

/** Last loop decision (local summary, no API call) shown as a dialog. */
@Composable
fun LoopDecisionDialog(decision: LoopDecision?, explain: DecisionExplain?, onExplain: () -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        LoopPopover(decision, explain?.takeIf { it.runTime == decision?.runTime }, onExplain, onDismiss)
    }
}

@Composable
private fun LoopPopover(decision: LoopDecision?, explain: DecisionExplain?, onExplain: () -> Unit, onClose: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xFF191527))
            .border(1.dp, AiPurple.copy(alpha = 0.45f), RoundedCornerShape(18.dp))
            .verticalScroll(rememberScrollState())
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
            // how eventual BG / lowest predicted BG came out; folds away once the AI flow is shown
            decision.math?.let { math ->
                var open by remember { mutableStateOf(true) }
                LaunchedEffect(explain != null) { if (explain != null) open = false }
                Row(
                    Modifier
                        .padding(top = 12.dp)
                        .fillMaxWidth()
                        .clickable { open = !open }, verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(stringResource(R.string.dashboard_loop_math_title) + if (open) " ▴" else " ▾", color = DashColors.Sub, fontSize = 11.sp)
                    Box(
                        Modifier
                            .padding(start = 8.dp)
                            .weight(1f)
                            .height(1.dp)
                            .background(DashColors.Line)
                    )
                }
                if (open) LoopMathSection(math)
            }
        }
        // AI explanation (on demand only): Gemini or the local model, see the Dashboard settings
        if (decision != null) when {
            explain == null || (explain.error && !explain.loading) -> {
                if (explain?.error == true) Text(explain.text, color = DashColors.Low, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp))
                PopoverButton(
                    stringResource(R.string.dashboard_loop_ai_flow), AiPurple.copy(alpha = 0.12f), Color(0xFFC4B5FD),
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                        .border(1.dp, AiPurple.copy(alpha = 0.5f), RoundedCornerShape(8.dp)), onExplain
                )
            }

            else                                                    -> Column(
                Modifier
                    .padding(top = 12.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(AiPurple.copy(alpha = 0.08f))
                    .border(1.dp, AiPurple.copy(alpha = 0.25f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.dashboard_loop_ai_flow_title), color = Color(0xFFC4B5FD), fontSize = 10.5.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text(if (explain.loading) stringResource(R.string.dashboard_loop_ai_loading) else explain.label, color = DashColors.Dim, fontSize = 10.5.sp)
                }
                if (explain.loading)
                    LinearProgressIndicator(
                        color = AiPurple, trackColor = DashColors.Card2,
                        modifier = Modifier
                            .padding(top = 8.dp)
                            .fillMaxWidth()
                    )
                else Text(explain.text, color = DashColors.Text, fontSize = 12.5.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 4.dp))
            }
        }
        PopoverButton(
            stringResource(R.string.dashboard_loop_close), DashColors.Card, DashColors.Sub,
            Modifier
                .fillMaxWidth()
                .padding(top = 12.dp), onClose
        )
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

@Composable
private fun MathBox(content: @Composable () -> Unit) {
    Column(
        Modifier
            .padding(top = 8.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(DashColors.Card2)
            .border(1.dp, DashColors.Line, RoundedCornerShape(12.dp))
            .padding(horizontal = 11.dp, vertical = 9.dp)
    ) { content() }
}

@Composable
private fun MathTitle(title: String, value: String, color: Color = DashColors.Text) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = DashColors.Sub, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Text(value, color = color, fontSize = 17.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun LoopMathSection(math: LoopMath) {
    MathBox {
        MathTitle(stringResource(R.string.dashboard_loop_math_eventual), math.eventual)
        math.eventualSteps.forEach { step ->
            Row(Modifier.padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(step.label, color = DashColors.Sub, fontSize = 11.5.sp, lineHeight = 15.sp, modifier = Modifier.weight(1f))
                Text(
                    step.value, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 8.dp),
                    color = when (step.kind) {
                        StepKind.DOWN  -> DashColors.Iob
                        StepKind.UP    -> DashColors.High
                        StepKind.PLAIN -> DashColors.Text
                    }
                )
            }
        }
        Text(math.deviationNote, color = DashColors.Dim, fontSize = 11.sp, lineHeight = 15.sp, modifier = Modifier.padding(top = 4.dp))
        Box(
            Modifier
                .padding(top = 5.dp)
                .fillMaxWidth()
                .height(1.dp)
                .background(DashColors.Line)
        )
        Row(Modifier.padding(top = 4.dp)) {
            Text(stringResource(R.string.dashboard_loop_math_sum), color = DashColors.Sub, fontSize = 11.5.sp, modifier = Modifier.weight(1f))
            Text(math.eventual, color = DashColors.Text, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
    }
    MathBox {
        MathTitle(stringResource(R.string.dashboard_loop_math_min), math.minPred, if (math.minPredWarn) DashColors.Low else DashColors.Text)
        if (math.curves.isNotEmpty()) PredictionChart(math)
        Text(math.minPredNote, color = DashColors.Text, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 6.dp))
    }
    if (math.verdicts.isNotEmpty()) {
        val warn = math.verdicts.any { it.second }
        Column(
            Modifier
                .padding(top = 8.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background((if (warn) DashColors.Low else DashColors.Accent).copy(alpha = 0.08f))
                .padding(horizontal = 10.dp, vertical = 7.dp)
        ) {
            math.verdicts.forEach { (text, bad) ->
                Text(text, color = if (bad) DashColors.Low else DashColors.Text, fontSize = 12.sp, lineHeight = 17.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

private fun PredKind.color() = when (this) {
    PredKind.IOB -> DashColors.Iob
    PredKind.COB -> DashColors.Cob
    PredKind.UAM -> DashColors.Uam
    PredKind.ZT  -> DashColors.Zt
}

/** prediction curves (5 min steps) with the target, the safety line and the lowest predicted point */
@Composable
private fun PredictionChart(math: LoopMath) {
    val all = math.curves.flatMap { it.values }
    val lo = minOf(all.minOrNull()?.toDouble() ?: 0.0, math.thresholdMgdl, math.minPredMgdl) - 8
    val hi = maxOf(all.maxOrNull()?.toDouble() ?: 0.0, math.targetMgdl) + 8
    val steps = (math.curves.maxOfOrNull { it.values.size } ?: 2).coerceAtLeast(2) - 1
    Canvas(
        Modifier
            .padding(top = 6.dp)
            .fillMaxWidth()
            .height(110.dp)
    ) {
        val x = { i: Int -> i / steps.toFloat() * size.width }
        val y = { v: Double -> ((hi - v) / (hi - lo) * size.height).toFloat() }
        val dash = PathEffect.dashPathEffect(floatArrayOf(8f, 8f))
        drawLine(DashColors.Target.copy(alpha = 0.6f), Offset(0f, y(math.targetMgdl)), Offset(size.width, y(math.targetMgdl)), 2f, pathEffect = dash)
        drawLine(DashColors.Low.copy(alpha = 0.6f), Offset(0f, y(math.thresholdMgdl)), Offset(size.width, y(math.thresholdMgdl)), 2f, pathEffect = dash)
        math.curves.forEach { c ->
            val path = Path()
            c.values.forEachIndexed { i, v -> if (i == 0) path.moveTo(x(i), y(v.toDouble())) else path.lineTo(x(i), y(v.toDouble())) }
            drawPath(path, c.kind.color(), style = Stroke(width = 4f))
        }
        // mark where the chosen curve reaches the lowest predicted value
        math.curves.asReversed().firstNotNullOfOrNull { c ->
            c.values.withIndex().drop(12).firstOrNull { kotlin.math.abs(it.value - math.minPredMgdl) < 1.5 }
                ?: c.values.withIndex().lastOrNull()?.takeIf { kotlin.math.abs(it.value - math.minPredMgdl) < 1.5 }
        }?.let { hit ->
            drawCircle(if (math.minPredWarn) DashColors.Low else DashColors.Text, 9f, Offset(x(hit.index), y(hit.value.toDouble())), style = Stroke(width = 3f))
        }
    }
    Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        math.curves.forEach { c ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(width = 10.dp, height = 2.dp)
                        .background(c.kind.color())
                )
                Text(" ${c.kind.name}", color = DashColors.Sub, fontSize = 10.sp)
            }
        }
        Spacer(Modifier.weight(1f))
        Text(stringResource(R.string.dashboard_loop_math_target, math.targetText), color = DashColors.Target, fontSize = 10.sp)
        Text(stringResource(R.string.dashboard_loop_math_threshold, math.thresholdText), color = DashColors.Low, fontSize = 10.sp)
    }
}
