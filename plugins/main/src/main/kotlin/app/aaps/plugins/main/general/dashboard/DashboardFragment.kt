package app.aaps.plugins.main.general.dashboard

import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.text.toSpanned
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.aaps.core.data.configuration.Constants
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.RM
import app.aaps.core.data.model.TrendArrow
import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.data.ue.Action
import app.aaps.core.data.ue.Sources
import app.aaps.core.interfaces.aps.Loop
import app.aaps.core.interfaces.automation.Automation
import app.aaps.core.interfaces.bgQualityCheck.BgQualityCheck
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.constraints.ConstraintsChecker
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.iob.GlucoseStatusProvider
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.logging.UserEntryLogger
import app.aaps.core.interfaces.nsclient.ProcessedDeviceStatusData
import app.aaps.core.interfaces.overview.LastBgData
import app.aaps.core.interfaces.overview.OverviewData
import app.aaps.core.interfaces.overview.OverviewMenus
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.plugin.PluginBase
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.protection.ProtectionCheck
import app.aaps.core.interfaces.pump.BolusProgressData
import app.aaps.core.interfaces.pump.defs.determineCorrectBolusStepSize
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.AapsSchedulers
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventAcceptOpenLoopChange
import app.aaps.core.interfaces.rx.events.EventBucketedDataCreated
import app.aaps.core.interfaces.rx.events.EventEffectiveProfileSwitchChanged
import app.aaps.core.interfaces.rx.events.EventExtendedBolusChange
import app.aaps.core.interfaces.rx.events.EventInitializationChanged
import app.aaps.core.interfaces.rx.events.EventMobileToWear
import app.aaps.core.interfaces.rx.events.EventNewOpenLoopNotification
import app.aaps.core.interfaces.rx.events.EventPreferenceChange
import app.aaps.core.interfaces.rx.events.EventPumpStatusChanged
import app.aaps.core.interfaces.rx.events.EventRefreshOverview
import app.aaps.core.interfaces.rx.events.EventRunningModeChange
import app.aaps.core.interfaces.rx.events.EventScale
import app.aaps.core.interfaces.rx.events.EventTempBasalChange
import app.aaps.core.interfaces.rx.events.EventTempTargetChange
import app.aaps.core.interfaces.rx.events.EventUpdateOverviewCalcProgress
import app.aaps.core.interfaces.rx.events.EventUpdateOverviewGraph
import app.aaps.core.interfaces.rx.events.EventUpdateOverviewIobCob
import app.aaps.core.interfaces.rx.events.EventUpdateOverviewSensitivity
import app.aaps.core.interfaces.rx.events.EventWearUpdateTiles
import app.aaps.core.interfaces.rx.weardata.EventData
import app.aaps.core.interfaces.stats.TddCalculator
import app.aaps.core.interfaces.source.DexcomBoyda
import app.aaps.core.interfaces.source.XDripSource
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.DecimalFormatter
import app.aaps.core.interfaces.utils.MidnightTime
import app.aaps.core.interfaces.utils.TrendCalculator
import app.aaps.core.interfaces.utils.fabric.FabricPrivacy
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.BooleanNonKey
import app.aaps.core.keys.IntNonKey
import app.aaps.core.keys.UnitDoubleKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.objects.constraints.ConstraintObject
import app.aaps.core.objects.extensions.directionToIcon
import app.aaps.core.objects.extensions.displayText
import app.aaps.core.objects.extensions.round
import app.aaps.core.objects.profile.ProfileSealed
import app.aaps.core.objects.wizard.QuickWizard
import app.aaps.core.ui.UIRunnable
import app.aaps.core.ui.dialogs.OKDialog
import app.aaps.core.ui.extensions.runOnUiThread
import app.aaps.plugins.main.R
import app.aaps.plugins.main.general.overview.notifications.NotificationStore
import app.aaps.plugins.main.general.overview.notifications.events.EventUpdateOverviewNotification
import app.aaps.plugins.main.general.overview.ui.StatusLightHandler
import dagger.android.support.DaggerFragment
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.plusAssign
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlin.math.abs

/**
 * Alternative home screen ("Dashboard" tab) rendered with Compose.
 * Mirrors OverviewFragment's data sources and actions so both tabs behave the same.
 */
class DashboardFragment : DaggerFragment(), DashboardActions {

