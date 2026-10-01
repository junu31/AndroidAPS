package app.aaps.plugins.main.general.dashboard

import androidx.annotation.DrawableRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.layout
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.aaps.plugins.main.R

object DashColors {

    val Bg = Color(0xFF0E1116)
    val Card = Color(0xFF171B22)
    val Card2 = Color(0xFF1E232C)
    val Line = Color(0xFF2A303B)
    val Grid = Color(0xFF3A4250)
    val Warn = Color(0xFFFDE047)
    val Text = Color(0xFFE8ECF2)
    val Sub = Color(0xFF8A93A3)
    val Dim = Color(0xFF5C6575)
    val InRange = Color(0xFF34D399)
    val Low = Color(0xFFF87171)
    val High = Color(0xFFFBBF24)
    val Iob = Color(0xFF60A5FA)
    val Cob = Color(0xFFF59E0B)
    val Basal = Color(0xFFA78BFA)
    val Uam = Color(0xFFF472B6)
    val Zt = Color(0xFF22D3EE)
    val Target = Color(0xFF2DD4BF)
    val Activity = Color(0xFFFDE68A)
    val Accent = Color(0xFF2DD4BF)
}

private val ScreenPadding = 14.dp

interface DashboardActions {

    fun onProfileClick()
    fun onProfileLongClick()
    fun onTempTargetClick()
    fun onLoopClick()
    fun onLoopLongClick()
    fun onBgQualityClick()
    fun onPumpStatusClick()
    fun onInsulin()
    fun onCarbs()
    fun onWizard()
    fun onAiCarbs()
    fun onTreatment()
    fun onQuickWizard()
    fun onQuickWizardLong()
    fun onCalibration()
    fun onCgm()
    fun onAcceptTemp()
    fun onScale(hours: Int)
    fun showInfo(title: String, text: String)
}

@Composable
fun DashboardScreen(
    state: DashboardState,
    graph: GraphModel,
    actions: DashboardActions,
    notifications: @Composable () -> Unit
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(DashColors.Bg)
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = ScreenPadding, end = ScreenPadding, top = 0.dp, bottom = 100.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (state.calcProgressPct != 100)
                LinearProgressIndicator(
                    progress = { state.calcProgressPct / 100f },
                    modifier = Modifier.fillMaxWidth(),
                    color = DashColors.Accent,
                    trackColor = DashColors.Card2
                )
            notifications()
            if (state.pumpStatus.isNotEmpty()) PumpStatusBanner(state.pumpStatus, actions)
            // the compact profile/target line belongs to the BG card, so keep it close
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Ribbons(state, actions)
                HeroCard(state, actions)
            }
            GraphCard(graph, actions)
            if (state.statusLights.isNotEmpty()) {
                SectionTitle(stringResource(R.string.dashboard_supplies))
                StatusLights(state.statusLights)
                if (state.stats != null) SectionDivider()
            }
            state.stats?.let {
                SectionTitle(stringResource(R.string.dashboard_glucose_stats))
                StatsCard(it)
            }
            state.buttons.acceptTemp?.let { AcceptTempButton(it, actions) }
            if (state.buttons.userActions.isNotEmpty() || state.buttons.quickWizard != null || state.buttons.calibration || state.buttons.cgm || state.buttons.treatment)
                SecondaryActions(state.buttons, actions)
        }
        BottomActions(state.buttons, actions, Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun SectionTitle(text: String) {
    // same style and left edge as the graph's "BG" title
    Text(text, color = DashColors.Text, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 4.dp, top = 4.dp))
}

@Composable
private fun CardBox(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(DashColors.Card)
            .border(1.dp, DashColors.Line, RoundedCornerShape(22.dp))
    ) { content() }
}

private fun Severity.color(): Color = when (this) {
    Severity.OK       -> DashColors.InRange
    Severity.WARNING  -> DashColors.High
    Severity.CRITICAL -> DashColors.Low
    Severity.NEUTRAL  -> DashColors.Text
}

private fun BgRange.color(): Color = when (this) {
    BgRange.LOW      -> DashColors.Low
    BgRange.IN_RANGE -> DashColors.InRange
    BgRange.HIGH     -> DashColors.High
    BgRange.UNKNOWN  -> DashColors.Sub
}

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.clicks(onClick: () -> Unit, onLongClick: (() -> Unit)? = null) =
    combinedClickable(onClick = onClick, onLongClick = onLongClick)

@Composable
private fun PumpStatusBanner(text: String, actions: DashboardActions) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF14243A))
            .border(1.dp, Color(0xFF1F3B5E), RoundedCornerShape(14.dp))
            .clicks({ actions.onPumpStatusClick() })
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Text(text, color = DashColors.Iob, fontSize = 13.sp)
    }
}

