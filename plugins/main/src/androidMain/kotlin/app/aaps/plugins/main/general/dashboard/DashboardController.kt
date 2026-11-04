package app.aaps.plugins.main.general.dashboard

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aaps.core.data.configuration.Constants
import app.aaps.core.data.model.BS
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.RM
import app.aaps.core.data.model.TrendArrow
import app.aaps.core.data.time.T
import app.aaps.core.interfaces.aps.APSResult
import app.aaps.core.interfaces.aps.Loop
import app.aaps.core.interfaces.bgQualityCheck.BgQualityCheck
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.constraints.ConstraintsChecker
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.db.ProcessedTbrEbData
import app.aaps.core.interfaces.iob.GlucoseStatusProvider
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.navigation.ElementType
import app.aaps.core.interfaces.nsclient.ProcessedDeviceStatusData
import app.aaps.core.interfaces.overview.LastBgData
import app.aaps.core.interfaces.overview.graph.BgType
import app.aaps.core.interfaces.overview.graph.OverviewDataCache
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.plugin.PluginBase
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.source.DexcomBoyda
import app.aaps.core.interfaces.source.XDripSource
import app.aaps.core.interfaces.stats.TddCalculator
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.DecimalFormatter
import app.aaps.core.interfaces.utils.MidnightTime
import app.aaps.core.interfaces.utils.TrendCalculator
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.BooleanNonKey
import app.aaps.core.keys.UnitDoubleKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.ui.extensions.displayText
import app.aaps.core.objects.extensions.round
import app.aaps.core.ui.compose.StatusLevel
import app.aaps.core.ui.compose.navigation.HomeBarItem
import app.aaps.core.ui.compose.navigation.NavigationRequest
import app.aaps.plugins.main.R
import app.aaps.core.interfaces.autotune.Autotune
import app.aaps.plugins.main.general.dashboard.ai.AiCarbs
import app.aaps.plugins.main.general.dashboard.ai.AiTextEngine
import app.aaps.plugins.main.general.dashboard.ai.WeeklyReview
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import app.aaps.plugins.main.general.dashboard.meal.MealRecords
import app.aaps.plugins.main.general.dashboard.meal.MealTracker
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.abs

/** Personal-fork: what one status light item shows, handed in from the 4.0 StatusViewModel. */
data class StatusInput(val age: String, val ageStatus: StatusLevel, val level: String?, val levelStatus: StatusLevel)

/**
 * Personal-fork: the data of the Dashboard home screen (DashboardFragment on 3.4), from the same sources.
 * It only reads; every treatment goes through the 4.0 navigation and its dialogs.
 */