    @Inject lateinit var aapsLogger: AAPSLogger
    @Inject lateinit var aapsSchedulers: AapsSchedulers
    @Inject lateinit var preferences: Preferences
    @Inject lateinit var rxBus: RxBus
    @Inject lateinit var rh: ResourceHelper
    @Inject lateinit var profileFunction: ProfileFunction
    @Inject lateinit var profileUtil: ProfileUtil
    @Inject lateinit var constraintChecker: ConstraintsChecker
    @Inject lateinit var statusLightHandler: StatusLightHandler
    @Inject lateinit var processedDeviceStatusData: ProcessedDeviceStatusData
    @Inject lateinit var loop: Loop
    @Inject lateinit var activePlugin: ActivePlugin
    @Inject lateinit var iobCobCalculator: IobCobCalculator
    @Inject lateinit var dexcomBoyda: DexcomBoyda
    @Inject lateinit var xDripSource: XDripSource
    @Inject lateinit var notificationStore: NotificationStore
    @Inject lateinit var quickWizard: QuickWizard
    @Inject lateinit var config: Config
    @Inject lateinit var protectionCheck: ProtectionCheck
    @Inject lateinit var fabricPrivacy: FabricPrivacy
    @Inject lateinit var overviewMenus: OverviewMenus
    @Inject lateinit var trendCalculator: TrendCalculator
    @Inject lateinit var dateUtil: DateUtil
    @Inject lateinit var uel: UserEntryLogger
    @Inject lateinit var persistenceLayer: PersistenceLayer
    @Inject lateinit var glucoseStatusProvider: GlucoseStatusProvider
    @Inject lateinit var overviewData: OverviewData
    @Inject lateinit var lastBgData: LastBgData
    @Inject lateinit var automation: Automation
    @Inject lateinit var bgQualityCheck: BgQualityCheck
    @Inject lateinit var uiInteraction: UiInteraction
    @Inject lateinit var decimalFormatter: DecimalFormatter
    @Inject lateinit var commandQueue: CommandQueue
    @Inject lateinit var tddCalculator: TddCalculator

    private val disposable = CompositeDisposable()
    private val handler = Handler(HandlerThread(this::class.simpleName + "Handler").also { it.start() }.looper)
    private lateinit var refreshLoop: Runnable
    private var task: Runnable? = null
    private var lastUserAction = ""

    private var state by mutableStateOf(DashboardState())
    private var graph by mutableStateOf(GraphModel())
    private var notificationsView: RecyclerView? = null