/** Profile (left) and target (right) as one compact text line above the BG card (option A-1). Same click actions as before. */
@Composable
private fun Ribbons(state: DashboardState, actions: DashboardActions) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        CompactRibbon(
            icon = app.aaps.core.ui.R.drawable.ic_ribbon_profile,
            description = stringResource(R.string.dashboard_profile),
            info = state.profile,
            modifier = Modifier.weight(1f),
            onClick = { actions.onProfileClick() },
            onLongClick = { actions.onProfileLongClick() }
        )
        CompactRibbon(
            icon = R.drawable.ic_crosstarget,
            description = stringResource(R.string.dashboard_target),
            info = state.target,
            modifier = Modifier,
            onClick = { actions.onTempTargetClick() },
            onLongClick = { actions.onTempTargetClick() }
        )
    }
}

@Composable
private fun CompactRibbon(@DrawableRes icon: Int, description: String, info: RibbonInfo, modifier: Modifier, onClick: () -> Unit, onLongClick: () -> Unit) {
    val color = if (info.severity == Severity.NEUTRAL) DashColors.Text else info.severity.color()
    Row(
        modifier
            .height(32.dp)
            .clip(RoundedCornerShape(8.dp))
            .clicks(onClick, onLongClick)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(painterResource(icon), contentDescription = description, tint = DashColors.Sub, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            info.text, color = color, fontSize = 13.sp,
            fontWeight = if (info.severity == Severity.NEUTRAL) FontWeight.SemiBold else FontWeight.Bold,
            maxLines = 1, overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun HeroCard(state: DashboardState, actions: DashboardActions) {
    val bg = state.bg
    val color = bg.range.color()
    CardBox {
        Box(
            Modifier
                .matchParentSize()
                .background(Brush.radialGradient(listOf(color.copy(alpha = 0.18f), Color.Transparent), radius = 600f, center = Offset(900f, 0f)))
        )
        Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(bg.timeAgo, color = DashColors.Sub, fontSize = 12.sp, modifier = Modifier.weight(1f))
                state.loop?.let { LoopPill(it, actions) }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                // left: BG value + trend arrow
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Text(
                        bg.value,
                        color = color,
                        fontSize = 72.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = (-2).sp,
                        maxLines = 1,
                        textDecoration = if (bg.isActual) null else TextDecoration.LineThrough
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        bg.arrowRes?.let {
                            Box(
                                Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .background(color.copy(alpha = 0.15f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(painterResource(it), contentDescription = bg.arrowDescription, tint = color, modifier = Modifier.size(30.dp))
                            }
                        }
                        if (bg.qualityIcon != 0)
                            Icon(
                                painterResource(bg.qualityIcon), contentDescription = bg.qualityMessage, tint = Color.Unspecified,
                                modifier = Modifier
                                    .padding(top = 6.dp)
                                    .size(20.dp)
                                    .clicks({ actions.onBgQualityClick() })
                            )
                    }
                }
                // right: Δ 5 / 15 / 40 min stacked
                Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.width(108.dp)) {
                    DeltaRow(stringResource(R.string.dashboard_delta_5), bg.delta)
                    DeltaRow(stringResource(R.string.dashboard_delta_15), bg.shortAvgDelta)
                    DeltaRow(stringResource(R.string.dashboard_delta_40), bg.longAvgDelta)
                }
            }
            HeroStats(state, actions)
        }
    }
}

@Composable
private fun DeltaRow(label: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(DashColors.Card2)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = DashColors.Dim, fontSize = 11.sp, modifier = Modifier.weight(1f))
        Text(value.ifEmpty { "–" }, color = DashColors.Text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun LoopPill(loop: LoopInfo, actions: DashboardActions) {
    val c = loop.severity.color()
    Row(
        Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(c.copy(alpha = 0.12f))
            .clicks({ actions.onLoopClick() }, { actions.onLoopLongClick() })
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(painterResource(loop.iconRes), contentDescription = loop.label, tint = Color.Unspecified, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            if (loop.extra.isNotEmpty()) "${loop.label} · ${loop.extra}" else loop.label,
            color = c, fontSize = 12.sp, fontWeight = FontWeight.SemiBold
        )
    }
}

/** IOB / COB / Basal / Sens in one row under a thin divider inside the BG card (option A). */
@Composable
private fun HeroStats(state: DashboardState, actions: DashboardActions) {
    Box(
        Modifier
            .padding(top = 12.dp)
            .fillMaxWidth()
            .height(1.dp)
            .background(DashColors.Line)
    )
    Row(
        Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
    ) {
        HeroStat("IOB", DashColors.Iob, state.iob, actions, first = true)
        StatDivider()
        HeroStat("COB", DashColors.Cob, state.cob, actions)
        StatDivider()
        HeroStat("Basal", DashColors.Basal, state.basal, actions)
        StatDivider()
        HeroStat(stringResource(R.string.dashboard_sens), DashColors.Zt, state.sensitivity, actions)
    }
    state.extended?.let { ext ->
        Row(
            Modifier
                .fillMaxWidth()
                .clicks({ if (ext.dialogText.isNotEmpty()) actions.showInfo(ext.dialogTitle, ext.dialogText) })
                .padding(top = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(stringResource(app.aaps.core.ui.R.string.extended_bolus), color = DashColors.Sub, fontSize = 11.sp, modifier = Modifier.weight(1f))
            Text(ext.value, color = DashColors.Text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun StatDivider() {
    Box(
        Modifier
            .padding(top = 10.dp)
            .width(1.dp)
            .fillMaxHeight()
            .background(DashColors.Line)
    )
}

@Composable
private fun RowScope.HeroStat(label: String, accent: Color, tile: InfoTile, actions: DashboardActions, first: Boolean = false) {
    Column(
        Modifier
            .weight(1f)
            .clicks({ if (tile.dialogText.isNotEmpty()) actions.showInfo(tile.dialogTitle, tile.dialogText) })
            .padding(start = if (first) 0.dp else 10.dp, end = 4.dp, top = 10.dp, bottom = 2.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(7.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(accent)
            )
            Spacer(Modifier.width(5.dp))
            Text(label, color = DashColors.Sub, fontSize = 11.sp, maxLines = 1)
        }
        Text(
            tile.value.ifEmpty { "–" }, color = DashColors.Text, fontSize = if (tile.value.length > 8) 13.sp else 15.sp, lineHeight = 17.sp,
            fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp)
        )
        if (tile.sub.isNotEmpty())
            Text(tile.sub, color = if (tile.highlight) accent else DashColors.Dim, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Edge-to-edge graph (no card): breaks out of the screen's 14dp side padding so the plot uses the full width. */
@Composable
private fun GraphCard(graph: GraphModel, actions: DashboardActions) {
    Column(
        Modifier.layout { measurable, constraints ->
            val extra = (ScreenPadding * 2).roundToPx()
            val width = constraints.maxWidth + extra
            val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
            layout(constraints.maxWidth, placeable.height) { placeable.place(-extra / 2, 0) }
        }
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 18.dp, end = 14.dp)) {
            Text(stringResource(R.string.dashboard_bg), color = DashColors.Text, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            ScaleSelector(graph.rangeHours, actions)
        }
        BgChart(graph, Modifier.padding(top = 6.dp))
        // only the prediction lines need a legend (BG and basal are self-explanatory)
        if (graph.predictions.isNotEmpty())
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .padding(start = 18.dp, top = 4.dp)
                    .horizontalScroll(rememberScrollState())
            ) {
                if (graph.predictions.containsKey(PredictionKind.IOB)) Legend("IOB", DashColors.Iob)
                if (graph.predictions.containsKey(PredictionKind.COB)) Legend("COB", DashColors.Cob)
                if (graph.predictions.containsKey(PredictionKind.UAM)) Legend("UAM", DashColors.Uam)
                if (graph.predictions.containsKey(PredictionKind.ZT)) Legend("ZT", DashColors.Zt)
            }
    }
    SectionDivider()
}

/** Thin line between card-less sections (option B: only the BG card keeps a card). */
@Composable
private fun SectionDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(DashColors.Line)
    )
}

@Composable
private fun ScaleSelector(current: Int, actions: DashboardActions) {
    Row(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(DashColors.Card2)
            .padding(3.dp)
    ) {
        listOf(6, 12, 18, 24).forEach { h ->
            val on = h == current
            Text(
                "${h}h",
                color = if (on) DashColors.Text else DashColors.Sub,
                fontSize = 12.sp,
                fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (on) Color(0xFF2B3340) else Color.Transparent)
                    .clicks({ actions.onScale(h) })
                    .padding(horizontal = 9.dp, vertical = 4.dp)
            )
        }
    }
}

@Composable
private fun Legend(text: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .width(12.dp)
                .height(3.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(color)
        )
        Spacer(Modifier.width(4.dp))
        Text(text, color = DashColors.Sub, fontSize = 11.sp)
    }
}

@Composable
private fun StatusLights(items: List<StatusLight>) {
    // no surrounding card; the tiles keep their own background
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items.forEach { item ->
            // neutral tiles: subtle outline; items that need attention get a tinted background and border
            val (tileBg, tileBorder) = when (item.severity) {
                Severity.CRITICAL -> DashColors.Low.copy(alpha = 0.10f) to DashColors.Low.copy(alpha = 0.45f)
                Severity.WARNING  -> DashColors.Warn.copy(alpha = 0.08f) to DashColors.Warn.copy(alpha = 0.35f)
                else              -> DashColors.Card to DashColors.Line
            }
            Column(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(tileBg)
                    .border(1.dp, tileBorder, RoundedCornerShape(14.dp))
                    .padding(vertical = 8.dp, horizontal = 2.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(painterResource(item.iconRes), contentDescription = item.label, tint = DashColors.Text.copy(alpha = 0.85f), modifier = Modifier.size(20.dp))
                Text(item.label, color = DashColors.Dim, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
                Text(
                    item.value.trim().ifEmpty { "–" },
                    color = item.color?.let { Color(it) } ?: DashColors.Text,
                    fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                // always reserve the sub line so every supply tile has the same height
                Text(item.sub.trim(), color = item.subColor?.let { Color(it) } ?: DashColors.Sub, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun AcceptTempButton(text: String, actions: DashboardActions) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xFF2A2114))
            .border(1.dp, Color(0xFF4A3A1C), RoundedCornerShape(18.dp))
            .clicks({ actions.onAcceptTemp() })
            .padding(14.dp)
    ) {
        Text(text, color = DashColors.High, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SecondaryActions(buttons: Buttons, actions: DashboardActions) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        buttons.quickWizard?.let { ChipButton(it, app.aaps.core.objects.R.drawable.ic_quick_wizard, DashColors.Accent, { actions.onQuickWizard() }, { actions.onQuickWizardLong() }) }
        if (buttons.treatment) ChipButton(stringResource(app.aaps.core.ui.R.string.overview_treatment_label), app.aaps.core.objects.R.drawable.icon_insulin_carbs, DashColors.Text, { actions.onTreatment() })
        if (buttons.cgm) ChipButton(stringResource(R.string.overview_cgm), buttons.cgmIcon, DashColors.Text, { actions.onCgm() })
        if (buttons.calibration) ChipButton(stringResource(app.aaps.core.ui.R.string.calibration), app.aaps.core.objects.R.drawable.ic_calibration, DashColors.Text, { actions.onCalibration() })
        buttons.userActions.forEach { ua -> ChipButton(ua.title, ua.iconRes, DashColors.Basal, { ua.run() }) }
    }
}

@Composable
private fun ChipButton(text: String, @DrawableRes icon: Int, color: Color, onClick: () -> Unit, onLongClick: (() -> Unit)? = null) {
    Row(
        Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(DashColors.Card)
            .border(1.dp, DashColors.Line, RoundedCornerShape(14.dp))
            .clicks(onClick, onLongClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != 0) {
            Icon(painterResource(icon), contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
    }
}

@Composable
private fun BottomActions(buttons: Buttons, actions: DashboardActions, modifier: Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, DashColors.Bg, DashColors.Bg)))
            .padding(start = 14.dp, end = 14.dp, top = 16.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (buttons.insulin)
            ActionButton(
                stringResource(app.aaps.core.ui.R.string.overview_insulin_label), app.aaps.core.objects.R.drawable.ic_bolus,
                if (buttons.insulinWarning) DashColors.High else DashColors.Iob, DashColors.Card
            ) { actions.onInsulin() }
        if (buttons.carbs)
            ActionButton(stringResource(app.aaps.core.ui.R.string.carbs), app.aaps.core.objects.R.drawable.ic_cp_bolus_carbs, DashColors.Cob, DashColors.Card) { actions.onCarbs() }
        if (buttons.aiCarbs)
            ActionButton(stringResource(R.string.dashboard_ai), R.drawable.ic_dashboard_ai, DashColors.Basal, DashColors.Card) { actions.onAiCarbs() }
        if (buttons.wizard)
            ActionButton(stringResource(R.string.calculator_label), app.aaps.core.objects.R.drawable.ic_calculator, Color(0xFF062521), DashColors.Accent) { actions.onWizard() }
    }
}

@Composable
private fun RowScope.ActionButton(text: String, @DrawableRes icon: Int, fg: Color, bg: Color, onClick: () -> Unit) {
    Row(
        Modifier
            .weight(1f)
            .height(56.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .border(1.dp, if (bg == DashColors.Card) DashColors.Line else bg, RoundedCornerShape(8.dp))
            .clicks(onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = fg, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, color = fg, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}
