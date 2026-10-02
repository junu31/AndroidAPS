package app.aaps.plugins.main.general.dashboard

import androidx.annotation.DrawableRes
import android.content.Context
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.core.content.edit
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
    fun onWeeklyReview()
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
                .padding(start = ScreenPadding, end = ScreenPadding, top = 0.dp, bottom = if (state.pumpStatus.isNotEmpty()) 156.dp else 116.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // notifications (often empty) and the compact profile/target line sit tight above the BG card,
            // so an empty notification slot does not add a full section gap under the tabs
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                notifications()
                Ribbons(state, actions)
                HeroPager(state, actions)
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
            state.weeklyReviewLast?.let { WeeklyReviewCard(it, actions) }
            state.buttons.acceptTemp?.let { AcceptTempButton(it, actions) }
            if (state.buttons.userActions.isNotEmpty() || state.buttons.quickWizard != null || state.buttons.calibration || state.buttons.cgm || state.buttons.treatment)
                SecondaryActions(state.buttons, actions)
        }
        BottomActions(state, actions, Modifier.align(Alignment.BottomCenter))
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
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF14243A))
            .border(1.dp, Color(0xFF1F3B5E), RoundedCornerShape(8.dp))
            .clicks({ actions.onPumpStatusClick() })
            .padding(horizontal = 12.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.Center
    ) {
        Text(text, color = DashColors.Iob, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
        // compact layout (option A): smaller BG / arrow, tighter paddings
        Column(Modifier.padding(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(bg.timeAgo, color = DashColors.Sub, fontSize = 12.sp, modifier = Modifier.weight(1f))
                state.loop?.let { LoopPill(it, actions) }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                // left: BG value + trend arrow
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    // tapping the BG value opens the last loop decision; the small bubble marks it as tappable
                    var showDecision by remember { mutableStateOf(false) }
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { showDecision = true }
                            .padding(end = 2.dp)
                    ) {
                        Text(
                            bg.value,
                            color = color,
                            fontSize = 58.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = (-2).sp,
                            maxLines = 1,
                            textDecoration = if (bg.isActual) null else TextDecoration.LineThrough
                        )
                        LoopDecisionBadge(state.loopDecision, Modifier.padding(start = 2.dp, top = 6.dp))
                    }
                    if (showDecision) LoopDecisionDialog(state.loopDecision) { showDecision = false }
                    Spacer(Modifier.width(10.dp))
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        bg.arrowRes?.let {
                            // rounded-square tile like the buttons; the faster the change, the stronger the tint
                            val tint = listOf(0.12f, 0.18f, 0.28f, 0.45f)[bg.trendLevel.coerceIn(0, 3)]
                            Box(
                                Modifier
                                    .size(36.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(color.copy(alpha = tint))
                                    .border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(8.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(painterResource(it), contentDescription = bg.arrowDescription, tint = color, modifier = Modifier.size(24.dp))
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
                Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.width(132.dp)) {
                    DeltaBar(stringResource(R.string.dashboard_delta_5), bg.delta, bg.deltasMgdl[0])
                    DeltaBar(stringResource(R.string.dashboard_delta_15), bg.shortAvgDelta, bg.deltasMgdl[1])
                    DeltaBar(stringResource(R.string.dashboard_delta_40), bg.longAvgDelta, bg.deltasMgdl[2])
                }
            }
            HeroStats(state, actions)
        }
    }
}

private const val PREFS_UI = "dashboard_ui"
private const val KEY_HERO_PAGE = "hero_page"

/** BG card and the ring card side by side; swipe to switch, the last shown card is kept across restarts. */
@Composable
private fun HeroPager(state: DashboardState, actions: DashboardActions) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS_UI, Context.MODE_PRIVATE) }
    val pagerState = rememberPagerState(initialPage = prefs.getInt(KEY_HERO_PAGE, 0).coerceIn(0, 1)) { 2 }
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { prefs.edit { putInt(KEY_HERO_PAGE, it) } }
    }
    // the main tabs are a ViewPager2: keep it from taking the swipe while the finger is on the card
    val view = LocalView.current
    // the cards differ in height: the pager follows the visible card, blending while swiping
    val heights = remember { mutableStateMapOf<Int, Int>() }
    val h0 = heights[0]
    val h1 = heights[1]
    val heightModifier = if (h0 != null && h1 != null) {
        val f = (pagerState.currentPage + pagerState.currentPageOffsetFraction).coerceIn(0f, 1f)
        Modifier.height(with(LocalDensity.current) { (h0 + (h1 - h0) * f).toDp() })
    } else Modifier
    Column {
        HorizontalPager(
            state = pagerState,
            pageSpacing = 12.dp,
            beyondViewportPageCount = 1,
            verticalAlignment = Alignment.Top,
            modifier = heightModifier.pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    view.parent?.requestDisallowInterceptTouchEvent(true)
                }
            }
        ) { page ->
            Box(
                Modifier
                    .wrapContentHeight(Alignment.Top, unbounded = true)
                    .onSizeChanged { heights[page] = it.height }
            ) {
                if (page == 0) HeroCard(state, actions) else RingCard(state, actions)
            }
        }
        PageDots(pagerState.currentPage, 2)
    }
}