@SingleIn(AppScope::class)
@Inject
class DashboardController(
    private val preferences: Preferences,
    private val rh: ResourceHelper,
    private val profileFunction: ProfileFunction,
    private val profileUtil: ProfileUtil,
    private val constraintChecker: ConstraintsChecker,
    private val processedDeviceStatusData: ProcessedDeviceStatusData,
    private val processedTbrEbData: ProcessedTbrEbData,
    private val loop: Loop,
    private val activePlugin: ActivePlugin,
    private val iobCobCalculator: IobCobCalculator,
    private val dexcomBoyda: DexcomBoyda,
    private val xDripSource: XDripSource,
    private val config: Config,
    private val trendCalculator: TrendCalculator,
    private val dateUtil: DateUtil,
    private val persistenceLayer: PersistenceLayer,
    private val glucoseStatusProvider: GlucoseStatusProvider,
    private val lastBgData: LastBgData,
    private val bgQualityCheck: BgQualityCheck,
    private val decimalFormatter: DecimalFormatter,
    private val tddCalculator: TddCalculator,
    private val mealTracker: MealTracker,
    private val cache: OverviewDataCache,
    private val aapsLogger: AAPSLogger,
    val newWizard: () -> DashboardWizard,
    val newMealRecords: () -> MealRecords,
    val newAiCarbs: () -> AiCarbs,
    val newWeeklyReview: () -> WeeklyReview,
    private val autotune: Autotune,
    private val aiTextEngine: AiTextEngine
) {

    private val _state = MutableStateFlow(DashboardState())
    val state: StateFlow<DashboardState> = _state
    private var rangeHours = 6
    private val _graph = MutableStateFlow(GraphModel())
    val graph: StateFlow<GraphModel> = _graph

    val qualityMessage: String get() = bgQualityCheck.message

    private fun post(block: (DashboardState) -> DashboardState) = _state.update(block)

    /** AI explanation of the current loop decision: the last hour's flow, the dialog already shows the math. */
    suspend fun explainDecision() = withContext(Dispatchers.Default) {
        val state = _state.value
        val decision = state.loopDecision ?: return@withContext
        if (state.decisionExplain?.let { it.runTime == decision.runTime && (it.loading || !it.error) } == true) return@withContext
        if (!aiTextEngine.usesLocal && preferences.get(StringKey.OverviewAiCarbsApiKey).isBlank()) {
            post { it.copy(decisionExplain = DecisionExplain(decision.runTime, loading = false, text = rh.gs(R.string.dashboard_loop_ai_no_engine), error = true)) }
            return@withContext
        }
        post { it.copy(decisionExplain = DecisionExplain(decision.runTime, loading = true)) }
        val bg = state.bg
        val now = buildString {
            appendLine("지금 판단: ${decision.decision}")
            if (decision.summary.isNotEmpty()) appendLine("간단 요약: ${decision.summary}")
            appendLine("현재 혈당: ${bg.value} (추세 ${bg.arrowDescription}), 변화 5분 ${bg.delta} / 15분 ${bg.shortAvgDelta} / 40분 ${bg.longAvgDelta}")
            appendLine("IOB ${state.iob.value}, COB ${state.cob.value}, 기저 ${state.basal.value}, 민감도 ${state.sensitivity.value}, 목표 ${state.target.text}")
            decision.facts.forEach { (k, v) -> appendLine("$k: $v") }
            appendLine("알고리즘 원문: ${decision.reason.take(LOOP_AI_REASON_CHARS)}")
        }
        val explain = try {
            val r = aiTextEngine.generate(LOOP_AI_SYSTEM_PROMPT, now + loopHistoryText(decision.runTime - T.mins(LOOP_AI_HISTORY_MIN).msecs(), decision.runTime))
            val seconds = ((r.millis + 500) / 1000).toInt()
            DecisionExplain(decision.runTime, loading = false, text = r.text, label = rh.gs(if (r.local) R.string.dashboard_loop_ai_label_local else R.string.dashboard_loop_ai_label, r.source, seconds))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            aapsLogger.error(LTag.UI, "Loop decision AI", e)
            DecisionExplain(decision.runTime, loading = false, text = rh.gs(R.string.dashboard_loop_ai_failed), error = true)
        }
        post { it.copy(decisionExplain = explain) }
    }

    /** one line per loop run (oldest first) plus boluses / carbs of the window; runs on io */
    private suspend fun loopHistoryText(start: Long, end: Long): String = buildString {
        val runs = persistenceLayer.getApsResults(start, end + 1).sortedBy { it.date }
        if (runs.isNotEmpty()) appendLine("최근 1시간 루프 판단 (오래된 것부터):")
        runs.forEach { r ->
            val bgText = r.glucoseStatus?.glucose?.let { profileUtil.fromMgdlToStringInUnits(it) } ?: "-"
            val tbr = when {
                !r.isTempBasalRequested -> "기저 유지"
                r.duration == 0         -> "임시기저 취소"
                else                    -> "기저 ${decimalFormatter.to2Decimal(r.rate)}U/h"
            }
            val smb = if (r.smb > 0) ", SMB ${decimalFormatter.to2Decimal(r.smb)}U" else ""
            val ev = LoopReasonParser.eventualBg(r.reason)?.let { ", 예상 $it" } ?: ""
            val mp = LoopReasonParser.minPredBg(r.reason)?.let { ", 예측최저 $it" } ?: ""
            appendLine("${dateUtil.timeString(r.date)} 혈당 $bgText, IOB ${r.iob?.iob?.let { decimalFormatter.to2Decimal(it) } ?: "-"}$ev$mp → $tbr$smb")
        }
        val boluses = persistenceLayer.getBolusesFromTime(start, true).filter { it.timestamp <= end }
        if (boluses.isNotEmpty())
            appendLine("볼루스: " + boluses.joinToString(", ") { "${dateUtil.timeString(it.timestamp)} ${decimalFormatter.to2Decimal(it.amount)}U${if (it.type == BS.Type.SMB) "(SMB)" else ""}" })
        val carbs = persistenceLayer.getCarbsFromTime(start, true).filter { it.timestamp <= end }
        appendLine(if (carbs.isEmpty()) "탄수화물 입력: 없음" else "탄수화물 입력: " + carbs.joinToString(", ") { "${dateUtil.timeString(it.timestamp)} ${it.amount.toInt()}g" })
    }


    /** Runs while the Dashboard is on screen. */
    suspend fun run() = withContext(Dispatchers.Default) {
        merge(
            persistenceLayer.observeAnyChange().map { },
            cache.bgInfoFlow.map { },
            cache.predictionsFlow.map { },
            cache.calcProgressFlow.map { },
            mealTracker.changes.map { },
            flow { while (true) { emit(Unit); delay(REFRESH_MS) } }
        ).conflate().collect {
            refreshAll()
            delay(MIN_REFRESH_GAP_MS)
        }
    }

    fun setRange(hours: Int) {
        rangeHours = hours
        preferences.put(BooleanNonKey.ObjectivesScaleUsed, true)
        _graph.update { it.copy(rangeHours = hours) }
    }

    private suspend fun refreshAll() {
        if (!config.appInitialized) return
        updateBg()
        updateIobCob()
        updateBasal()
        updateSensitivity()
        processAps()
        updateProfile()
        updateTemporaryTarget()
        updateStats()
        processButtons()
        updateGraph()
    }

    // ---------- BG ----------

    private suspend fun updateBg() {
        val lastBg = lastBgData.lastBg()
        val glucoseStatus = glucoseStatusProvider.getGlucoseStatusData()
        val trendArrow = trendCalculator.getTrendArrow(iobCobCalculator.ads)
        val range = when {
            lastBg == null      -> BgRange.UNKNOWN
            lastBgData.isLow()  -> BgRange.LOW
            lastBgData.isHigh() -> BgRange.HIGH
            else                -> BgRange.IN_RANGE
        }
        val age = lastBg?.let { dateUtil.minOrSecAgo(rh, it.timestamp) }
        val source = (activePlugin.activeBgSource as? PluginBase)?.name
        val info = BgInfo(
            value = lastBg?.let { profileUtil.fromMgdlToStringInUnits(it.recalculated) } ?: "--",
            range = range,
            isActual = lastBgData.isActualBg(),
            arrowDescription = lastBgData.lastBgDescription() + " " + trendCalculator.getTrendDescription(iobCobCalculator.ads),
            trendLevel = when (trendArrow) {
                TrendArrow.DOUBLE_UP, TrendArrow.DOUBLE_DOWN, TrendArrow.TRIPLE_UP, TrendArrow.TRIPLE_DOWN -> 3
                TrendArrow.SINGLE_UP, TrendArrow.SINGLE_DOWN                                               -> 2
                TrendArrow.FORTY_FIVE_UP, TrendArrow.FORTY_FIVE_DOWN                                       -> 1
                else                                                                                       -> 0
            },
            trendAngle = when (trendArrow) {
                TrendArrow.TRIPLE_UP, TrendArrow.DOUBLE_UP, TrendArrow.SINGLE_UP       -> 90f
                TrendArrow.FORTY_FIVE_UP                                               -> 45f
                TrendArrow.FLAT                                                        -> 0f
                TrendArrow.FORTY_FIVE_DOWN                                             -> -45f
                TrendArrow.SINGLE_DOWN, TrendArrow.DOUBLE_DOWN, TrendArrow.TRIPLE_DOWN -> -90f
                else                                                                   -> null
            },
            trendFast = trendArrow in listOf(TrendArrow.DOUBLE_UP, TrendArrow.DOUBLE_DOWN, TrendArrow.TRIPLE_UP, TrendArrow.TRIPLE_DOWN),
            delta = glucoseStatus?.let { profileUtil.fromMgdlToSignedStringInUnits(it.delta) } ?: "",
            shortAvgDelta = glucoseStatus?.let { profileUtil.fromMgdlToSignedStringInUnits(it.shortAvgDelta) } ?: "",
            longAvgDelta = glucoseStatus?.let { profileUtil.fromMgdlToSignedStringInUnits(it.longAvgDelta) } ?: "",
            deltasMgdl = listOf(glucoseStatus?.delta, glucoseStatus?.shortAvgDelta, glucoseStatus?.longAvgDelta),
            age = age ?: "",
            source = source ?: "",
            timeAgo = listOfNotNull(age, source).joinToString(" · "),
            qualityMessage = if (bgQualityCheck.stateFlow.value == BgQualityCheck.State.FLAT || bgQualityCheck.stateFlow.value == BgQualityCheck.State.DOUBLED) bgQualityCheck.stateDescription() else ""
        )
        post { it.copy(bg = info, simpleMode = preferences.simpleMode, bgCardBackground = preferences.get(BooleanKey.DashboardBgCardBackground)) }
    }

    // ---------- Profile / target ----------

    private suspend fun updateProfile() {
        val display = cache.profileFlow.value
        val severity = when {
            profileFunction.getProfile() == null -> Severity.CRITICAL
            display?.isModified == true          -> Severity.WARNING
            else                                 -> Severity.NEUTRAL
        }
        val text = profileFunction.getProfileNameWithRemainingTime()
        post { it.copy(profile = RibbonInfo(text, severity)) }
    }

    private suspend fun updateTemporaryTarget() {
        val units = profileFunction.getUnits()
        val tempTarget = persistenceLayer.getTemporaryTargetActiveAt(dateUtil.now())
        val info = if (tempTarget != null) {
            RibbonInfo(
                profileUtil.toTargetRangeString(tempTarget.lowTarget, tempTarget.highTarget, GlucoseUnit.MGDL, units) + " " + dateUtil.untilString(tempTarget.timestamp + tempTarget.duration, rh),
                Severity.WARNING
            )
        } else {
            profileFunction.getProfile()?.let { profile ->
                val targetUsed =
                    if (config.APS) loop.lastRun?.constraintsProcessed?.targetBG ?: 0.0
                    else if (config.AAPSCLIENT) processedDeviceStatusData.getAPSResult()?.targetBG ?: 0.0
                    else 0.0
                if (targetUsed != 0.0 && abs(profile.getTargetMgdl() - targetUsed) > 0.01)
                    RibbonInfo(profileUtil.toTargetRangeString(targetUsed, targetUsed, GlucoseUnit.MGDL, units), Severity.OK)
                else
                    RibbonInfo(profileUtil.toTargetRangeString(profile.getTargetLowMgdl(), profile.getTargetHighMgdl(), GlucoseUnit.MGDL, units))
            } ?: RibbonInfo()
        }
        post { it.copy(target = info) }
    }

    // ---------- IOB / COB / basal / sensitivity ----------

    private fun units(v: Double) = String.format(Locale.getDefault(), "%.2f U", v)

    private suspend fun updateIobCob() {
        val bolusIob = iobCobCalculator.calculateIobFromBolus().round()
        val basalIob = iobCobCalculator.calculateIobFromTempBasalsIncludingConvertedExtended().round()
        val total = units(bolusIob.iob + basalIob.basaliob)
        val iob = InfoTile(
            value = total,
            dialogTitle = "IOB",
            dialogText = total + "\n" + rh.gs(R.string.dash_bolus) + ": " + units(bolusIob.iob) + "\n" + rh.gs(R.string.dash_basal) + ": " + units(basalIob.basaliob)
        )
        val cobText = iobCobCalculator.getCobInfo("Dashboard COB").displayText(rh, decimalFormatter) ?: "-"
        val lastCarbsTime = persistenceLayer.getNewestCarbs()?.timestamp ?: 0L
        val lastRun = loop.lastRun
        val processed = lastRun?.constraintsProcessed
        var carbsReq = ""
        var carbsReqActive = false
        if (config.APS && processed != null && lastRun != null && processed.carbsReq > 0) {
            carbsReqActive = true
            if (lastCarbsTime < lastRun.lastAPSRun) carbsReq = processed.carbsReq.toString() + " " + rh.gs(R.string.dash_required)
        }
        val cob = InfoTile(value = cobText, sub = carbsReq, highlight = carbsReqActive)
        post { it.copy(iob = iob, cob = cob) }
    }

    private suspend fun updateBasal() {
        val now = dateUtil.now()
        val profile = profileFunction.getProfile()
        val temp = processedTbrEbData.getTempBasalIncludingConvertedExtended(now)
        val rate = when {
            profile == null -> null
            temp == null    -> profile.getBasal()
            temp.isAbsolute -> temp.rate
            else            -> profile.getBasal() * temp.rate / 100.0
        }
        val text = rate?.let { String.format(Locale.getDefault(), "%.2f U/h", it) } ?: "-"
        val basal = InfoTile(value = if (temp != null) "T: $text" else text, dialogTitle = rh.gs(R.string.dash_basal), dialogText = text)
        val extended = persistenceLayer.getExtendedBolusActiveAt(now)?.takeIf { !activePlugin.activePump.isFakingTempsByExtendedBoluses }?.let { eb ->
            val v = String.format(Locale.getDefault(), "%.2f U/h", eb.rate)
            InfoTile(value = v, dialogTitle = "Extended bolus", dialogText = v)
        }
        post { it.copy(basal = basal, extended = extended) }
    }

    private suspend fun updateSensitivity() {
        val lastAutosensData = iobCobCalculator.ads.getLastAutosensData("Dashboard", aapsLogger, dateUtil)
        val lastAutosensRatio = lastAutosensData?.let { it.autosensResult.ratio * 100 }
        val autosensEnabled = config.AAPSCLIENT && preferences.get(BooleanNonKey.AutosensUsedOnMainPhone) ||
            !config.AAPSCLIENT && constraintChecker.isAutosensModeEnabled().value()
        val profile = profileFunction.getProfile()
        val isfMgdl = profile?.getProfileIsfMgdl()
        val variableSens =
            if (config.APS) loop.lastRun?.request?.variableSens ?: 0.0
            else if (config.AAPSCLIENT) processedDeviceStatusData.getAPSResult()?.variableSens ?: 0.0
            else 0.0
        val units = profileFunction.getUnits()
        val value = lastAutosensRatio?.let { String.format(Locale.getDefault(), "%.0f%%", it) } ?: ""
        val tile = if (variableSens != isfMgdl && variableSens != 0.0 && isfMgdl != null)
            InfoTile(
                value = value,
                sub = "ISF " + String.format(Locale.getDefault(), "%1$.1f→%2$.1f", profileUtil.fromMgdlToUnits(isfMgdl, units), profileUtil.fromMgdlToUnits(variableSens, units))
            )
        else InfoTile(value = value, sub = if (autosensEnabled) "Autosens" else "")
        post { it.copy(sensitivity = tile) }
    }

    // ---------- Loop ----------

    private suspend fun processAps() {
        val pump = activePlugin.activePump
        val mode = loop.runningMode()
        val suspendEnd = "${loop.minutesToEndOfSuspend()} min"
        val lastRunAgo = loop.lastRun?.lastAPSRun?.let { dateUtil.minAgo(rh, it) } ?: ""
        val closed = R.drawable.dash_ic_loop_closed
        val loopInfo = if (pump.pumpDescription.isTempBasalCapable) when (mode) {
            RM.Mode.SUPER_BOLUS       -> LoopInfo(closed, "Superbolus", suspendEnd, Severity.WARNING, ringColor = COLOR_WARN)
            RM.Mode.DISCONNECTED_PUMP -> LoopInfo(closed, rh.gs(R.string.dashboard_home_disconnected), suspendEnd, Severity.CRITICAL, ringGlyph = LoopGlyph.CROSS, ringColor = COLOR_CRITICAL)
            RM.Mode.SUSPENDED_BY_PUMP,
            RM.Mode.SUSPENDED_BY_USER,
            RM.Mode.SUSPENDED_BY_DST  -> LoopInfo(closed, rh.gs(R.string.dashboard_home_suspended), suspendEnd, Severity.CRITICAL, ringGlyph = LoopGlyph.PAUSE, ringColor = COLOR_CRITICAL)
            RM.Mode.CLOSED_LOOP_LGS   -> LoopInfo(closed, "LGS", lastRunAgo, Severity.WARNING, ringColor = COLOR_WARN)
            RM.Mode.CLOSED_LOOP       -> LoopInfo(closed, rh.gs(R.string.dashboard_home_closed), lastRunAgo, Severity.OK, ringColor = COLOR_OK)
            RM.Mode.OPEN_LOOP         -> LoopInfo(closed, rh.gs(R.string.dashboard_home_open), lastRunAgo, Severity.WARNING, ringColor = COLOR_WARN)
            RM.Mode.DISABLED_LOOP     -> LoopInfo(closed, rh.gs(R.string.dashboard_home_disabled), "", Severity.CRITICAL, ringGlyph = LoopGlyph.CROSS, ringColor = COLOR_CRITICAL)
            RM.Mode.RESUME            -> error("Invalid mode")
        }.let { it.copy(ringTint = it.ringColor) } else null
        val decision = loop.lastRun?.let { buildLoopDecision(it) }
        post { it.copy(loop = loopInfo, loopDecision = decision) }
    }

    /** Local, API-free summary of the last loop run for the floating button. */
    private suspend fun buildLoopDecision(lastRun: Loop.LastRun): LoopDecision? {
        val result = lastRun.constraintsProcessed ?: lastRun.request ?: return null
        val reason = (lastRun.request ?: result).reason
        val profile = profileFunction.getProfile()
        val units = profileFunction.getUnits()
        val basal = profile?.getBasal()
        val tbrPart = when {
            !result.isTempBasalRequested -> rh.gs(R.string.dashboard_loop_tbr_none)
            result.duration == 0         -> rh.gs(R.string.dashboard_loop_tbr_cancel)
            else                         -> rh.gs(R.string.dashboard_loop_tbr, decimalFormatter.to2Decimal(result.rate), result.duration)
        }
        val smbPart = if (result.smb > 0) rh.gs(R.string.dashboard_loop_smb, decimalFormatter.to2Decimal(result.smb)) else rh.gs(R.string.dashboard_loop_smb_none)
        val kind = when {
            result.smb > 0                                                                  -> DecisionKind.UP
            result.isTempBasalRequested && basal != null && result.rate > basal + 0.001 -> DecisionKind.UP
            result.isTempBasalRequested && basal != null && result.rate < basal - 0.001 -> DecisionKind.DOWN
            result.isTempBasalRequested && result.duration > 0 && result.rate == 0.0      -> DecisionKind.DOWN
            else                                                                            -> DecisionKind.NONE
        }
        val target = result.targetBG.takeIf { it > 0 }?.let { profileUtil.fromMgdlToStringInUnits(it, units) }
        val minPred = LoopReasonParser.minPredBg(reason)
        val eventual = LoopReasonParser.eventualBg(reason)
        val summary = when {
            result.smb > 0 && eventual != null && target != null      -> rh.gs(R.string.dashboard_loop_why_smb, eventual, target)
            kind == DecisionKind.DOWN && minPred != null && target != null -> rh.gs(R.string.dashboard_loop_why_low, minPred, target)
            kind == DecisionKind.UP && eventual != null && target != null  -> rh.gs(R.string.dashboard_loop_why_high, eventual, target)
            kind == DecisionKind.NONE                                      -> rh.gs(R.string.dashboard_loop_why_none)
            else                                                           -> ""
        }
        val facts = buildList {
            result.iob?.let { add("IOB" to units(it.iob)) }
            result.mealData?.let { add("COB" to "${it.mealCOB.toInt()} g") }
            minPred?.let { add(rh.gs(R.string.dashboard_loop_min_pred) to it) }
            eventual?.let { add(rh.gs(R.string.dashboard_loop_eventual) to it) }
            target?.let { add(rh.gs(R.string.dashboard_target) to it) }
        }
        return LoopDecision(
            runTime = lastRun.lastAPSRun,
            runTimeText = dateUtil.timeString(lastRun.lastAPSRun) + " · " + dateUtil.minAgo(rh, lastRun.lastAPSRun),
            decision = "$tbrPart · $smbPart",
            kind = kind,
            summary = summary,
            facts = facts,
            reason = reason,
            math = buildLoopMath(lastRun.request ?: result, result, reason)
        )
    }

    /**
     * Works out how OpenAPS SMB reached "eventual BG" and "minPredBG" (DetermineBasalSMB):
     * eventual = BG − IOB × ISF + deviation (or the end of the COB / UAM curve when higher),
     * minPredBG = lowest point of the IOB / COB / UAM curves, chosen by whether carbs were entered.
     * Values come from the loop result; the loop's own numbers are always the ones shown as the answer.
     */
    private suspend fun buildLoopMath(request: APSResult, result: APSResult, reason: String): LoopMath? {
        val units = profileFunction.getUnits()
        val mmol = units == GlucoseUnit.MMOL
        val toMgdl = { v: Double -> if (mmol) v * Constants.MMOLL_TO_MGDL else v }
        val fmt = { mgdl: Double -> profileUtil.fromMgdlToStringInUnits(mgdl, units) }
        val signed = { mgdl: Double -> if (abs(mgdl) < 0.5) fmt(0.0) else (if (mgdl < 0) "− " else "+ ") + fmt(abs(mgdl)) }
        val bg = request.glucoseStatus?.glucose ?: return null
        val iob = request.iob?.iob ?: return null
        val isf = LoopReasonParser.number(reason, "ISF")?.let(toMgdl) ?: return null
        val dev = LoopReasonParser.number(reason, "Dev")?.let(toMgdl) ?: return null
        val bgi = LoopReasonParser.number(reason, "BGI")?.let(toMgdl) ?: 0.0
        val eventual = LoopReasonParser.eventualBg(reason)?.replace(',', '.')?.toDoubleOrNull()?.let(toMgdl) ?: return null
        val minPred = LoopReasonParser.minPredBg(reason)?.replace(',', '.')?.toDoubleOrNull()?.let(toMgdl) ?: return null
        val minGuard = LoopReasonParser.number(reason, "minGuardBG")?.let(toMgdl)
        val target = request.targetBG.takeIf { it > 0 } ?: result.targetBG
        val minBg = request.oapsProfile?.min_bg ?: target
        val threshold = minBg - 0.5 * (minBg - 40)

        // eventual BG
        val drop = iob * isf
        val naive = Math.round(bg - drop).toDouble()
        val sum = naive + dev
        val steps = mutableListOf(
            MathStep(rh.gs(R.string.dashboard_loop_math_bg), fmt(bg)),
            MathStep(rh.gs(R.string.dashboard_loop_math_iob, decimalFormatter.to2Decimal(iob), fmt(isf)), signed(-drop), if (abs(drop) < 0.5) StepKind.PLAIN else if (drop > 0) StepKind.DOWN else StepKind.UP),
            MathStep(rh.gs(R.string.dashboard_loop_math_dev), signed(dev), if (dev >= 0) StepKind.UP else StepKind.DOWN)
        )
        if (abs(eventual - sum) >= 1.5) {
            val cobEnd = LoopReasonParser.number(reason, "COBpredBG")?.let(toMgdl)
            val uamEnd = LoopReasonParser.number(reason, "UAMpredBG")?.let(toMgdl)
            val label = when {
                cobEnd != null && abs(cobEnd - eventual) < 1.5 -> rh.gs(R.string.dashboard_loop_math_use_cob, fmt(sum))
                uamEnd != null && abs(uamEnd - eventual) < 1.5 -> rh.gs(R.string.dashboard_loop_math_use_uam, fmt(sum))
                else                                           -> rh.gs(R.string.dashboard_loop_math_other)
            }
            steps += MathStep(label, signed(eventual - sum), if (eventual >= sum) StepKind.UP else StepKind.DOWN)
        }
        val per5 = dev / 6.0
        val deviationNote = rh.gs(R.string.dashboard_loop_math_dev_note, signedPlain(fmt, per5 + bgi), signedPlain(fmt, bgi), signedPlain(fmt, per5), signedPlain(fmt, dev))

        // lowest predicted BG
        val p = request.predictions()
        val curves = listOfNotNull(
            p?.ZT?.let { PredCurve(PredKind.ZT, it) },
            p?.IOB?.let { PredCurve(PredKind.IOB, it) },
            p?.COB?.let { PredCurve(PredKind.COB, it) },
            p?.UAM?.let { PredCurve(PredKind.UAM, it) }
        )
        // same windows as the algorithm: IOB / COB after the 90 min insulin peak, UAM after 1 h
        // (stored curves are cut once they go flat, so a short curve ends on its plateau)
        val tailMin = { c: List<Int>?, from: Int -> c?.let { if (it.size > from) it.drop(from).minOrNull() else it.lastOrNull() }?.toDouble() }
        val minIob = tailMin(p?.IOB, 18)
        val minCob = tailMin(p?.COB, 18)
        val minUam = tailMin(p?.UAM, 12)
        val carbsEntered = (request.mealData?.carbs ?: 0.0) > 0.0
        val minPredNote = when {
            carbsEntered && minCob != null                  -> rh.gs(R.string.dashboard_loop_math_min_cob, fmt(minCob), fmt(minPred))
            !carbsEntered && minIob != null && minUam != null && abs(maxOf(minIob, minUam) - minPred) < 1.5 ->
                rh.gs(R.string.dashboard_loop_math_min_uam, fmt(minIob), fmt(minUam), fmt(minPred))
            !carbsEntered && minUam != null                 -> rh.gs(R.string.dashboard_loop_math_min_uam_plain, fmt(minPred))
            minIob != null                                  -> rh.gs(R.string.dashboard_loop_math_min_iob, fmt(minIob), fmt(minPred))
            else                                            -> rh.gs(R.string.dashboard_loop_math_min_plain, fmt(minPred))
        }

        // what the two numbers decided
        val verdicts = buildList {
            add(
                when {
                    eventual > target + 1.5 -> rh.gs(R.string.dashboard_loop_math_verdict_high, fmt(eventual), fmt(target))
                    eventual < target - 1.5 -> rh.gs(R.string.dashboard_loop_math_verdict_low, fmt(eventual), fmt(target))
                    else                    -> rh.gs(R.string.dashboard_loop_math_verdict_near, fmt(eventual), fmt(target))
                } to false
            )
            minGuard?.let {
                if (it >= threshold) add(rh.gs(R.string.dashboard_loop_math_verdict_safe, fmt(it), fmt(threshold)) to false)
                else add(rh.gs(R.string.dashboard_loop_math_verdict_unsafe, fmt(it), fmt(threshold)) to true)
            }
        }
        return LoopMath(
            eventual = fmt(eventual), eventualSteps = steps, deviationNote = deviationNote,
            minPred = fmt(minPred), minPredNote = minPredNote, minPredWarn = minPred < threshold,
            curves = curves, minPredMgdl = minPred, targetMgdl = target, thresholdMgdl = threshold,
            targetText = fmt(target), thresholdText = fmt(threshold), verdicts = verdicts
        )
    }

    private fun signedPlain(fmt: (Double) -> String, mgdl: Double): String =
        (if (mgdl < 0) "−" else "+") + fmt(abs(mgdl))



    // ---------- Statistics ----------

    private suspend fun updateStats() {
        val now = dateUtil.now()
        val midnight = MidnightTime.calc(now)
        val units = profileFunction.getUnits()
        val lowMgdl = profileUtil.convertToMgdlDetect(preferences.get(UnitDoubleKey.OverviewLowMark))
        val highMgdl = profileUtil.convertToMgdlDetect(preferences.get(UnitDoubleKey.OverviewHighMark))
        val readings = persistenceLayer.getBgReadingsDataFromTimeToTime(midnight, now, true).map { it.value }
        val summary = GlucoseStatsCalculator.summarize(readings, lowMgdl, highMgdl, now - midnight)
        val tdd = tddCalculator.calculateToday()
        val dash = "–"
        fun fmt(v: Double, pattern: String) = String.format(Locale.getDefault(), pattern, v)
        val stats = GlucoseStats(
            rangePct = summary?.rangePct ?: emptyList(),
            thresholds = listOf(GlucoseStatsCalculator.VERY_LOW_MGDL, lowMgdl, highMgdl, GlucoseStatsCalculator.VERY_HIGH_MGDL).map { profileUtil.fromMgdlToStringInUnits(it, units) },
            unitLabel = units.asText,
            mean = summary?.let { profileUtil.fromMgdlToStringInUnits(it.meanMgdl, units) } ?: dash,
            eA1c = summary?.let { fmt(it.eA1cPct, "%.1f") } ?: dash,
            cv = summary?.let { fmt(it.cvPct, "%.1f") } ?: dash,
            totalInsulin = tdd?.let { fmt(if (it.totalAmount > 0) it.totalAmount else it.bolusAmount + it.basalAmount, "%.2f") } ?: dash,
            bolus = tdd?.let { fmt(it.bolusAmount, "%.2f") } ?: dash,
            basal = tdd?.let { fmt(it.basalAmount, "%.2f") } ?: dash,
            carbs = tdd?.carbs?.takeIf { it > 0 }?.let { fmt(it, "%.0f") } ?: dash,
            cgmActive = summary?.let { fmt(it.cgmActivePct, "%.0f") } ?: dash
        )
        val recent = persistenceLayer.getBolusesFromTime(now - T.hours(24).msecs(), false)
            .filter { it.type != BS.Type.PRIMING }
            .sortedByDescending { it.timestamp }
            .take(5)
            .map { RecentBolus(dateUtil.timeString(it.timestamp), fmt(it.amount, "%.2f"), it.type == BS.Type.SMB) }
        val data = mealTracker.data()
        val meals = data.meals.filter { !it.legacy }
        val meal = MealTile(tracked = data.foods.count { it.tracked }, pending = meals.count { it.confirmed && it.eval == null }, unseen = meals.count { it.eval != null && !it.seen })
        // weekly review card only when Autotune can be used
        val reviewLast = if (autotune.usable) autotune.lastResultSummary()?.let { dateUtil.dateStringRelative(it.runTime, rh) } ?: "" else null
        post { it.copy(stats = stats, recentBoluses = recent, meal = meal, weeklyReviewLast = reviewLast) }
    }

    // ---------- Buttons ----------

    private suspend fun processButtons() {
        val pump = activePlugin.activePump
        val profile = profileFunction.getProfile()
        val pumpUsable = loop.runningMode() != RM.Mode.DISCONNECTED_PUMP && !pump.isSuspended() && pump.isInitialized()
        val xDrip = xDripSource.isEnabled()
        val dexcom = dexcomBoyda.isEnabled()
        val buttons = Buttons(
            insulin = profile != null && preferences.get(BooleanKey.OverviewShowInsulinButton),
            insulinWarning = !pumpUsable,
            carbs = profile != null && preferences.get(BooleanKey.OverviewShowCarbsButton),
            wizard = pumpUsable && profile != null && preferences.get(BooleanKey.OverviewShowWizardButton),
            calibration = false,
            cgm = preferences.get(BooleanKey.OverviewShowCgmButton) && xDrip && !dexcom,
            cgmIcon = R.drawable.dash_ic_xdrip
        )
        post { it.copy(buttons = buttons) }
    }

    // ---------- Graph ----------

    private suspend fun updateGraph() {
        val range = cache.timeRangeFlow.value ?: return
        val now = dateUtil.now()
        val hours = rangeHours
        val from = maxOf(range.fromTime, range.toTime - T.hours(hours.toLong()).msecs())
        val low = preferences.get(UnitDoubleKey.OverviewLowMark)
        val high = preferences.get(UnitDoubleKey.OverviewHighMark)
        val isMgdl = profileFunction.getUnits() == GlucoseUnit.MGDL
        val bg = cache.bgReadingsFlow.value.filter { it.timestamp in from..range.endTime }.map { GraphPoint(it.timestamp, it.value) }
        val predictions = cache.predictionsFlow.value.mapNotNull { p ->
            when (p.type) {
                BgType.IOB_PREDICTION   -> PredictionKind.IOB to GraphPoint(p.timestamp, p.value)
                BgType.COB_PREDICTION   -> PredictionKind.COB to GraphPoint(p.timestamp, p.value)
                BgType.A_COB_PREDICTION -> PredictionKind.A_COB to GraphPoint(p.timestamp, p.value)
                BgType.UAM_PREDICTION   -> PredictionKind.UAM to GraphPoint(p.timestamp, p.value)
                BgType.ZT_PREDICTION    -> PredictionKind.ZT to GraphPoint(p.timestamp, p.value)
                else                    -> null
            }
        }.groupBy({ it.first }, { it.second }).mapValues { e -> e.value.sortedBy { it.x } }
        val treatments = cache.treatmentGraphFlow.value
        fun nearestBg(t: Long) = bg.minByOrNull { abs(it.x - t) }?.y ?: low
        val markers = buildList {
            treatments.boluses.filter { it.timestamp >= from }.forEach {
                val smb = it.bolusType == app.aaps.core.interfaces.overview.graph.BolusType.SMB
                add(GraphMarker(it.timestamp, if (smb) low else nearestBg(it.timestamp), if (smb) MarkerKind.SMB else MarkerKind.BOLUS, it.label, !it.isValid))
            }
            treatments.carbs.filter { it.timestamp >= from }.forEach { add(GraphMarker(it.timestamp, nearestBg(it.timestamp), MarkerKind.CARBS, it.label, !it.isValid)) }
            cache.epsGraphFlow.value.filter { it.timestamp >= from }.forEach { add(GraphMarker(it.timestamp, 0.0, MarkerKind.PROFILE, it.label)) }
        }
        val basalData = cache.basalGraphFlow.value
        val step = if (isMgdl) 20.0 else 1.0
        val maxY = kotlin.math.ceil((maxOf(bg.maxOfOrNull { it.y } ?: 0.0, high) + step) / step) * step
        _graph.value = GraphModel(
            fromTime = from,
            now = now,
            endTime = range.endTime,
            rangeHours = hours,
            maxY = maxY,
            lowMark = low,
            highMark = high,
            isMgdl = isMgdl,
            bg = bg,
            predictions = predictions,
            markers = markers,
            basal = basalData.actualBasal.filter { it.timestamp >= from }.map { GraphPoint(it.timestamp, it.value) },
            basalProfile = basalData.profileBasal.filter { it.timestamp >= from }.map { GraphPoint(it.timestamp, it.value) },
            targetLine = cache.targetLineFlow.value.targets.filter { it.timestamp >= from }.map { GraphPoint(it.timestamp, it.value) },
            activity = cache.activityGraphFlow.value.activity.filter { it.timestamp >= from }.map { GraphPoint(it.timestamp, it.value) }
        )
    }

    private companion object {

        const val REFRESH_MS = 60_000L
        const val MIN_REFRESH_GAP_MS = 500L
        const val COLOR_OK = 0xFF4ADE80.toInt()
        const val COLOR_WARN = 0xFFFBBF24.toInt()
        const val COLOR_CRITICAL = 0xFFF87171.toInt()
    }
}

