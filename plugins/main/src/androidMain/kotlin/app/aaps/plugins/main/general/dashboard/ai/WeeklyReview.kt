package app.aaps.plugins.main.general.dashboard.ai

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.interfaces.autotune.Autotune
import app.aaps.core.interfaces.autotune.AutotuneSummary
import app.aaps.core.interfaces.autotune.AutotuneTrace
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.di.ApplicationScope
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.MidnightTime
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.main.R
import app.aaps.plugins.main.general.dashboard.DashColors
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Personal-fork (Dashboard weekly review): runs Autotune for the last 7 days, shows the suggested basal / ISF / IC next
 * to the current values, lets the AI explain the suggestion (advisory text only) and offers the same profile actions as
 * the Autotune screen. Nothing is activated: the user still has to do a profile switch.
 */
@Inject
class WeeklyReview(
    private val context: Context,
    private val aapsLogger: AAPSLogger,
    private val rh: ResourceHelper,
    private val preferences: Preferences,
    private val dateUtil: DateUtil,
    private val profileUtil: ProfileUtil,
    private val persistenceLayer: PersistenceLayer,
    private val autotune: Autotune,
    private val aiTextEngine: AiTextEngine,
    @ApplicationScope private val appScope: CoroutineScope
) {

    private companion object {

        const val DAYS = 7
        const val T_DAY = 24 * 60 * 60 * 1000L

        // The AI explanation of the last Autotune run is stored, so reopening or restarting shows it without another call.
        const val PREFS = "weekly_review"
        const val KEY_AI_RUN = "ai_run_time"
        const val KEY_AI_TEXT = "ai_text"

        const val SYSTEM_PROMPT =
            """You summarise one week of AndroidAPS Autotune results for the user in Korean.
The screen already shows every value and the reason for each change, so do NOT repeat them one by one.
You get the changes with their reasons (from the Autotune data) and data quality notes per day.
Write 2 to 4 short bullet lines starting with "- ", plain Korean (존댓말), no headings, no markdown other than the bullets.
Rules:
- Describe the week's patterns: e.g. BG rising at dawn without meals, meals ending high, hours without enough data, values stopped at a limit.
- Point out what is worth watching next week, and when the data quality makes the result less reliable.
- Do NOT tell the user to apply the changes, do NOT give doses or medical advice.
- Only use the numbers given."""
    }

    private var summary by mutableStateOf<AutotuneSummary?>(null)
    private var quality by mutableStateOf<List<WeeklyReviewMath.DayQuality>>(emptyList())
    private var running by mutableStateOf(false)
    private var failed by mutableStateOf(false)
    private var aiText by mutableStateOf<String?>(null)
    private var aiBusy by mutableStateOf(false)
    private var aiDone by mutableStateOf(false)
    private var ask by mutableStateOf<Triple<String, String, () -> Unit>?>(null)
    private var note by mutableStateOf<Pair<String, String>?>(null)
    private var modelStatus by mutableStateOf<String?>(null)
    private var modelCopying by mutableStateOf(false)
    private lateinit var scope: CoroutineScope

    private val aiPrefs by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    @Composable
    fun Content(onClose: () -> Unit) {
        scope = rememberCoroutineScope()
        val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) scope.launch { copyModel(uri) } }
        LaunchedEffect(Unit) {
            quality = runCatching { loadQuality() }.onFailure { aapsLogger.error(LTag.UI, "Weekly review quality", it) }.getOrDefault(emptyList())
            if (autotune.calculationRunning) watchRun(explain = false) else show(autotune.lastResultSummary())
        }
        // a preloaded model that was not used is freed with the screen
        DisposableEffect(Unit) { onDispose { aiTextEngine.release() } }
        Dialog(onDismissRequest = { if (!modelCopying) onClose() }, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
            Column(
                Modifier
                    .padding(12.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(DashColors.Bg)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
            ) {
                Text("✦ " + rh.gs(R.string.weekly_review_title), color = DashColors.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                summary?.let { Text(rh.gs(R.string.weekly_review_meta, it.days, it.profileName, dateUtil.dateString(it.runTime)), color = DashColors.Sub, fontSize = 11.5.sp, modifier = Modifier.padding(top = 2.dp)) }
                Text(rh.gs(R.string.weekly_review_warning), color = DashColors.High, fontSize = 11.5.sp, modifier = Modifier.padding(top = 6.dp))
                QualityCard()
                when {
                    running         -> Status(rh.gs(R.string.weekly_review_running))
                    failed          -> Status(rh.gs(R.string.weekly_review_failed))
                    summary == null -> Status(rh.gs(R.string.weekly_review_none))
                    else            -> summary?.let { Result(it) }
                }
                if (aiTextEngine.usesLocal) ModelRow { picker.launch(arrayOf("*/*")) }
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Action(rh.gs(R.string.weekly_review_close), Modifier.weight(1f), onClick = onClose)
                    Action(rh.gs(R.string.weekly_review_run), Modifier.weight(1.4f), enabled = !running, strong = true) { confirmRun() }
                }
            }
        }
        ask?.let { (title, message, action) ->
            AlertDialog(
                onDismissRequest = { ask = null }, title = { Text(title) }, text = { Text(message) },
                confirmButton = { TextButton(onClick = { ask = null; action() }) { Text(rh.gs(android.R.string.ok)) } },
                dismissButton = { TextButton(onClick = { ask = null }) { Text(rh.gs(android.R.string.cancel)) } }
            )
        }
        note?.let { (title, message) ->
            AlertDialog(onDismissRequest = { note = null }, title = { Text(title) }, text = { Text(message) }, confirmButton = { TextButton(onClick = { note = null }) { Text(rh.gs(android.R.string.ok)) } })
        }
    }

    private suspend fun loadQuality(): List<WeeklyReviewMath.DayQuality> {
        val now = dateUtil.now()
        return WeeklyReviewMath.dayStarts(now, MidnightTime.calc(now), DAYS).map { start ->
            val end = start + T_DAY
            WeeklyReviewMath.DayQuality(
                dayStart = start,
                bgCount = persistenceLayer.getBgReadingsDataFromTimeToTime(start, end, true).size,
                carbEntries = persistenceLayer.getCarbsFromTimeToTimeExpanded(start, end, true).count { it.amount > 0 }
            )
        }
    }

    private fun qualityLine(d: WeeklyReviewMath.DayQuality, dayFmt: SimpleDateFormat): String {
        val reasons = buildList {
            if (d.sensorGap) add(rh.gs(R.string.weekly_review_quality_sensor, d.bgCount))
            if (d.noCarbs) add(rh.gs(R.string.weekly_review_quality_carbs))
        }
        return dayFmt.format(Date(d.dayStart)) + ": " + reasons.joinToString(" · ")
    }

    private fun show(s: AutotuneSummary?) {
        summary = s
        failed = false
        val cached = s?.let { storedExplanation(it.runTime) }
        aiText = cached
        aiDone = cached != null
        // the AI button is shown: load the local model now so the explanation starts at once when tapped
        if (s != null && cached == null) aiTextEngine.preload()
    }

    private fun confirmRun() {
        val s = summary
        ask = Triple(
            rh.gs(R.string.weekly_review_run),
            if (s?.canRevert == true) rh.gs(R.string.weekly_review_run_confirm_reverted, s.profileName) else rh.gs(R.string.weekly_review_run_confirm)
        ) { run() }
    }

    private fun run() {
        if (autotune.calculationRunning) return
        // all weekdays, current profile; same calculation as the Autotune screen. Keeps running if this screen is closed.
        appScope.launch(Dispatchers.Default) {
            try {
                autotune.aapsAutotune(DAYS, false, "", BooleanArray(7) { true })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Autotune can throw (e.g. NaN with too little data) and then leaves its "running" flag set
                aapsLogger.error(LTag.UI, "Weekly review autotune", e)
                autotune.calculationRunning = false
                autotune.lastRunSuccess = false
            }
        }
        scope.launch {
            // give the run a moment to set its flag
            delay(500)
            watchRun(explain = true)
        }
    }

    private suspend fun watchRun(explain: Boolean) {
        running = true
        while (autotune.calculationRunning) delay(1000)
        running = false
        val s = autotune.lastResultSummary()
        if (autotune.lastRunSuccess && s != null) {
            show(s)
            if (explain) explain(s)
        } else failed = true
    }

    private fun hourLabel(from: Int, to: Int) = rh.gs(R.string.weekly_review_why_hours, from, if (to == 0) 24 else to)

    private fun basalTag(r: WeeklyReviewMath.BasalReason, trace: AutotuneTrace): String? = when {
        r.capped && r.direction == WeeklyReviewMath.Direction.UP -> rh.gs(R.string.weekly_review_why_tag_cap_max, pct(trace.capMax))
        r.capped                                                 -> rh.gs(R.string.weekly_review_why_tag_cap_min, pct(trace.capMin))
        r.noData                                                 -> rh.gs(R.string.weekly_review_why_tag_nodata)
        else                                                     -> null
    }

    private fun pct(cap: Double) = (cap * 100).roundToInt()

    private fun basalReasonText(r: WeeklyReviewMath.BasalReason, trace: AutotuneTrace): String {
        val hours = hourLabel(r.driverFrom, r.driverTo)
        val total = signedBg(r.total)
        val base = when {
            r.noData && r.direction != WeeklyReviewMath.Direction.SAME -> rh.gs(R.string.weekly_review_why_basal_nodata)
            r.direction == WeeklyReviewMath.Direction.UP               -> rh.gs(R.string.weekly_review_why_basal_up, hours, r.daysWithData, r.daysUp, total)
            r.direction == WeeklyReviewMath.Direction.DOWN             -> rh.gs(R.string.weekly_review_why_basal_down, hours, r.daysWithData, r.daysDown, total)
            r.daysWithData == 0                                        -> rh.gs(R.string.weekly_review_why_basal_same_nodata, hours)
            else                                                       -> rh.gs(R.string.weekly_review_why_basal_same, hours, total)
        }
        return when {
            r.capped && r.direction == WeeklyReviewMath.Direction.UP   -> base + " " + rh.gs(R.string.weekly_review_why_basal_cap_max, pct(trace.capMax))
            r.capped && r.direction == WeeklyReviewMath.Direction.DOWN -> base + " " + rh.gs(R.string.weekly_review_why_basal_cap_min, pct(trace.capMin))
            else                                                       -> base
        }
    }

    private fun signedBg(mgdl: Double): String =
        (if (mgdl > 0) "+" else if (mgdl < 0) "−" else "") + profileUtil.fromMgdlToStringInUnits(abs(mgdl))

    private fun fmtIsf(mgdl: Double) =
        String.format(Locale.getDefault(), if (profileUtil.units == GlucoseUnit.MMOL) "%.2f" else "%.1f", profileUtil.fromMgdlToUnits(mgdl))

    private fun isfReasonText(r: WeeklyReviewMath.IsfReason, s: AutotuneSummary): String {
        val pctDrop = r.medianRatio?.let { (it * 100).roundToInt() }
        val change = WeeklyReviewMath.changePct(s.currentIsfMgdl, s.tunedIsfMgdl)
        return when {
            r.tunedDays == 0 || pctDrop == null -> rh.gs(R.string.weekly_review_why_isf_few, r.points)
            change < 0 && pctDrop < 100         -> rh.gs(R.string.weekly_review_why_isf_less, r.points, pctDrop)
            change > 0 && pctDrop > 100         -> rh.gs(R.string.weekly_review_why_isf_more, r.points, pctDrop)
            change == 0                         -> rh.gs(R.string.weekly_review_why_isf_same, r.points, pctDrop)
            else                                -> rh.gs(R.string.weekly_review_why_isf_mixed, r.points, pctDrop)
        }
    }

    private fun icReasonText(r: WeeklyReviewMath.IcReason, s: AutotuneSummary): String {
        val change = WeeklyReviewMath.changePct(s.currentIc, s.tunedIc)
        val bg = signedBg(r.avgBgChange)
        return when {
            r.meals == 0                          -> rh.gs(R.string.weekly_review_why_ic_none)
            change < 0 && r.avgBgChange > 0       -> rh.gs(R.string.weekly_review_why_ic_less, r.meals, bg)
            change > 0 && r.avgBgChange < 0       -> rh.gs(R.string.weekly_review_why_ic_more, r.meals, bg)
            change == 0                           -> rh.gs(R.string.weekly_review_why_ic_same, r.meals)
            else                                  -> rh.gs(R.string.weekly_review_why_ic_mixed, r.meals)
        }
    }

    @Composable
    private fun QualityCard() {
        if (quality.isEmpty()) return
        val dayFmt = remember { SimpleDateFormat("E", Locale.getDefault()) }
        Card {
            Text(rh.gs(R.string.weekly_review_quality), color = DashColors.Text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                quality.forEach { d ->
                    val c = if (d.ok) DashColors.InRange else DashColors.Low
                    Text(
                        dayFmt.format(Date(d.dayStart)), color = c, fontSize = 11.sp, textAlign = TextAlign.Center,
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(6.dp))
                            .background(c.copy(alpha = 0.12f))
                            .padding(vertical = 6.dp)
                    )
                }
            }
            val bad = quality.filterNot { it.ok }
            Text(
                if (bad.isEmpty()) rh.gs(R.string.weekly_review_quality_ok) else rh.gs(R.string.weekly_review_quality_bad, quality.size, bad.size),
                color = if (bad.isEmpty()) DashColors.InRange else DashColors.High, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp)
            )
            if (bad.isNotEmpty()) Text(bad.joinToString("\n") { qualityLine(it, dayFmt) }, color = DashColors.Sub, fontSize = 11.5.sp, modifier = Modifier.padding(top = 2.dp))
        }
    }

    @Composable
    private fun Status(text: String) {
        Card { Text(text, color = DashColors.Sub, fontSize = 12.5.sp) }
    }

    @Composable
    private fun Result(s: AutotuneSummary) {
        Card {
            Text(rh.gs(R.string.weekly_review_basal), color = DashColors.Text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            WeeklyReviewMath.basalRanges(s.currentBasal, s.tunedBasal).forEach { r ->
                ValueRow(String.format(Locale.getDefault(), "%02d–%02d", r.startHour, r.endHour), String.format(Locale.getDefault(), "%.3f", r.current), String.format(Locale.getDefault(), "%.3f", r.tuned), r.changePct)
                s.trace?.let { trace -> BasalWhy(WeeklyReviewMath.basalReason(r, trace), trace) }
            }
            s.trace?.let { Text(rh.gs(R.string.weekly_review_why_basal_note, it.days.size), color = DashColors.Dim, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp)) }
        }
        Card {
            Text(rh.gs(R.string.weekly_review_isf_ic), color = DashColors.Text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            // same precision as the Autotune screen: ISF 1 decimal (mg/dL) / 2 decimals (mmol/L), IC 2 decimals
            val units = profileUtil.units
            val isfFormat = if (units == GlucoseUnit.MMOL) "%.2f" else "%.1f"
            ValueRow(
                "ISF (${units.asText}/U)", String.format(Locale.getDefault(), isfFormat, profileUtil.fromMgdlToUnits(s.currentIsfMgdl)),
                String.format(Locale.getDefault(), isfFormat, profileUtil.fromMgdlToUnits(s.tunedIsfMgdl)), WeeklyReviewMath.changePct(s.currentIsfMgdl, s.tunedIsfMgdl)
            )
            s.trace?.let { trace ->
                val r = WeeklyReviewMath.isfReason(trace, s.currentIsfMgdl, s.tunedIsfMgdl)
                Reason(if (r.capped) rh.gs(R.string.weekly_review_why_tag_limit) else null, isfReasonText(r, s))
                r.medianRatio?.let { Calc(rh.gs(R.string.weekly_review_why_isf_calc, String.format(Locale.getDefault(), "%.2f", it), r.tunedDays, trace.days.size)) }
            }
            ValueRow("IC (g/U)", String.format(Locale.getDefault(), "%.2f", s.currentIc), String.format(Locale.getDefault(), "%.2f", s.tunedIc), WeeklyReviewMath.changePct(s.currentIc, s.tunedIc))
            s.trace?.let { trace ->
                val r = WeeklyReviewMath.icReason(trace, s.currentIc, s.tunedIc)
                Reason(if (r.capped) rh.gs(R.string.weekly_review_why_tag_limit) else null, icReasonText(r, s))
                r.measuredIc?.let { Calc(rh.gs(R.string.weekly_review_why_ic_calc, String.format(Locale.getDefault(), "%.0f", r.carbs), String.format(Locale.getDefault(), "%.1f", r.insulin), String.format(Locale.getDefault(), "%.2f", it))) }
            }
        }
        Card {
            Text(rh.gs(R.string.weekly_review_ai), color = DashColors.Text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            aiText?.let { Text(it, color = DashColors.Text, fontSize = 12.5.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 6.dp)) }
            if (!aiDone) Action(rh.gs(R.string.weekly_review_ai_button), Modifier.padding(top = 8.dp).fillMaxWidth(), enabled = !aiBusy) { scope.launch { explain(s) } }
        }
        Action(rh.gs(R.string.weekly_review_save_new), Modifier.padding(top = 10.dp).fillMaxWidth()) { saveNew() }
        Text(rh.gs(R.string.weekly_review_save_note), color = DashColors.Dim, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
        if (s.canUpdate) Action(rh.gs(R.string.weekly_review_overwrite), Modifier.padding(top = 8.dp).fillMaxWidth()) { overwrite(s) }
        if (s.canRevert) Action(rh.gs(R.string.weekly_review_revert), Modifier.padding(top = 8.dp).fillMaxWidth()) { revert(s) }
    }

    /** the reason line; tapping it shows the per-day bars of the driving hours and the formula */
    @Composable
    private fun BasalWhy(r: WeeklyReviewMath.BasalReason, trace: AutotuneTrace) {
        var open by remember(r) { mutableStateOf(false) }
        Reason(basalTag(r, trace), basalReasonText(r, trace) + "  ›", Modifier.clickable { open = !open })
        if (!open) return
        val dayFmt = remember { SimpleDateFormat("E", Locale.getDefault()) }
        Column(
            Modifier
                .padding(bottom = 6.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(DashColors.Card2)
                .padding(horizontal = 9.dp, vertical = 7.dp)
        ) {
            Text(rh.gs(R.string.weekly_review_why_detail_title, hourLabel(r.driverFrom, r.driverTo), profileUtil.units.asText), color = DashColors.Sub, fontSize = 11.sp)
            val maxAbs = r.perDay.filterNotNull().maxOfOrNull { abs(it) }?.takeIf { it > 0 } ?: 1.0
            Row(Modifier.padding(top = 6.dp).height(52.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                trace.days.indices.forEach { i ->
                    val v = r.perDay.getOrNull(i)
                    val c = when {
                        v == null -> DashColors.Dim
                        v > 0     -> DashColors.High
                        v < 0     -> DashColors.Iob
                        else      -> DashColors.Dim
                    }
                    Box(
                        Modifier
                            .weight(1f)
                            .height(if (v == null) 4.dp else (abs(v) / maxAbs * 48 + 3).dp)
                            .background(c)
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                trace.days.forEachIndexed { i, d ->
                    val v = r.perDay.getOrNull(i)
                    Text(
                        if (v == null) dayFmt.format(Date(d.dayStart)) + "·" + rh.gs(R.string.weekly_review_why_none) else dayFmt.format(Date(d.dayStart)) + "\n" + signedBg(v),
                        color = DashColors.Dim, fontSize = 9.5.sp, textAlign = TextAlign.Center, modifier = Modifier.weight(1f)
                    )
                }
            }
            if (r.daysWithData > 0 && r.avgIsf > 0) {
                val units = r.avgPerDay / r.avgIsf
                Text(
                    rh.gs(
                        if (units >= 0) R.string.weekly_review_why_detail_calc_more else R.string.weekly_review_why_detail_calc_less,
                        signedBg(r.avgPerDay), fmtIsf(r.avgIsf), String.format(Locale.getDefault(), "%.2f", abs(units))
                    ), color = DashColors.Text, fontSize = 11.5.sp, modifier = Modifier.padding(top = 6.dp)
                )
            }
        }
    }

    @Composable
    private fun Reason(tag: String?, text: String, modifier: Modifier = Modifier) {
        Text((tag?.let { "[$it] " } ?: "") + text, color = DashColors.Sub, fontSize = 11.5.sp, modifier = modifier.padding(bottom = 6.dp))
    }

    @Composable
    private fun Calc(text: String) {
        Text(
            text, color = DashColors.Text, fontSize = 11.5.sp,
            modifier = Modifier
                .padding(bottom = 6.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(DashColors.Card2)
                .padding(horizontal = 9.dp, vertical = 6.dp)
        )
    }

    /** label | current → suggested | change %, coloured: more = yellow, less = blue, same = dim */
    @Composable
    private fun ValueRow(label: String, current: String, tuned: String, pct: Int) {
        val c = if (pct > 0) DashColors.High else if (pct < 0) DashColors.Iob else DashColors.Dim
        val weight = if (pct != 0) FontWeight.Bold else FontWeight.Normal
        Row(Modifier.padding(vertical = 5.dp)) {
            Text(label, color = DashColors.Text, fontSize = 12.5.sp, modifier = Modifier.weight(1.4f))
            Text(current, color = DashColors.Sub, fontSize = 12.5.sp, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
            Text(tuned, color = c, fontSize = 12.5.sp, fontWeight = weight, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
            Text(if (pct == 0) "0%" else String.format(Locale.getDefault(), "%+d%%", pct), color = c, fontSize = 12.5.sp, fontWeight = weight, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
        }
    }

    // ---------- AI explanation ----------

    private suspend fun explain(s: AutotuneSummary) {
        storedExplanation(s.runTime)?.let {
            aiText = it
            aiDone = true
            return
        }
        if (!aiTextEngine.usesLocal && preferences.get(StringKey.OverviewAiCarbsApiKey).isBlank()) {
            aiText = rh.gs(R.string.ai_carbs_error_no_key)
            return
        }
        aiBusy = true
        aiText = rh.gs(R.string.weekly_review_ai_loading)
        try {
            val result = aiTextEngine.generate(SYSTEM_PROMPT, promptData(s))
            val cleaned = result.text.lines().filter { it.isNotBlank() }.joinToString("\n") { it.trim().replaceFirst(Regex("^[-*•]\\s*"), "• ") } +
                // which engine answered and how long it took; stored with the text so it survives restarts
                "\n\n" + rh.gs(if (result.local) R.string.weekly_review_ai_source_local else R.string.weekly_review_ai_source, result.source, ((result.millis + 500) / 1000).toInt())
            aiPrefs.edit().putLong(KEY_AI_RUN, s.runTime).putString(KEY_AI_TEXT, cleaned).apply()
            aiText = cleaned
            aiDone = true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            aapsLogger.error(LTag.UI, "Weekly review AI", e)
            aiText = errorMessage(e)
        }
        aiBusy = false
    }

    private fun storedExplanation(runTime: Long): String? =
        if (aiPrefs.getLong(KEY_AI_RUN, 0L) == runTime) aiPrefs.getString(KEY_AI_TEXT, null) else null

    /** Only aggregated values are sent: hourly basal ranges, ISF / IC and per-day quality notes. */
    private fun promptData(s: AutotuneSummary): String = buildString {
        appendLine("기간: 최근 ${s.days}일, 프로필: ${s.profileName}")
        appendLine("기저 (시간대: 현재 → 제안, 변화):")
        WeeklyReviewMath.basalRanges(s.currentBasal, s.tunedBasal).forEach {
            appendLine(String.format(Locale.US, "%02d-%02d: %.3f → %.3f U/h (%+d%%)", it.startHour, it.endHour, it.current, it.tuned, it.changePct))
        }
        appendLine(String.format(Locale.US, "ISF: %.1f → %.1f mg/dL/U (%+d%%)", s.currentIsfMgdl, s.tunedIsfMgdl, WeeklyReviewMath.changePct(s.currentIsfMgdl, s.tunedIsfMgdl)))
        appendLine(String.format(Locale.US, "IC: %.2f → %.2f g/U (%+d%%)", s.currentIc, s.tunedIc, WeeklyReviewMath.changePct(s.currentIc, s.tunedIc)))
        s.trace?.let { trace ->
            appendLine("변화 이유 (Autotune 데이터 기준, 화면에 이미 표시됨):")
            WeeklyReviewMath.basalRanges(s.currentBasal, s.tunedBasal).forEach {
                val why = WeeklyReviewMath.basalReason(it, trace)
                appendLine(String.format(Locale.US, "- 기저 %02d-%02d: ", it.startHour, it.endHour) + (basalTag(why, trace)?.let { t -> "[$t] " } ?: "") + basalReasonText(why, trace))
            }
            appendLine("- ISF: " + isfReasonText(WeeklyReviewMath.isfReason(trace, s.currentIsfMgdl, s.tunedIsfMgdl), s))
            appendLine("- IC: " + icReasonText(WeeklyReviewMath.icReason(trace, s.currentIc, s.tunedIc), s))
        }
        val bad = quality.filterNot { it.ok }
        if (bad.isEmpty()) appendLine("데이터 품질: 7일 모두 양호")
        else {
            val fmt = SimpleDateFormat("E", Locale.KOREAN)
            appendLine("데이터 품질 주의:")
            bad.forEach { appendLine("- " + qualityLine(it, fmt)) }
        }
    }

    private fun errorMessage(error: Throwable): String = when {
        error is HttpException && error.code() == 429                       -> rh.gs(R.string.ai_carbs_error_rate_limited)
        error is HttpException && error.code() in setOf(401, 403)           -> rh.gs(R.string.ai_carbs_error_auth)
        error is HttpException && error.code() in setOf(500, 502, 503, 504) -> rh.gs(R.string.ai_carbs_error_overloaded)
        error is IOException                                                -> rh.gs(R.string.ai_carbs_error_network)
        else                                                                -> rh.gs(R.string.ai_carbs_error_generic, error.message ?: "")
    }

    // ---------- profile actions (same as the Autotune screen) ----------

    private fun saveNew() {
        val name = "at_" + SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(dateUtil.now()))
        val title = rh.gs(R.string.weekly_review_save_new)
        ask = Triple(title, rh.gs(R.string.weekly_review_save_new_confirm, name)) {
            scope.launch {
                val used = autotune.copyTunedToNewProfile(name)
                note = title to (if (used != null) rh.gs(R.string.weekly_review_saved, used) else rh.gs(R.string.weekly_review_failed))
            }
        }
    }

    private fun overwrite(s: AutotuneSummary) {
        ask = Triple(rh.gs(R.string.weekly_review_overwrite), rh.gs(R.string.weekly_review_overwrite_confirm, s.profileName)) {
            scope.launch {
                autotune.updateInputProfileWithTuned()
                show(autotune.lastResultSummary())
            }
        }
    }

    private fun revert(s: AutotuneSummary) {
        ask = Triple(rh.gs(R.string.weekly_review_revert), rh.gs(R.string.weekly_review_revert_confirm, s.profileName)) {
            scope.launch {
                autotune.revertInputProfile()
                show(autotune.lastResultSummary())
            }
        }
    }

    // ---------- local model file ----------

    @Composable
    private fun ModelRow(onPick: () -> Unit) {
        val file = File(preferences.get(StringKey.AiLocalModelPath))
        Card {
            Text(rh.gs(R.string.local_model_file), color = DashColors.Text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Text(
                modelStatus ?: if (file.canRead()) "${file.name} · ${"%.1f".format(file.length() / 1e9)} GB" else rh.gs(R.string.local_model_pick),
                color = DashColors.Sub, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp)
            )
            Action(rh.gs(R.string.local_model_file), Modifier.padding(top = 8.dp).fillMaxWidth(), enabled = !modelCopying, onClick = onPick)
        }
    }

    /** The on-device runtime needs a plain file path, so the model is copied into the app's own storage. Only one is kept. */
    private suspend fun copyModel(uri: Uri) {
        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "model.task"
        // .task (MediaPipe, Gemma 3n) or .litertlm (LiteRT-LM, Gemma 4)
        if (!name.endsWith(".task", ignoreCase = true) && !name.endsWith(".litertlm", ignoreCase = true)) {
            modelStatus = rh.gs(R.string.local_model_wrong_type)
            return
        }
        modelCopying = true
        modelStatus = rh.gs(R.string.local_model_copying, name)
        val target = withContext(Dispatchers.IO) {
            val dir = File(context.getExternalFilesDir(null), "llm").apply { mkdirs() }
            val target = File(dir, name)
            val tmp = File(dir, "$name.part")
            try {
                context.contentResolver.openInputStream(uri)!!.use { input -> tmp.outputStream().use { input.copyTo(it, 1 shl 20) } }
                dir.listFiles()?.filter { it != tmp && it.name != name }?.forEach { it.delete() }
                target.delete()
                if (tmp.renameTo(target)) target else null
            } catch (e: IOException) {
                aapsLogger.error(LTag.UI, "Local model copy failed", e)
                tmp.delete()
                null
            }
        }
        modelCopying = false
        if (target != null) {
            preferences.put(StringKey.AiLocalModelPath, target.absolutePath)
            modelStatus = rh.gs(R.string.local_model_ready, name)
        } else modelStatus = rh.gs(R.string.local_model_copy_failed)
    }

    // ---------- parts ----------

    @Composable
    private fun Card(content: @Composable () -> Unit) {
        Column(
            Modifier
                .padding(top = 10.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(DashColors.Card)
                .border(1.dp, DashColors.Line, RoundedCornerShape(14.dp))
                .padding(12.dp)
        ) { content() }
    }

    @Composable
    private fun Action(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, strong: Boolean = false, onClick: () -> Unit) {
        val c = if (!enabled) DashColors.Dim else if (strong) DashColors.Bg else DashColors.Accent
        Text(
            text, color = c, fontSize = 13.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 1,
            modifier = modifier
                .clip(RoundedCornerShape(10.dp))
                .background(if (strong && enabled) DashColors.Accent else Color.Transparent)
                .border(1.dp, if (enabled) DashColors.Accent.copy(alpha = 0.6f) else DashColors.Line, RoundedCornerShape(10.dp))
                .clickable(enabled = enabled, onClick = onClick)
                .padding(vertical = 10.dp, horizontal = 6.dp)
        )
    }
}
