package app.aaps.plugins.main.general.dashboard

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
        // AI explanation (on demand only): Gemini or the local model, see the Dashboard settings
        if (decision != null) when {
            explain == null || (explain.error && !explain.loading) -> {
                if (explain?.error == true) Text(explain.text, color = DashColors.Low, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp))
                PopoverButton(
                    stringResource(R.string.dashboard_loop_ai), AiPurple.copy(alpha = 0.12f), Color(0xFFC4B5FD),
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
                    Text(stringResource(R.string.dashboard_loop_ai_title), color = Color(0xFFC4B5FD), fontSize = 10.5.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
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