/** Personal-fork: the Dashboard with its bottom bar, in the place of the 4.0 home content. */
@Composable
fun DashboardHome(
    controller: DashboardController,
    items: List<HomeBarItem>,
    cannula: StatusInput?,
    insulin: StatusInput?,
    sensor: StatusInput?,
    battery: StatusInput?,
    isPatchPump: Boolean,
    onNavigate: (NavigationRequest) -> Unit
) {
    val state by controller.state.collectAsStateWithLifecycle()
    val graph by controller.graph.collectAsStateWithLifecycle()
    LaunchedEffect(controller) { controller.run() }
    var info by remember { mutableStateOf<Pair<String, String>?>(null) }
    var wizard by remember { mutableStateOf<DashboardWizard?>(null) }
    var records by remember { mutableStateOf<MealRecords?>(null) }
    var aiCarbs by remember { mutableStateOf<AiCarbs?>(null) }
    var weekly by remember { mutableStateOf<WeeklyReview?>(null) }
    val scope = rememberCoroutineScope()
    fun go(type: ElementType) = onNavigate(NavigationRequest.Element(type))
    val actions = remember(controller) {
        object : DashboardActions {
            override fun onProfileClick() = go(ElementType.PROFILE_MANAGEMENT)
            override fun onProfileLongClick() = go(ElementType.PROFILE_MANAGEMENT)
            override fun onTempTargetClick() = go(ElementType.TEMP_TARGET_MANAGEMENT)
            override fun onLoopClick() = go(ElementType.RUNNING_MODE)
            override fun onLoopLongClick() = go(ElementType.RUNNING_MODE)
            override fun onBgQualityClick() { info = "" to controller.qualityMessage }
            override fun onPumpStatusClick() = Unit // the 4.0 pump activity button shows it
            override fun onInsulin() = go(ElementType.INSULIN)
            override fun onCarbs() = go(ElementType.CARBS)
            override fun onWizard() { wizard = controller.newWizard().also { w -> w.onAiPhoto = { aiCarbs = controller.newAiCarbs() } } }
            override fun onAiCarbs() { aiCarbs = controller.newAiCarbs() }
            override fun onTreatment() = go(ElementType.TREATMENT)
            override fun onQuickWizard() = Unit
            override fun onQuickWizardLong() = go(ElementType.QUICK_WIZARD_MANAGEMENT)
            override fun onCalibration() = go(ElementType.CALIBRATION)
            override fun onCgm() = go(ElementType.CGM_XDRIP)
            override fun onAcceptTemp() = Unit // the loop action in the bottom bar
            override fun onScale(hours: Int) = controller.setRange(hours)
            override fun showInfo(title: String, text: String) { info = title to text }
            override fun onWeeklyReview() { weekly = controller.newWeeklyReview() }
            override fun onMealRecords() { records = controller.newMealRecords() }
            override fun onRecentBoluses() = go(ElementType.TREATMENTS)
            override fun onExplainDecision() { scope.launch { controller.explainDecision() } }
        }
    }
    val lights = buildList {
        cannula?.let { add(light(if (isPatchPump) R.drawable.dash_ic_patch_pump_outline else R.drawable.dash_ic_cp_age_cannula, stringResource(if (isPatchPump) R.string.dashboard_patch else R.string.dashboard_cannula), it.age, it.ageStatus)) }
        insulin?.let {
            if (!isPatchPump) add(light(R.drawable.dash_ic_cp_age_insulin, stringResource(R.string.dashboard_insulin), it.age, it.ageStatus))
            it.level?.let { level -> add(light(R.drawable.dash_ic_bolus, stringResource(R.string.dashboard_reservoir), level, it.levelStatus)) }
        }
        sensor?.let { add(light(R.drawable.dash_ic_cp_age_sensor, stringResource(R.string.dashboard_sensor), it.age, it.ageStatus, it.level.orEmpty(), it.levelStatus)) }
        battery?.let {
            val level = it.level
            add(
                if (level != null) light(R.drawable.dash_ic_cp_age_battery, stringResource(R.string.dashboard_battery), level, it.levelStatus, it.age, it.ageStatus)
                else light(R.drawable.dash_ic_cp_age_battery, stringResource(R.string.dashboard_battery), it.age, it.ageStatus)
            )
        }
    }
    Box(Modifier.fillMaxSize()) {
        DashboardScreen(state.copy(statusLights = lights), graph, actions, items)
        wizard?.Content(onClose = { wizard = null })
        records?.Content(onClose = { records = null }) { prefill ->
            records = null
            wizard = controller.newWizard().also { w ->
                w.prefill = prefill
                w.onAiPhoto = { aiCarbs = controller.newAiCarbs() }
            }
        }
        weekly?.Content(onClose = { weekly = null })
        aiCarbs?.Content(onClose = { aiCarbs = null }) { prefill ->
            aiCarbs = null
            val open = wizard
            if (open != null) open.applyPrefill(prefill)
            else wizard = controller.newWizard().also { w ->
                w.prefill = prefill
                w.onAiPhoto = { aiCarbs = controller.newAiCarbs() }
            }
        }
    }
    info?.let { (title, text) ->
        AlertDialog(
            onDismissRequest = { info = null },
            title = if (title.isNotEmpty()) ({ Text(title) }) else null,
            text = { Text(text) },
            confirmButton = { TextButton(onClick = { info = null }) { Text("OK") } }
        )
    }
}