@Composable
private fun PageDots(current: Int, count: Int) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)
    ) {
        repeat(count) { i ->
            Box(
                Modifier
                    .height(6.dp)
                    .width(if (i == current) 16.dp else 6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(if (i == current) DashColors.Accent else DashColors.Dim)
            )
        }
    }
}

/**
 * Second card: BG inside the loop icon with the trend circle (option B, colors follow the BG range).
 * Split by grid lines: ring | right side, right side = delta bars / 2x2 IOB, COB, Basal, Sens.
 */
@Composable
private fun RingCard(state: DashboardState, actions: DashboardActions) {
    val bg = state.bg
    val color = bg.range.color()
    CardBox {
        Box(
            Modifier
                .matchParentSize()
                .background(Brush.radialGradient(listOf(color.copy(alpha = 0.18f), Color.Transparent), radius = 600f, center = Offset(0f, 0f)))
        )
        Row(Modifier.height(IntrinsicSize.Min)) {
            // tapping the BG opens the last loop decision, like on the first card
            var showDecision by remember { mutableStateOf(false) }
            Box(Modifier.padding(6.dp)) {
                BgRing(
                    bg, color, state.loop, 156.dp,
                    Modifier
                        .clip(RoundedCornerShape(78.dp))
                        .clickable { showDecision = true }
                )
                // top left stays free: the trend triangles never point there
                LoopDecisionBadge(state.loopDecision, Modifier.padding(start = 4.dp, top = 4.dp))
            }
            if (showDecision) LoopDecisionDialog(state.loopDecision) { showDecision = false }
            GridLineV()
            Column(Modifier.weight(1f)) {
                Column(
                    Modifier.padding(start = 10.dp, end = 12.dp, top = 8.dp, bottom = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    DeltaBar(stringResource(R.string.dashboard_delta_5), bg.delta, bg.deltasMgdl[0])
                    DeltaBar(stringResource(R.string.dashboard_delta_15), bg.shortAvgDelta, bg.deltasMgdl[1])
                    DeltaBar(stringResource(R.string.dashboard_delta_40), bg.longAvgDelta, bg.deltasMgdl[2])
                }
                GridLineH()
                Row(Modifier.weight(1f)) {
                    GridStat("IOB", DashColors.Iob, state.iob, actions)
                    GridLineV()
                    GridStat("COB", DashColors.Cob, state.cob, actions)
                }
                GridLineH()
                Row(Modifier.weight(1f)) {
                    GridStat("Basal", DashColors.Basal, state.basal, actions)
                    GridLineV()
                    GridStat(stringResource(R.string.dashboard_sens), DashColors.Zt, state.sensitivity, actions)
                }
            }
        }
    }
}

@Composable
private fun GridLineV() {
    Box(
        Modifier
            .width(1.dp)
            .fillMaxHeight()
            .background(DashColors.Line)
    )
}

@Composable
private fun GridLineH() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(DashColors.Line)
    )
}