    // Off-screen views used to reuse existing helpers that write into TextViews
    private lateinit var cannulaAge: TextView
    private lateinit var insulinAge: TextView
    private lateinit var reservoirLevel: TextView
    private lateinit var sensorAge: TextView
    private lateinit var sensorBattery: TextView
    private lateinit var batteryAge: TextView
    private lateinit var batteryLevel: TextView

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        cannulaAge = TextView(ctx)
        insulinAge = TextView(ctx)
        reservoirLevel = TextView(ctx)
        sensorAge = TextView(ctx)
        sensorBattery = TextView(ctx)
        batteryAge = TextView(ctx)
        batteryLevel = TextView(ctx)
        return ComposeView(ctx).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                DashboardScreen(
                    state = state,
                    graph = graph,
                    actions = this@DashboardFragment,
                    notifications = {
                        AndroidView(factory = { c ->
                            RecyclerView(c).also {
                                it.layoutManager = LinearLayoutManager(c)
                                it.setHasFixedSize(false)
                                notificationsView = it
                                notificationStore.updateNotifications(it)
                            }
                        })
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        disposable += activePlugin.activeOverview.overviewBus
            .toObservable(EventUpdateOverviewCalcProgress::class.java)
            .observeOn(aapsSchedulers.main)
            .subscribe({ state = state.copy(calcProgressPct = overviewData.calcProgressPct) }, fabricPrivacy::logException)
        disposable += activePlugin.activeOverview.overviewBus
            .toObservable(EventUpdateOverviewIobCob::class.java)
            .debounce(1L, TimeUnit.SECONDS)
            .observeOn(aapsSchedulers.io)
            .subscribe({ updateIobCob() }, fabricPrivacy::logException)
        disposable += activePlugin.activeOverview.overviewBus
            .toObservable(EventUpdateOverviewSensitivity::class.java)
            .debounce(1L, TimeUnit.SECONDS)
            .observeOn(aapsSchedulers.main)
            .subscribe({ updateSensitivity() }, fabricPrivacy::logException)
        disposable += activePlugin.activeOverview.overviewBus
            .toObservable(EventUpdateOverviewGraph::class.java)
            .debounce(1L, TimeUnit.SECONDS)
            .observeOn(aapsSchedulers.main)
            .subscribe({ updateGraph() }, fabricPrivacy::logException)
        disposable += activePlugin.activeOverview.overviewBus
            .toObservable(EventUpdateOverviewNotification::class.java)
            .observeOn(aapsSchedulers.main)
            .subscribe({ updateNotification() }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventScale::class.java)
            .observeOn(aapsSchedulers.main)
            .subscribe({ onScale(it.hours) }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventBucketedDataCreated::class.java)
            .debounce(1L, TimeUnit.SECONDS)
            .observeOn(aapsSchedulers.io)
            .subscribe({ updateBg() }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventRefreshOverview::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({
                           if (it.now) refreshAll()
                           else scheduleUpdateGUI()
                       }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventAcceptOpenLoopChange::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({ scheduleUpdateGUI() }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventPreferenceChange::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({ scheduleUpdateGUI() }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventNewOpenLoopNotification::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({ scheduleUpdateGUI() }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventPumpStatusChanged::class.java)
            .observeOn(aapsSchedulers.main)
            .delay(30, TimeUnit.MILLISECONDS, aapsSchedulers.main)
            .subscribe({
                           overviewData.pumpStatus = it.getStatus(requireContext())
                           state = state.copy(pumpStatus = overviewData.pumpStatus)
                       }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventInitializationChanged::class.java)
            .observeOn(aapsSchedulers.main)
            .subscribe({ processButtons() }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventEffectiveProfileSwitchChanged::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({ scheduleUpdateGUI() }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventTempTargetChange::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({ updateTemporaryTarget() }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventExtendedBolusChange::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({ updateExtendedBolus() }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventTempBasalChange::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({ updateTemporaryBasal() }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventRunningModeChange::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({ processAps() }, fabricPrivacy::logException)

        refreshLoop = Runnable {
            refreshAll()
            handler.postDelayed(refreshLoop, 60 * 1000L)
        }
        handler.postDelayed(refreshLoop, 60 * 1000L)
        handler.post { refreshAll() }
        state = state.copy(
            pumpStatus = overviewData.pumpStatus, calcProgressPct = overviewData.calcProgressPct,
            fabPosition = if (fabPrefs.contains("fab_x")) fabPrefs.getFloat("fab_x", 1f) to fabPrefs.getFloat("fab_y", 1f) else null
        )
        popupBolusDialogIfRunning(onClick = false)
    }

    override fun onPause() {
        super.onPause()
        disposable.clear()
        handler.removeCallbacksAndMessages(null)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        notificationsView = null
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        handler.looper.quitSafely()
    }

    private fun refreshAll() {
        if (!config.appInitialized) return
        runOnUiThread {
            if (view == null) return@runOnUiThread
            updateTimeAndStatusLights()
            updateSensitivity()
            updateGraph()
            updateNotification()
        }
        updateBg()
        updateTemporaryBasal()
        updateExtendedBolus()
        updateIobCob()
        processButtons()
        processAps()
        updateProfile()
        updateTemporaryTarget()
        updateStats()
    }

    private fun scheduleUpdateGUI() {
        task?.let { handler.removeCallbacks(it) }
        task = Runnable {
            refreshAll()
            task = null
        }.also { handler.postDelayed(it, 500) }
    }

    /** Applies a state change on the main thread. */
    private fun post(block: (DashboardState) -> DashboardState) = runOnUiThread { state = block(state) }

    // ---------- BG ----------

    private fun updateBg() {
        val lastBg = lastBgData.lastBg()
        val isActualBg = lastBgData.isActualBg()
        val glucoseStatus = glucoseStatusProvider.glucoseStatusData
        val trendDescription = trendCalculator.getTrendDescription(iobCobCalculator.ads)
        val trendArrow = trendCalculator.getTrendArrow(iobCobCalculator.ads)
        val lastBgDescription = lastBgData.lastBgDescription()
        val range = when {
            lastBg == null        -> BgRange.UNKNOWN
            lastBgData.isLow()    -> BgRange.LOW
            lastBgData.isHigh()   -> BgRange.HIGH
            else                  -> BgRange.IN_RANGE
        }
        val qualityIcon = bgQualityCheck.icon()
        val info = BgInfo(
            value = lastBg?.let { profileUtil.fromMgdlToStringInUnits(it.recalculated) } ?: "--",
            range = range,
            isActual = isActualBg,
            arrowRes = trendArrow?.directionToIcon(),
            trendLevel = when (trendArrow) {
                TrendArrow.DOUBLE_UP, TrendArrow.DOUBLE_DOWN, TrendArrow.TRIPLE_UP, TrendArrow.TRIPLE_DOWN -> 3
                TrendArrow.SINGLE_UP, TrendArrow.SINGLE_DOWN                                               -> 2
                TrendArrow.FORTY_FIVE_UP, TrendArrow.FORTY_FIVE_DOWN                                       -> 1
                else                                                                                       -> 0
            },
            arrowDescription = lastBgDescription + " " + rh.gs(app.aaps.core.ui.R.string.and) + " " + trendDescription,
            delta = glucoseStatus?.let { profileUtil.fromMgdlToSignedStringInUnits(it.delta) } ?: "",
            shortAvgDelta = glucoseStatus?.let { profileUtil.fromMgdlToSignedStringInUnits(it.shortAvgDelta) } ?: "",
            longAvgDelta = glucoseStatus?.let { profileUtil.fromMgdlToSignedStringInUnits(it.longAvgDelta) } ?: "",
            deltasMgdl = listOf(glucoseStatus?.delta, glucoseStatus?.shortAvgDelta, glucoseStatus?.longAvgDelta),
            timeAgo = listOfNotNull(
                lastBg?.let { dateUtil.minOrSecAgo(rh, it.timestamp) },
                (activePlugin.activeBgSource as? PluginBase)?.name
            ).joinToString(" · "),
            qualityIcon = qualityIcon,
            qualityMessage = if (qualityIcon != 0) bgQualityCheck.stateDescription() else ""
        )
        post { it.copy(bg = info, simpleMode = preferences.simpleMode) }
    }

    // ---------- Profile / target ----------

    private fun updateProfile() {
        val profile = profileFunction.getProfile()
        val severity = profile?.let {
            if (it is ProfileSealed.EPS && (it.value.originalPercentage != 100 || it.value.originalTimeshift != 0L || it.value.originalDuration != 0L)) Severity.WARNING
            else Severity.NEUTRAL
        } ?: Severity.CRITICAL
        val text = profileFunction.getProfileNameWithRemainingTime()
        post { it.copy(profile = RibbonInfo(text, severity)) }
    }

    private fun updateTemporaryTarget() {
        val units = profileFunction.getUnits()
        val tempTarget = persistenceLayer.getTemporaryTargetActiveAt(dateUtil.now())
        val info = if (tempTarget != null) {
            RibbonInfo(
                profileUtil.toTargetRangeString(tempTarget.lowTarget, tempTarget.highTarget, GlucoseUnit.MGDL, units) + " " + dateUtil.untilString(tempTarget.end, rh),
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

    private fun updateIobCob() {
        val bolusIob = iobCobCalculator.calculateIobFromBolus().round()
        val basalIob = iobCobCalculator.calculateIobFromTempBasalsIncludingConvertedExtended().round()
        val total = rh.gs(app.aaps.core.ui.R.string.format_insulin_units, bolusIob.iob + basalIob.basaliob)
        val iobDialog = total + "\n" +
            rh.gs(app.aaps.core.ui.R.string.bolus) + ": " + rh.gs(app.aaps.core.ui.R.string.format_insulin_units, bolusIob.iob) + "\n" +
            rh.gs(app.aaps.core.ui.R.string.basal) + ": " + rh.gs(app.aaps.core.ui.R.string.format_insulin_units, basalIob.basaliob)
        val iob = InfoTile(
            value = total,
            dialogTitle = rh.gs(app.aaps.core.ui.R.string.iob),
            dialogText = iobDialog
        )

        val cobText = iobCobCalculator.getCobInfo("Dashboard COB").displayText(rh, decimalFormatter) ?: rh.gs(app.aaps.core.ui.R.string.value_unavailable_short)
        val lastCarbsTime = persistenceLayer.getNewestCarbs()?.timestamp ?: 0L
        val constraintsProcessed = loop.lastRun?.constraintsProcessed
        val lastRun = loop.lastRun
        var carbsReq = ""
        var carbsReqActive = false
        if (config.APS && constraintsProcessed != null && lastRun != null && constraintsProcessed.carbsReq > 0) {
            carbsReqActive = true
            //only display carbsreq when carbs have not been entered recently
            if (lastCarbsTime < lastRun.lastAPSRun)
                carbsReq = constraintsProcessed.carbsReq.toString() + " " + rh.gs(app.aaps.core.ui.R.string.required)
        }
        val cob = InfoTile(value = cobText, sub = carbsReq, highlight = carbsReqActive)
        post { it.copy(iob = iob, cob = cob) }
    }

    private fun updateTemporaryBasal() {
        val tile = InfoTile(
            value = overviewData.temporaryBasalText(),
            dialogTitle = rh.gs(app.aaps.core.ui.R.string.basal),
            dialogText = overviewData.temporaryBasalDialogText()
        )
        post { it.copy(basal = tile) }
    }

    private fun updateExtendedBolus() {
        val pump = activePlugin.activePump
        val extendedBolus = persistenceLayer.getExtendedBolusActiveAt(dateUtil.now())
        val tile =
            if (extendedBolus != null && !pump.isFakingTempsByExtendedBoluses)
                InfoTile(
                    value = overviewData.extendedBolusText(),
                    dialogTitle = rh.gs(app.aaps.core.ui.R.string.extended_bolus),
                    dialogText = overviewData.extendedBolusDialogText()
                )
            else null
        post { it.copy(extended = tile) }
    }

    private fun updateSensitivity() {
        val lastAutosensData = iobCobCalculator.ads.getLastAutosensData("Dashboard", aapsLogger, dateUtil)
        val lastAutosensRatio = lastAutosensData?.let { it.autosensResult.ratio * 100 }
        val autosensEnabled = config.AAPSCLIENT && preferences.get(BooleanNonKey.AutosensUsedOnMainPhone) ||
            !config.AAPSCLIENT && constraintChecker.isAutosensModeEnabled().value()

        val profile = profileFunction.getProfile()
        val request = loop.lastRun?.request
        val isfMgdl = profile?.getProfileIsfMgdl()
        val isfForCarbs = profile?.getIsfMgdlForCarbs(dateUtil.now(), "Dashboard", config, processedDeviceStatusData)
        val variableSens =
            if (config.APS) request?.variableSens ?: 0.0
            else if (config.AAPSCLIENT) processedDeviceStatusData.getAPSResult()?.variableSens ?: 0.0
            else 0.0
        val ratioUsed = request?.autosensResult?.ratio ?: 1.0

        val tile = if (variableSens != isfMgdl && variableSens != 0.0 && isfMgdl != null) {
            val dialog = ArrayList<String>()
            lastAutosensRatio?.let { dialog.add(rh.gs(app.aaps.core.ui.R.string.autosens_long, it)) }
            if (ratioUsed != 1.0 && ratioUsed != lastAutosensData?.autosensResult?.ratio)
                dialog.add(rh.gs(app.aaps.core.ui.R.string.algorithm_long, ratioUsed * 100))
            dialog.add(rh.gs(app.aaps.core.ui.R.string.isf_for_carbs, profileUtil.fromMgdlToUnits(isfForCarbs ?: 0.0, profileFunction.getUnits())))
            if (config.APS) activePlugin.activeAPS.getSensitivityOverviewString()?.let { dialog.add(it) }
            InfoTile(
                value = lastAutosensRatio?.let { String.format(Locale.getDefault(), "%.0f%%", it) } ?: "",
                sub = "ISF " + String.format(
                    Locale.getDefault(), "%1$.1f→%2$.1f",
                    profileUtil.fromMgdlToUnits(isfMgdl, profileFunction.getUnits()),
                    profileUtil.fromMgdlToUnits(variableSens, profileFunction.getUnits())
                ),
                dialogTitle = rh.gs(app.aaps.core.ui.R.string.sensitivity),
                dialogText = dialog.joinToString("\n")
            )
        } else {
            InfoTile(
                value = lastAutosensRatio?.let { String.format(Locale.getDefault(), "%.0f%%", it) } ?: "",
                sub = if (autosensEnabled) "Autosens" else ""
            )
        }
        post { it.copy(sensitivity = tile) }
    }

    // ---------- Loop / APS ----------

    private fun processAps() {
        val pump = activePlugin.activePump
        val mode = loop.runningMode
        val suspendEnd = dateUtil.age(loop.minutesToEndOfSuspend() * 60000L, true, rh)
        val lastRunAgo = loop.lastRun?.lastAPSRun?.let { dateUtil.minAgo(rh, it) } ?: ""
        val loopInfo = if (pump.pumpDescription.isTempBasalCapable) {
            when (mode) {
                RM.Mode.SUPER_BOLUS       -> LoopInfo(R.drawable.ic_loop_superbolus, rh.gs(app.aaps.core.ui.R.string.superbolus), suspendEnd, Severity.WARNING)
                RM.Mode.DISCONNECTED_PUMP -> LoopInfo(app.aaps.core.ui.R.drawable.ic_loop_disconnected, rh.gs(app.aaps.core.ui.R.string.disconnected), suspendEnd, Severity.CRITICAL)
                RM.Mode.SUSPENDED_BY_PUMP -> LoopInfo(app.aaps.core.ui.R.drawable.ic_loop_paused, rh.gs(app.aaps.core.ui.R.string.pumpsuspended), "", Severity.CRITICAL)
                RM.Mode.SUSPENDED_BY_USER -> LoopInfo(app.aaps.core.ui.R.drawable.ic_loop_paused, rh.gs(app.aaps.core.ui.R.string.loopsuspended), suspendEnd, Severity.CRITICAL)
                RM.Mode.SUSPENDED_BY_DST  -> LoopInfo(app.aaps.core.ui.R.drawable.ic_loop_paused, rh.gs(app.aaps.core.ui.R.string.loop_suspended_by_dst), suspendEnd, Severity.CRITICAL)
                RM.Mode.CLOSED_LOOP_LGS   -> LoopInfo(app.aaps.core.ui.R.drawable.ic_loop_lgs, rh.gs(app.aaps.core.ui.R.string.uel_lgs_loop_mode), lastRunAgo, Severity.WARNING)
                RM.Mode.CLOSED_LOOP       -> LoopInfo(app.aaps.core.objects.R.drawable.ic_loop_closed, rh.gs(app.aaps.core.ui.R.string.closedloop), lastRunAgo, Severity.OK)
                RM.Mode.OPEN_LOOP         -> LoopInfo(app.aaps.core.ui.R.drawable.ic_loop_open, rh.gs(app.aaps.core.ui.R.string.openloop), lastRunAgo, Severity.WARNING)
                RM.Mode.DISABLED_LOOP     -> LoopInfo(app.aaps.core.ui.R.drawable.ic_loop_disabled, rh.gs(R.string.disabled_loop), "", Severity.CRITICAL)
                RM.Mode.RESUME            -> error("Invalid mode")
            }
        } else null

        val decision = loop.lastRun?.let { buildLoopDecision(it) }
        post { it.copy(loop = loopInfo, loopDecision = decision) }
    }

    /** Local, API-free summary of the last loop run for the floating button. */
    private fun buildLoopDecision(lastRun: Loop.LastRun): LoopDecision? {
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
            result.iob?.let { add("IOB" to rh.gs(app.aaps.core.ui.R.string.format_insulin_units, it.iob)) }
            result.mealData?.let { add("COB" to rh.gs(app.aaps.core.objects.R.string.format_carbs, it.mealCOB.toInt())) }
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
            reason = reason
        )
    }

    private fun updateTimeAndStatusLights() {
        val pump = activePlugin.activePump
        val isPatchPump = pump.pumpDescription.isPatchPump
        statusLightHandler.updateStatusLights(cannulaAge, null, insulinAge, reservoirLevel, sensorAge, sensorBattery, batteryAge, batteryLevel)
        val lights = ArrayList<StatusLight>()
        // WarnColors paints urgent/old items with urgentColor/lowColor and warnings with warnColor/highColor
        val ctx = requireContext()
        val critical = setOf(rh.gac(ctx, app.aaps.core.ui.R.attr.urgentColor), rh.gac(ctx, app.aaps.core.ui.R.attr.lowColor))
        val warning = setOf(rh.gac(ctx, app.aaps.core.ui.R.attr.warnColor), rh.gac(ctx, app.aaps.core.ui.R.attr.highColor))
        fun severityOf(vararg colors: Int?): Severity = when {
            colors.any { it in critical } -> Severity.CRITICAL
            colors.any { it in warning }  -> Severity.WARNING
            else                          -> Severity.NEUTRAL
        }
        if (preferences.get(BooleanKey.OverviewShowStatusLights) || config.AAPSCLIENT) {
            lights.add(
                StatusLight(
                    if (isPatchPump) app.aaps.core.objects.R.drawable.ic_patch_pump_outline else app.aaps.core.objects.R.drawable.ic_cp_age_cannula,
                    rh.gs(if (isPatchPump) R.string.dashboard_patch else R.string.dashboard_cannula),
                    cannulaAge.text.toString(), cannulaAge.currentTextColor
                )
            )
            if (!isPatchPump)
                lights.add(StatusLight(app.aaps.core.objects.R.drawable.ic_cp_age_insulin, rh.gs(R.string.dashboard_insulin), insulinAge.text.toString(), insulinAge.currentTextColor))
            lights.add(StatusLight(app.aaps.core.objects.R.drawable.ic_bolus, rh.gs(R.string.dashboard_reservoir), reservoirLevel.text.toString(), reservoirLevel.currentTextColor))
            lights.add(
                StatusLight(
                    app.aaps.core.objects.R.drawable.ic_cp_age_sensor, rh.gs(R.string.dashboard_sensor), sensorAge.text.toString(), sensorAge.currentTextColor,
                    sensorBattery.text.toString(), sensorBattery.currentTextColor
                )
            )
            if (!isPatchPump || pump.pumpDescription.useHardwareLink) {
                val showAge = pump.pumpDescription.isBatteryReplaceable || pump.isBatteryChangeLoggingEnabled()
                val useBatteryLevel = (pump.model() == PumpType.OMNIPOD_EROS) || (pump.model() != PumpType.ACCU_CHEK_COMBO && pump.model() != PumpType.OMNIPOD_DASH)
                val main = if (useBatteryLevel) batteryLevel else batteryAge
                val sub = if (useBatteryLevel && showAge) batteryAge else null
                lights.add(
                    StatusLight(
                        app.aaps.core.objects.R.drawable.ic_cp_age_battery, rh.gs(R.string.dashboard_battery), main.text.toString(), main.currentTextColor,
                        sub?.text?.toString().orEmpty(), sub?.currentTextColor
                    )
                )
            }
        }
        state = state.copy(statusLights = lights.map { it.copy(severity = severityOf(it.color, it.subColor)) })
    }

    // ---------- Today's statistics ----------

    private fun updateStats() {
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
            thresholds = listOf(GlucoseStatsCalculator.VERY_LOW_MGDL, lowMgdl, highMgdl, GlucoseStatsCalculator.VERY_HIGH_MGDL)
                .map { profileUtil.fromMgdlToStringInUnits(it, units) },
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
        post { it.copy(stats = stats) }
    }

    // ---------- Graph ----------

    private fun updateGraph() {
        if (view == null) return
        val pump = activePlugin.activePump
        graph = GraphModelBuilder.build(
            overviewData = overviewData,
            overviewMenus = overviewMenus,
            now = dateUtil.now(),
            lowMark = preferences.get(UnitDoubleKey.OverviewLowMark),
            highMark = preferences.get(UnitDoubleKey.OverviewHighMark),
            isMgdl = profileFunction.getUnits() == GlucoseUnit.MGDL,
            showBasal = pump.pumpDescription.isTempBasalCapable || config.AAPSCLIENT
        )
    }

    private fun updateNotification() {
        notificationsView?.let { notificationStore.updateNotifications(it) }
    }

    // ---------- Buttons ----------

    private fun processButtons() {
        val lastBG = iobCobCalculator.ads.lastBg()
        val pump = activePlugin.activePump
        val profile = profileFunction.getProfile()
        val profileName = profileFunction.getProfileName()
        val actualBG = iobCobCalculator.ads.actualBg()
        val pumpUsable = loop.runningMode != RM.Mode.DISCONNECTED_PUMP && !pump.isSuspended() && pump.isInitialized()

        val quickWizardEntry = quickWizard.getActive()
        val quickWizardText =
            if (quickWizardEntry != null && lastBG != null && profile != null && pumpUsable) {
                val wizard = quickWizardEntry.doCalc(profile, profileName, lastBG)
                if (wizard.calculatedTotalInsulin > 0)
                    quickWizardEntry.buttonText() + " " + rh.gs(app.aaps.core.objects.R.string.format_carbs, quickWizardEntry.carbs()) +
                        " " + rh.gs(app.aaps.core.ui.R.string.format_insulin_units, wizard.calculatedTotalInsulin)
                else null
            } else null

        val lastRun = loop.lastRun
        val resultAvailable = lastRun != null &&
            (lastRun.lastOpenModeAccept == 0L || lastRun.lastOpenModeAccept < lastRun.lastAPSRun) &&
            lastRun.constraintsProcessed?.isChangeRequested == true
        val acceptTemp =
            if (resultAvailable && pump.isInitialized() && loop.runningMode == RM.Mode.OPEN_LOOP && (loop as PluginBase).isEnabled())
                "${rh.gs(R.string.set_basal_question)}\n${lastRun.constraintsProcessed?.resultAsString()}"
            else null

        val xDripIsBgSource = xDripSource.isEnabled()
        val dexcomIsSource = dexcomBoyda.isEnabled()

        val userActions = ArrayList<UserAction>()
        var list = ""
        if (!loop.runningMode.isSuspended() && pump.isInitialized() && profile != null && !config.showUserActionsOnWatchOnly())
            for (event in automation.userEvents())
                if (event.isEnabled && event.canRun()) {
                    userActions.add(UserAction(event.title, event.firstActionIcon() ?: app.aaps.core.ui.R.drawable.ic_user_options_24dp) {
                        context?.let { ctx -> OKDialog.showConfirmation(ctx, rh.gs(R.string.run_question, event.title), { handler.post { automation.processEvent(event) } }) }
                    })
                    list += event.hashCode()
                }
        if (list != lastUserAction) {
            // Synchronize Watch Tiles with overview
            lastUserAction = list
            rxBus.send(EventWearUpdateTiles())
        }

        val buttons = Buttons(
            insulin = profile != null && preferences.get(BooleanKey.OverviewShowInsulinButton),
            insulinWarning = !pumpUsable,
            carbs = profile != null && preferences.get(BooleanKey.OverviewShowCarbsButton),
            // AI hands over to the Wizard or Carbs dialog, so offer it whenever one of them is available
            aiCarbs = profile != null && (preferences.get(BooleanKey.OverviewShowCarbsButton) || preferences.get(BooleanKey.OverviewShowWizardButton)),
            wizard = pumpUsable && profile != null && preferences.get(BooleanKey.OverviewShowWizardButton),
            treatment = pumpUsable && profile != null && preferences.get(BooleanKey.OverviewShowTreatmentButton),
            calibration = xDripIsBgSource && actualBG != null && preferences.get(BooleanKey.OverviewShowCalibrationButton),
            cgm = preferences.get(BooleanKey.OverviewShowCgmButton) && (xDripIsBgSource || dexcomIsSource),
            cgmIcon = if (dexcomIsSource) R.drawable.ic_byoda else app.aaps.core.objects.R.drawable.ic_xdrip,
            quickWizard = quickWizardText,
            acceptTemp = acceptTemp,
            userActions = userActions
        )
        post { it.copy(buttons = buttons) }
    }

    // ---------- Actions (same behaviour as OverviewFragment) ----------

    private fun withBolusProtection(block: () -> Unit) {
        if (childFragmentManager.isStateSaved) return
        activity?.let { protectionCheck.queryProtection(it, ProtectionCheck.Protection.BOLUS, UIRunnable { if (isAdded) block() }) }
    }

    override fun onInsulin() = withBolusProtection { uiInteraction.runInsulinDialog(childFragmentManager) }
    override fun onCarbs() = withBolusProtection { uiInteraction.runCarbsDialog(childFragmentManager) }
    override fun onWizard() = withBolusProtection { uiInteraction.runWizardDialog(childFragmentManager) }
    override fun onAiCarbs() = withBolusProtection { uiInteraction.runAiCarbsDialog(childFragmentManager) }
    override fun onTreatment() = withBolusProtection { uiInteraction.runTreatmentDialog(childFragmentManager) }
    override fun onQuickWizard() = withBolusProtection { onClickQuickWizard() }
    override fun onTempTargetClick() = withBolusProtection { uiInteraction.runTempTargetDialog(childFragmentManager) }
    override fun onLoopClick() = withBolusProtection { uiInteraction.runLoopDialog(childFragmentManager, 1) }
    override fun onLoopLongClick() = withBolusProtection { uiInteraction.runLoopDialog(childFragmentManager, 0) }

    override fun onQuickWizardLong() {
        context?.let { startActivity(Intent(it, uiInteraction.quickWizardListActivity)) }
    }

    override fun onProfileClick() {
        if (childFragmentManager.isStateSaved) return
        uiInteraction.runProfileViewerDialog(childFragmentManager, dateUtil.now(), UiInteraction.Mode.RUNNING_PROFILE)
    }

    override fun onProfileLongClick() {
        activity?.let { activity ->
            if (loop.runningMode == RM.Mode.DISCONNECTED_PUMP) OKDialog.show(activity, rh.gs(R.string.not_available_full), rh.gs(R.string.smscommunicator_pump_disconnected))
            else withBolusProtection { uiInteraction.runProfileSwitchDialog(childFragmentManager) }
        }
    }

    override fun onBgQualityClick() {
        context?.let { OKDialog.show(it, rh.gs(R.string.data_status), bgQualityCheck.message) }
    }

    override fun onPumpStatusClick() = popupBolusDialogIfRunning(onClick = true)

    override fun onCalibration() {
        if (childFragmentManager.isStateSaved) return
        if (xDripSource.isEnabled()) uiInteraction.runCalibrationDialog(childFragmentManager)
    }

    override fun onCgm() {
        if (xDripSource.isEnabled()) openCgmApp("com.eveningoutpost.dexdrip")
        else if (dexcomBoyda.isEnabled()) dexcomBoyda.dexcomPackages().forEach { openCgmApp(it) }
    }

    override fun onAcceptTemp() {
        val activity = activity ?: return
        profileFunction.getProfile() ?: return
        if ((loop as PluginBase).isEnabled()) {
            handler.post {
                val lastRun = loop.lastRun
                loop.invoke("Accept temp button", false)
                if (lastRun?.lastAPSRun != null && lastRun.constraintsProcessed?.isChangeRequested == true) {
                    runOnUiThread {
                        protectionCheck.queryProtection(activity, ProtectionCheck.Protection.BOLUS, UIRunnable {
                            if (isAdded)
                                OKDialog.showConfirmation(
                                    activity, rh.gs(app.aaps.core.ui.R.string.tempbasal_label), lastRun.constraintsProcessed?.resultAsSpanned()
                                        ?: "".toSpanned(), {
                                        uel.log(Action.ACCEPTS_TEMP_BASAL, Sources.Overview)
                                        (context?.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager?)?.cancel(Constants.notificationID)
                                        rxBus.send(EventMobileToWear(EventData.CancelNotification(dateUtil.now())))
                                        handler.post { loop.acceptChangeRequest() }
                                        state = state.copy(buttons = state.buttons.copy(acceptTemp = null))
                                    })
                        })
                    }
                }
            }
        }
    }

    override fun onScale(hours: Int) {
        overviewData.rangeToDisplay = hours
        preferences.put(IntNonKey.RangeToDisplay, hours)
        rxBus.send(EventPreferenceChange(IntNonKey.RangeToDisplay.key))
        preferences.put(BooleanNonKey.ObjectivesScaleUsed, true)
        graph = graph.copy(rangeHours = hours)
    }

    override fun onLoopExplain() {
        val d = state.loopDecision ?: return
        if (childFragmentManager.isStateSaved) return
        uiInteraction.runLoopExplainDialog(
            childFragmentManager, d.runTime, d.decision, d.reason, d.facts.joinToString(" · ") { "${it.first} ${it.second}" }
        )
    }

    // floating button position survives tab switches and restarts
    private val fabPrefs by lazy { requireContext().getSharedPreferences("dashboard_ui", Context.MODE_PRIVATE) }

    override fun onFabMoved(x: Float, y: Float) {
        fabPrefs.edit().putFloat("fab_x", x).putFloat("fab_y", y).apply()
        state = state.copy(fabPosition = x to y)
    }

    override fun showInfo(title: String, text: String) {
        activity?.let { OKDialog.show(it, title, text) }
    }

    private fun onClickQuickWizard() {
        val actualBg = iobCobCalculator.ads.actualBg()
        val profile = profileFunction.getProfile()
        val profileName = profileFunction.getProfileName()
        val pump = activePlugin.activePump
        val quickWizardEntry = quickWizard.getActive()
        if (quickWizardEntry != null && actualBg != null && profile != null) {
            val wizard = quickWizardEntry.doCalc(profile, profileName, actualBg)
            if (wizard.calculatedTotalInsulin > 0.0 && quickWizardEntry.carbs() > 0.0) {
                val carbsAfterConstraints = constraintChecker.applyCarbsConstraints(ConstraintObject(quickWizardEntry.carbs(), aapsLogger)).value()
                activity?.let {
                    if (abs(wizard.insulinAfterConstraints - wizard.calculatedTotalInsulin) >= pump.pumpDescription.pumpType.determineCorrectBolusStepSize(wizard.insulinAfterConstraints) || carbsAfterConstraints != quickWizardEntry.carbs()) {
                        OKDialog.show(it, rh.gs(app.aaps.core.ui.R.string.treatmentdeliveryerror), rh.gs(R.string.constraints_violation) + "\n" + rh.gs(R.string.change_your_input))
                        return
                    }
                    wizard.confirmAndExecute(it, quickWizardEntry)
                }
            }
        }
    }

    private fun openCgmApp(packageName: String) {
        context?.let {
            try {
                val intent = it.packageManager.getLaunchIntentForPackage(packageName) ?: throw ActivityNotFoundException()
                intent.addCategory(Intent.CATEGORY_LAUNCHER)
                it.startActivity(intent)
            } catch (_: ActivityNotFoundException) {
                aapsLogger.debug(LTag.CORE, "Error opening CGM app")
            }
        }
    }

    private fun popupBolusDialogIfRunning(onClick: Boolean) {
        if (commandQueue.bolusInQueue() && !BolusProgressData.bolusEnded && (!BolusProgressData.isSMB || onClick))
            withBolusProtection { uiInteraction.runBolusProgressDialog(childFragmentManager) }
    }
}