private fun severityOf(s: StatusLevel): Severity = when (s) {
    StatusLevel.CRITICAL -> Severity.CRITICAL
    StatusLevel.WARNING  -> Severity.WARNING
    else                 -> Severity.NEUTRAL
}

private fun colorOf(s: StatusLevel): Int? = when (s) {
    StatusLevel.CRITICAL -> 0xFFF87171.toInt()
    StatusLevel.WARNING  -> 0xFFFBBF24.toInt()
    else                 -> null
}

private fun light(icon: Int, label: String, value: String, status: StatusLevel, sub: String = "", subStatus: StatusLevel = StatusLevel.UNSPECIFIED) = StatusLight(
    icon, label, value, colorOf(status), sub, colorOf(subStatus),
    if (severityOf(status) == Severity.CRITICAL || severityOf(subStatus) == Severity.CRITICAL) Severity.CRITICAL
    else if (severityOf(status) == Severity.WARNING || severityOf(subStatus) == Severity.WARNING) Severity.WARNING
    else Severity.NEUTRAL
)

/** Personal-fork: the dialog already shows the math, so the AI explains the last hour's flow. */
private const val LOOP_AI_REASON_CHARS = 600
private const val LOOP_AI_HISTORY_MIN = 60L
private const val LOOP_AI_SYSTEM_PROMPT =
    "AndroidAPS 루프(OpenAPS SMB)의 최근 1시간 판단 기록을 보고 흐름을 짧게 설명해.\n" +
        "규칙: 인사, 서론, 맺음말 없이 바로 본론. 한국어 존댓말 2~3문장, 문장마다 60자 이내.\n" +
        "지금 판단 하나를 풀어 쓰지 말고, 기록에서 보이는 변화를 설명해: 혈당이 어떻게 움직였는지, SMB·기저가 어떻게 바뀌었는지, 왜 지금 늘리거나 줄였는지(예: IOB가 쌓임, 예측최저가 낮아짐, 탄수 입력 없이 오름).\n" +
        "아래 데이터에 있는 숫자만 쓰고 새로 계산하지 마. 치료나 용량 권고는 하지 마. 목록, 마크다운 없이 평문."