@Composable
private fun RowScope.GridStat(label: String, accent: Color, tile: InfoTile, actions: DashboardActions) {
    Column(
        Modifier
            .weight(1f)
            .fillMaxHeight()
            .clicks({ if (tile.dialogText.isNotEmpty()) actions.showInfo(tile.dialogTitle, tile.dialogText) })
            .padding(horizontal = 4.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(7.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(accent)
            )
            Spacer(Modifier.width(4.dp))
            Text(label, color = DashColors.Sub, fontSize = 10.5.sp, maxLines = 1)
        }
        Text(
            tile.value.ifEmpty { "–" }, color = DashColors.Text, fontSize = 14.sp, fontWeight = FontWeight.Bold,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp)
        )
    }
}

/** Full bar = this change in mg/dL per 5 minutes. */
private const val DELTA_FULL_SCALE_MGDL = 10.0

/** Delta as a bar from a centre zero line (option A): rising goes right in yellow, falling goes left in blue. */
@Composable
private fun DeltaBar(label: String, value: String, mgdl: Double?) {
    val color = when {
        mgdl == null || mgdl == 0.0 -> DashColors.Sub
        mgdl > 0                    -> DashColors.High
        else                        -> DashColors.Iob
    }
    val fraction = ((mgdl ?: 0.0) / DELTA_FULL_SCALE_MGDL).coerceIn(-1.0, 1.0).toFloat()
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = DashColors.Dim, fontSize = 10.5.sp, maxLines = 1, modifier = Modifier.width(40.dp))
        Canvas(
            Modifier
                .weight(1f)
                .height(10.dp)
        ) {
            val r = CornerRadius(size.height / 2)
            drawRoundRect(DashColors.Card2, cornerRadius = r)
            val mid = size.width / 2
            val w = mid * kotlin.math.abs(fraction)
            if (w > 0f)
                drawRoundRect(color, topLeft = Offset(if (fraction > 0) mid else mid - w, 0f), size = Size(w, size.height), cornerRadius = r)
            drawLine(DashColors.Sub.copy(alpha = 0.6f), Offset(mid, -3.dp.toPx()), Offset(mid, size.height + 3.dp.toPx()), 1.dp.toPx())
        }
        Text(
            value.ifEmpty { "–" }, color = color, fontSize = 12.5.sp, fontWeight = FontWeight.Bold, maxLines = 1,
            textAlign = TextAlign.End, modifier = Modifier.width(34.dp)
        )
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
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(painterResource(loop.iconRes), contentDescription = loop.label, tint = Color.Unspecified, modifier = Modifier.size(16.dp))
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
            .padding(top = 8.dp)
            .fillMaxWidth()
            .height(1.dp)
            .background(DashColors.Line)
    )
    Row(
        Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
    ) {
        HeroStat("IOB", DashColors.Iob, state.iob, actions)
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
private fun RowScope.HeroStat(label: String, accent: Color, tile: InfoTile, actions: DashboardActions) {
    Column(
        Modifier
            .weight(1f)
            .clicks({ if (tile.dialogText.isNotEmpty()) actions.showInfo(tile.dialogTitle, tile.dialogText) })
            .padding(start = 10.dp, end = 4.dp, top = 8.dp, bottom = 2.dp)
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
            textAlign = TextAlign.Center,
            fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 3.dp)
        )
        if (tile.sub.isNotEmpty())
            Text(
                tile.sub, color = if (tile.highlight) accent else DashColors.Dim, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()
            )
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
        listOf(3, 6, 12, 18, 24).forEach { h ->
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

/** Entry to the weekly AI review (Autotune of the last 7 days), shown only when Autotune is enabled. */
@Composable
private fun WeeklyReviewCard(last: String, actions: DashboardActions) {
    val purple = Color(0xFFA78BFA)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Brush.linearGradient(listOf(purple.copy(alpha = 0.14f), DashColors.Accent.copy(alpha = 0.08f))))
            .border(1.dp, purple.copy(alpha = 0.45f), RoundedCornerShape(16.dp))
            .clicks({ actions.onWeeklyReview() })
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(purple.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center
        ) { Icon(painterResource(R.drawable.ic_dashboard_ai), contentDescription = null, tint = purple, modifier = Modifier.size(20.dp)) }
        Column(
            Modifier
                .weight(1f)
                .padding(start = 12.dp)
        ) {
            Text(stringResource(R.string.dashboard_weekly_review), color = DashColors.Text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text(
                if (last.isEmpty()) stringResource(R.string.dashboard_weekly_review_never) else stringResource(R.string.dashboard_weekly_review_last, last),
                color = DashColors.Sub, fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            stringResource(R.string.dashboard_weekly_review_open), color = Color(0xFF1B1230), fontSize = 12.5.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(purple)
                .padding(horizontal = 12.dp, vertical = 8.dp)
        )
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
private fun BottomActions(state: DashboardState, actions: DashboardActions, modifier: Modifier) {
    val buttons = state.buttons
    Column(
        modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, DashColors.Bg, DashColors.Bg)))
            .padding(start = 14.dp, end = 14.dp, top = 16.dp, bottom = 12.dp)
    ) {
        // pump communication status and calculation progress right above the buttons, like the classic Overview
        if (state.pumpStatus.isNotEmpty()) {
            PumpStatusBanner(state.pumpStatus, actions)
            Spacer(Modifier.height(8.dp))
        }
        if (state.calcProgressPct != 100) {
            LinearProgressIndicator(
                progress = { state.calcProgressPct / 100f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp),
                color = DashColors.Accent,
                trackColor = DashColors.Card2
            )
            Spacer(Modifier.height(8.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (buttons.insulin)
                ActionButton(
                    stringResource(app.aaps.core.ui.R.string.overview_insulin_label), app.aaps.core.objects.R.drawable.ic_bolus,
                    if (buttons.insulinWarning) DashColors.High else DashColors.Iob, DashColors.Card
                ) { actions.onInsulin() }
            if (buttons.carbs)
                // the carbs drawable has more inner padding than the others: scale the drawing (same 28dp slot keeps labels aligned)
                ActionButton(stringResource(app.aaps.core.ui.R.string.carbs), app.aaps.core.objects.R.drawable.ic_cp_bolus_carbs, DashColors.Cob, DashColors.Card, iconScale = 1.45f) { actions.onCarbs() }
            if (buttons.aiCarbs)
                ActionButton(stringResource(R.string.dashboard_ai), R.drawable.ic_dashboard_ai, DashColors.Basal, DashColors.Card) { actions.onAiCarbs() }
            if (buttons.wizard)
                ActionButton(stringResource(R.string.calculator_label), app.aaps.core.objects.R.drawable.ic_calculator, Color(0xFF062521), DashColors.Accent) { actions.onWizard() }
        }
    }
}

@Composable
private fun RowScope.ActionButton(text: String, @DrawableRes icon: Int, fg: Color, bg: Color, iconScale: Float = 1f, onClick: () -> Unit) {
    // icon on top, label below (option A)
    Column(
        Modifier
            .weight(1f)
            .height(72.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .border(1.dp, if (bg == DashColors.Card) DashColors.Line else bg, RoundedCornerShape(8.dp))
            .clicks(onClick),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            painterResource(icon), contentDescription = null, tint = fg,
            modifier = Modifier
                .size(28.dp)
                .scale(iconScale)
        )
        Spacer(Modifier.height(5.dp))
        Text(text, color = fg, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}
