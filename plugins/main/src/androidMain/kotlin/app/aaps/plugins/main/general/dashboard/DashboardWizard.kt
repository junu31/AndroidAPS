package app.aaps.plugins.main.general.dashboard

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.ue.Sources
import app.aaps.core.interfaces.automation.Automation
import app.aaps.core.interfaces.bolus.MasterActionExecutor
import app.aaps.core.interfaces.bolus.WizardExecutor
import app.aaps.core.interfaces.clientcontrol.ActionProgress
import app.aaps.core.interfaces.clientcontrol.isNotDeliveryError
import app.aaps.core.interfaces.constraints.ConstraintsChecker
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.Profile
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profile.ProfileRepository
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.queue.SmbPause
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventAutosensCalculationFinished
import app.aaps.core.interfaces.rx.events.EventShowDialog
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.Round
import app.aaps.core.interfaces.utils.SafeParse
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.BooleanNonKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.objects.constraints.ConstraintObject
import app.aaps.core.objects.extensions.valueToUnits
import app.aaps.core.objects.profile.ProfileSealed
import app.aaps.core.objects.wizard.BolusWizard
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.clientcontrol.failText
import app.aaps.core.ui.compose.icons.IcCalculator
import app.aaps.plugins.main.R
import app.aaps.plugins.main.general.dashboard.meal.FoodType
import app.aaps.plugins.main.general.dashboard.meal.GradeChip
import app.aaps.plugins.main.general.dashboard.meal.MealChip
import app.aaps.plugins.main.general.dashboard.meal.MealFood
import app.aaps.plugins.main.general.dashboard.meal.MealGrade
import app.aaps.plugins.main.general.dashboard.meal.MealMath
import app.aaps.plugins.main.general.dashboard.meal.MealRecord
import app.aaps.plugins.main.general.dashboard.meal.MealStore
import app.aaps.plugins.main.general.dashboard.meal.MealSwitch
import app.aaps.plugins.main.general.dashboard.meal.MealTracker
import app.aaps.plugins.main.general.dashboard.meal.MealUi
import app.aaps.ui.compose.wizardDialog.showWizardBolusConfirmation
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.time.TimeSource

/** Values handed to the calculator. [foodId]: an existing food; otherwise an AI estimate. */
data class WizardPrefill(val carbs: Int, val foodId: String? = null, val name: String = "", val foods: String = "", val fat: Double? = null, val protein: Double? = null)

/**
 * The inputs of one calculation. The preview here and the master's recomputation in [WizardExecutor.prepare] are built
 * from the same value, so the dose shown is the dose confirmed. In % mode the entered value replaces the base percentage
 * and the U correction does not count.
 */
internal fun dashboardWizardInputs(
    bg: Double, useBg: Boolean, carbs: Int, usePercentage: Boolean, enteredPercentage: Int, enteredCorrection: Double, basePercentage: Int,
    carbTime: Int, useCob: Boolean, useIob: Boolean, useTt: Boolean, useTrend: Boolean, alarm: Boolean, notes: String, profileName: String?
) = MasterActionExecutor.WizardInputs(
    bg = if (useBg) bg else 0.0, carbs = carbs,
    percentage = if (usePercentage) enteredPercentage else basePercentage,
    directCorrection = if (usePercentage) 0.0 else enteredCorrection,
    carbTime = carbTime, useBg = useBg, useCob = useCob, useIob = useIob, useTt = useTt, useTrend = useTrend, alarm = alarm, notes = notes,
    profileName = profileName
)

/** The stored percentage, but 100 % when there is no recent BG (same rule as the 4.0 wizard). */
internal fun dashboardWizardBasePercentage(stored: Int, lastBgTime: Long?, now: Long, resetMinutes: Long): Int =
    if (lastBgTime == null || lastBgTime < now - resetMinutes * 60_000L) 100 else stored

/**
 * Personal-fork: Dashboard calculator. Same inputs as the 4.0 Bolus wizard plus formulas with plain-words explanations
 * and meal tracking. Delivery is the 4.0 path, not a copy: [WizardExecutor.prepare] starts the SMB pause and caps the
 * dose, [showWizardBolusConfirmation] shows the master's lines, [WizardExecutor.commit] / [WizardExecutor.discard] end it.
 */
@Inject
class DashboardWizard(
    private val context: Context,
    private val aapsLogger: AAPSLogger,
    private val rh: ResourceHelper,
    private val textResolver: TextResolver,
    private val preferences: Preferences,
    private val profileFunction: ProfileFunction,
    private val profileRepository: ProfileRepository,
    private val profileUtil: ProfileUtil,
    private val activePlugin: ActivePlugin,
    private val persistenceLayer: PersistenceLayer,
    private val iobCobCalculator: IobCobCalculator,
    private val constraintChecker: ConstraintsChecker,
    private val dateUtil: DateUtil,
    private val bolusWizardProvider: () -> BolusWizard,
    private val rxBus: RxBus,
    private val mealTracker: MealTracker,
    private val wizardExecutor: WizardExecutor,
    private val automation: Automation,
    private val appScope: CoroutineScope
) {

    private lateinit var scope: CoroutineScope
    private var calculations = 0
    private var basePercentage = 100
    private var closeRequest: (() -> Unit)? = null

    /** set before [Content]: opened from the meal records or the AI carbs screen */
    var prefill: WizardPrefill? = null

    /** the 📷 button: the Dashboard opens the AI carbs screen on top and calls [applyPrefill] */
    var onAiPhoto: () -> Unit = {}

    // ---- inputs (same meaning and defaults as the classic wizard) ----
    private var profileNames by mutableStateOf(listOf<String>())
    private var profileIndex by mutableIntStateOf(0)
    private var bgText by mutableStateOf("0")
    private var sensorBgText by mutableStateOf<String?>(null)
    // empty like the classic wizard (a "0" would stay in front of the typed value)
    private var carbsText by mutableStateOf("")
    private var aiCarbs by mutableStateOf<Int?>(null)
    private var aiFoods by mutableStateOf("")
    private var usePercentage by mutableStateOf(false)
    private var correctionText by mutableStateOf("0")
    private var carbTime by mutableIntStateOf(0)
    private var alarm by mutableStateOf(false)
    private var notes by mutableStateOf("")
    private var useBg by mutableStateOf(true)
    private var useTt by mutableStateOf(false)
    private var ttAvailable by mutableStateOf(false)
    private var useTrend by mutableStateOf(false)
    private var useIob by mutableStateOf(true)
    private var useCob by mutableStateOf(false)
    private var showCalc by mutableStateOf(true)
    private var showEasy by mutableStateOf(true)

    // ---- meal tracking: food of this meal, its history and the "기록 반영" suggestion ----
    private var recentFoods by mutableStateOf(listOf<MealFood>())
    private var food by mutableStateOf<MealFood?>(null)
    private var foodIsNew by mutableStateOf(false)
    private var tracked by mutableStateOf(false)
    private var aiFat by mutableStateOf<Double?>(null)
    private var aiProtein by mutableStateOf<Double?>(null)
    private var foodSheet by mutableStateOf(false)
    private var foodQuery by mutableStateOf("")
    private var foodHistory by mutableStateOf(listOf<MealRecord>())
    private var suggestion by mutableStateOf<MealMath.Suggestion?>(null)
    private var historyApplied by mutableStateOf<Double?>(null)
    private var currentIc by mutableStateOf(0.0)
    private var lastCarbs = 0
    private var lastHistoryAdjust = 0.0

    // ---- results ----
    private var result by mutableStateOf<WizardView?>(null)

    private var calculatedPercentage = 100
    private var calculatedCorrection = 0.0
    private var okClicked = false
    private var bolusStep = 0.1
    private var maxCarbs = 0
    private var maxCorrection = 0.0
    private var units = GlucoseUnit.MGDL

    /** easy: the formula in plain words (empty when the row adds nothing) */
    data class WizardRow(val label: String, val detail: String, val insulin: String, val easy: String = "")
    data class WizardView(val rows: List<WizardRow>, val total: String, val percent: String?, val canDeliver: Boolean, val insulin: Double, val roundingNote: String?)

    @Composable
    fun Content(onClose: () -> Unit) {
        scope = rememberCoroutineScope()
        closeRequest = onClose
        LaunchedEffect(Unit) {
            if (!init()) {
                onClose()
                return@LaunchedEffect
            }
            rxBus.toFlow(EventAutosensCalculationFinished::class).collect { calculateNow() }
        }
        Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
            WizardScreen(onClose)
        }
    }

    // ---------- logic ----------

    private suspend fun init(): Boolean {
        val profileStore = profileRepository.profile.value
        if (profileFunction.getProfile() == null || profileStore == null) {
            Toast.makeText(context, R.string.dash_noprofile, Toast.LENGTH_LONG).show()
            return false
        }
        units = profileFunction.getUnits()
        bolusStep = activePlugin.activePump.pumpDescription.bolusStep
        maxCarbs = constraintChecker.getMaxCarbsAllowed().value()
        maxCorrection = constraintChecker.getMaxBolusAllowed().value()
        profileNames = listOf(rh.gs(R.string.dash_active)) + profileStore.getProfileList().map { it.toString() }
        useTrend = preferences.get(BooleanNonKey.WizardIncludeTrend)
        useCob = preferences.get(BooleanNonKey.WizardIncludeCob)
        if (useCob) useIob = true
        usePercentage = uiPrefs().getBoolean(KEY_PERCENT, false)
        showCalc = uiPrefs().getBoolean(KEY_CALC, true)
        showEasy = uiPrefs().getBoolean(KEY_EASY, true)
        ttAvailable = persistenceLayer.getTemporaryTargetActiveAt(dateUtil.now()) != null
        recentFoods = mealTracker.recentFoods()
        basePercentage = dashboardWizardBasePercentage(
            preferences.get(IntKey.OverviewBolusPercentage), persistenceLayer.getLastGlucoseValue()?.timestamp, dateUtil.now(),
            preferences.get(IntKey.OverviewResetBolusPercentageTime).toLong()
        )
        calculatedPercentage = basePercentage
        if (usePercentage) correctionText = basePercentage.toString()
        val bg = iobCobCalculator.ads.actualBg()?.valueToUnits(units) ?: 0.0
        bgText = formatBg(bg)
        sensorBgText = if (bg > 0) bgText else null
        calculateNow()
        prefill?.let { p ->
            prefill = null
            applyPrefill(p)
        }
        return true
    }

    /** a food with its last carbs, or an AI estimate */
    fun applyPrefill(p: WizardPrefill) {
        val existing = p.foodId?.let { id -> mealTracker.data().foods.firstOrNull { it.id == id } }
        carbsText = p.carbs.toString()
        if (existing != null) selectFood(existing)
        else {
            aiCarbs = p.carbs
            aiFoods = p.foods
            aiFat = p.fat
            aiProtein = p.protein
            // the AI names the meal as a new food; an existing food is only linked when the user taps it
            val name = p.name.trim()
            if (name.isNotEmpty() && (food == null || foodIsNew))
                selectFood(MealFood(MealStore.newId(), name, MealMath.typeFor(aiFat, aiProtein), false, dateUtil.now()), isNew = true)
        }
        calculate()
    }

    private fun uiPrefs() = context.getSharedPreferences("dashboard_ui", Context.MODE_PRIVATE)

    private fun formatBg(v: Double) = if (units == GlucoseUnit.MGDL) String.format(Locale.US, "%.0f", v) else String.format(Locale.US, "%.1f", v)

    private fun calculate() {
        scope.launch { calculateNow() }
    }

    private fun currentInputs(carbs: Int): MasterActionExecutor.WizardInputs {
        val correction = if (Round.roundTo(calculatedCorrection, bolusStep) == SafeParse.stringToDouble(correctionText)) calculatedCorrection
        else SafeParse.stringToDouble(correctionText)
        return dashboardWizardInputs(
            SafeParse.stringToDouble(bgText), useBg, carbs, usePercentage, SafeParse.stringToInt(correctionText), correction, basePercentage,
            carbTime, useCob, useIob, useTt && ttAvailable, useTrend, alarm, notes, if (profileIndex == 0) null else profileNames.getOrNull(profileIndex)
        )
    }

    private suspend fun calculateNow() {
        val calculation = ++calculations
        val profileStore = profileRepository.profile.value ?: return
        val selected = profileNames.getOrNull(profileIndex) ?: return
        var profileName = selected
        val profile: Profile? = if (profileIndex == 0) {
            profileName = profileFunction.getProfileName()
            profileFunction.getProfile()
        } else profileStore.getSpecificProfile(selected)?.let { ProfileSealed.Pure(it, activePlugin) }
        profile ?: return

        val carbs = SafeParse.stringToInt(carbsText)
        val carbsAfterConstraint = constraintChecker.applyCarbsConstraints(ConstraintObject(carbs, aapsLogger)).value()
        if (abs(carbs - carbsAfterConstraint) > 0.01) {
            carbsText = ""
            Toast.makeText(context, R.string.dashboard_wizard_carbs_constraint, Toast.LENGTH_LONG).show()
            return
        }
        val inputs = currentInputs(carbsAfterConstraint)
        val bg = inputs.bg
        val correction = inputs.directCorrection
        val tempTarget = persistenceLayer.getTemporaryTargetActiveAt(dateUtil.now())
        var cob = 0.0
        if (useCob) iobCobCalculator.getCobInfo("Wizard COB").displayCob?.let { cob = it }

        // same arguments as MasterActionExecutorImpl.prepareWizard, which computes the dose that is confirmed
        val w = bolusWizardProvider().doCalc(
            profile, profileName, tempTarget, carbsAfterConstraint, cob, bg, correction, inputs.percentage,
            useBg, useCob, useIob, useIob, false, inputs.useTt, useTrend, alarm, notes, carbTime
        )
        if (calculation != calculations) return
        calculatedPercentage = w.calculatedPercentage
        calculatedCorrection = w.calculatedCorrection
        currentIc = w.ic
        lastCarbs = carbsAfterConstraint
        suggestion = food?.takeIf { !foodIsNew }?.let { MealMath.suggest(foodHistory, carbsAfterConstraint.toDouble(), w.ic, bolusStep) }
        // the correction came from "기록 반영" while it still holds that value
        val fromHistory = historyApplied?.takeIf { !usePercentage && abs(correction - it) < 0.001 }
        lastHistoryAdjust = fromHistory ?: 0.0

        val u = { v: Double -> rh.gs(R.string.dash_format_insulin_units, v) }
        // formulas built from the engine's own results (BolusWizard keeps the target private: target = BG - BG insulin x ISF)
        val f1 = { v: Double -> String.format(Locale.getDefault(), "%.1f", v) }
        val bgFormula = when {
            !useBg || bg <= 0                       -> ""
            abs(w.insulinFromBG) < 0.005            -> "${formatBg(bg)} · " + rh.gs(R.string.dashboard_wizard_in_target)
            else                                    -> "(${formatBg(bg)} − ${formatBg(bg - w.insulinFromBG * w.sens)}) ÷ ISF ${f1(w.sens)}"
        }
        val trendText = (if (w.trend > 0) "+" else "") + profileUtil.fromMgdlToStringInUnits(w.trend * 3)
        val has = { v: Double -> abs(v) >= 0.005 }
        val bgDiff = w.insulinFromBG * w.sens
        val iob = w.insulinFromBolusIOB + w.insulinFromBasalIOB
        val rows = buildList {
            add(
                WizardRow(
                    rh.gs(R.string.dash_bg_label), bgFormula, u(w.insulinFromBG),
                    if (useBg && has(w.insulinFromBG))
                        rh.gs(if (bgDiff > 0) R.string.dashboard_wizard_easy_bg_high else R.string.dashboard_wizard_easy_bg_low, formatBg(abs(bgDiff)), f1(w.sens))
                    else ""
                )
            )
            add(
                WizardRow(
                    rh.gs(R.string.dash_bg_trend_label), if (useTrend && w.glucoseStatus != null) "$trendText ÷ ISF ${f1(w.sens)}" else "", u(w.insulinFromTrend),
                    if (useTrend && has(w.insulinFromTrend))
                        rh.gs(if (w.trend > 0) R.string.dashboard_wizard_easy_trend_up else R.string.dashboard_wizard_easy_trend_down, profileUtil.fromMgdlToStringInUnits(abs(w.trend * 3)))
                    else ""
                )
            )
            add(
                WizardRow(
                    "IOB", if (useIob) rh.gs(R.string.dashboard_wizard_iob_formula, u(w.insulinFromBolusIOB), u(w.insulinFromBasalIOB)) else "",
                    u(-w.insulinFromBolusIOB - w.insulinFromBasalIOB),
                    if (useIob && iob >= 0.005) rh.gs(R.string.dashboard_wizard_easy_iob) else ""
                )
            )
            add(
                WizardRow(
                    "COB", if (useCob) "${f1(cob)}g ÷ IC ${f1(w.ic)}" else "", if (useCob) u(w.insulinFromCOB) else "",
                    if (useCob && has(w.insulinFromCOB)) rh.gs(R.string.dashboard_wizard_easy_cob) else ""
                )
            )
            add(
                WizardRow(
                    rh.gs(R.string.dash_carbs), "${carbs}g ÷ IC ${f1(w.ic)}", u(w.insulinFromCarbs),
                    if (has(w.insulinFromCarbs)) rh.gs(R.string.dashboard_wizard_easy_carbs, f1(w.ic)) else ""
                )
            )
            add(
                WizardRow(
                    rh.gs(R.string.dashboard_wizard_correction),
                    if (usePercentage) "" else if (fromHistory != null) rh.gs(R.string.meal_calc_detail, food?.name.orEmpty()) else rh.gs(R.string.dashboard_wizard_entered),
                    u(w.insulinFromCorrection),
                    when {
                        !has(w.insulinFromCorrection) -> ""
                        fromHistory != null           -> rh.gs(R.string.meal_calc_easy, food?.name.orEmpty(), suggestion?.records ?: 0, f1(suggestion?.gramsPerUnit ?: 0.0))
                        else                          -> rh.gs(R.string.dashboard_wizard_easy_correction)
                    }
                )
            )
            // the percentage applied to the sum (only when it is not 100 %)
            if (w.percentageCorrection != 100)
                add(
                    WizardRow(
                        rh.gs(R.string.dashboard_wizard_percent), "${u(w.totalBeforePercentageAdjustment)} × ${w.percentageCorrection}%",
                        u(w.totalBeforePercentageAdjustment * w.percentageCorrection / 100.0),
                        rh.gs(R.string.dashboard_wizard_easy_percent, w.percentageCorrection)
                    )
                )
        }
        // the sum differs from the result only by the pump's bolus step (and constraints)
        val beforeRound = w.totalBeforePercentageAdjustment * w.percentageCorrection / 100.0
        val roundingNote = if (w.calculatedTotalInsulin > 0 && has(beforeRound - w.calculatedTotalInsulin))
            rh.gs(R.string.dashboard_wizard_easy_rounding, u(beforeRound), bolusStep.toString(), u(w.calculatedTotalInsulin))
        else null
        val canDeliver = w.calculatedTotalInsulin > 0.0 || carbsAfterConstraint > 0
        val total = if (canDeliver) listOfNotNull(
            w.calculatedTotalInsulin.takeIf { it > 0 }?.let { u(it) },
            carbsAfterConstraint.takeIf { it > 0 }?.let { "${it}g" }
        ).joinToString("  ")
        else rh.gs(R.string.dashboard_wizard_missing_carbs, w.carbsEquivalent.toInt())
        result = WizardView(
            rows, total, if (w.percentageCorrection != 100 || usePercentage) "${w.percentageCorrection}%" else null,
            canDeliver, w.calculatedTotalInsulin, roundingNote
        )
    }

    private fun deliver() {
        if (okClicked) return
        okClicked = true
        scope.launch {
            calculateNow()
            val inputs = currentInputs(lastCarbs)
            val label = textResolver.gs(CoreUiStrings.clientcontrol_action_deliver_bolus)
            val title = textResolver.gs(CoreUiStrings.boluswizard)
            // Counted from before the prepare, like the 4.0 wizard. The pause starts inside prepare and ends in commit,
            // discard or by itself; nothing here depends on this dialog staying open.
            val closeAt = TimeSource.Monotonic.markNow() + SmbPause.MAX_DURATION
            appScope.launch {
                when (val prepared = wizardExecutor.prepare(WizardExecutor.WizardSource.Manual(inputs), label)) {
                    is ActionProgress.Prepared ->
                        showWizardBolusConfirmation(
                            rxBus, textResolver, title, IcCalculator, prepared.advisorApplies, prepared.lines, prepared.advisorLines,
                            closeAt = closeAt,
                            onCancel = {
                                wizardExecutor.discard(prepared.id)
                                okClicked = false // the calculator stays open with its inputs, like the 4.0 wizard
                            }
                        ) { asAdvisor ->
                            closeRequest?.invoke()
                            appScope.launch { commit(prepared, asAdvisor, label) }
                        }

                    is ActionProgress.Rejected -> {
                        okClicked = false
                        if (prepared.reason.isNotDeliveryError())
                            rxBus.send(EventShowDialog.Ok(title = title, message = prepared.detail ?: textResolver.gs(prepared.reason.failText())))
                    }

                    else                       -> okClicked = false
                }
            }
        }
    }

    private suspend fun commit(prepared: ActionProgress.Prepared, asAdvisor: Boolean, label: String) {
        val detail = prepared.wizardDetail
        // meal tracking: kept only if the bolus is really given (MealTracker checks the database). A failure here must
        // not stop the bolus. The advisor branch eats later, so it is no meal yet.
        // Registered before the commit: it records BG and IOB as they were before this bolus.
        var mealId: String? = null
        if (!asAdvisor) food?.let { f ->
            if (lastCarbs > 0) runCatching {
                val fromAi = aiCarbs != null && aiCarbs.toString() == carbsText
                mealId = mealTracker.register(
                    f, tracked, lastCarbs.toDouble(), if (fromAi) aiFat else null, if (fromAi) aiProtein else null,
                    carbTime, detail?.totalInsulin ?: 0.0, lastHistoryAdjust
                )
            }.onFailure { aapsLogger.error(LTag.CORE, "Meal tracking", it) }
        }
        val result = wizardExecutor.commit(prepared.id, asAdvisor, Sources.WizardDialog, label)
        // a refused or failed delivery leaves no meal
        if (result !is ActionProgress.Applied) mealId?.let { id -> mealTracker.update { d -> d.copy(meals = d.meals.filter { it.id != id }) } }
        // same reminder cleanup as the 4.0 wizard
        if (result is ActionProgress.Applied) {
            if ((detail?.totalInsulin ?: 0.0) > 0.0) automation.removeAutomationEventBolusReminder()
            if (!asAdvisor && (detail?.carbs ?: 0) > 0) automation.removeAutomationEventEatReminder()
        }
    }

    private fun step(text: String, delta: Double, min: Double, max: Double, decimals: Int): String {
        val v = (SafeParse.stringToDouble(text) + delta).coerceIn(min, max)
        return if (decimals == 0) String.format(Locale.US, "%.0f", v) else String.format(Locale.US, "%.${decimals}f", Round.roundTo(v, delta.let { abs(it) }))
    }

    // ---------- UI ----------

    @Composable
    private fun WizardScreen(onClose: () -> Unit) {
        val scroll = rememberScrollState()
        val r = result
        Column(
            Modifier
                .padding(horizontal = 12.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(PopupBg)
                .border(1.dp, PopupLine, RoundedCornerShape(22.dp))
                .verticalScroll(scroll)
                .padding(16.dp)
        ) {
            // title with the profile selector on the right
            var open by remember { mutableStateOf(false) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🧮 " + rh.gs(R.string.dash_boluswizard), color = DashColors.Text, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Row(
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(DashColors.Card2)
                        .border(1.dp, DashColors.Line, RoundedCornerShape(8.dp))
                        .clickable { open = !open }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        rh.gs(R.string.dash_profile) + " ", color = DashColors.Dim, fontSize = 11.sp
                    )
                    Text(
                        profileNames.getOrNull(profileIndex).orEmpty(), color = DashColors.Text, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 130.dp)
                    )
                    Text(if (open) " ▴" else " ▾", color = DashColors.Sub, fontSize = 12.sp)
                }
            }
            if (open) Column(
                Modifier
                    .padding(top = 6.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(DashColors.Card2)
            ) {
                profileNames.forEachIndexed { i, n ->
                    Text(
                        n, color = if (i == profileIndex) DashColors.Accent else DashColors.Text, fontSize = 14.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { profileIndex = i; open = false; calculate() }
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                    )
                }
            }
            // BG (prefilled with the sensor value, editable like the classic wizard)
            val bgEdited = sensorBgText != null && bgText != sensorBgText
            val bgStep = if (units == GlucoseUnit.MGDL) 1.0 else 0.1
            val bgMax = if (units == GlucoseUnit.MGDL) 500.0 else 30.0
            val isAi = aiCarbs != null && aiCarbs.toString() == carbsText
            // BG and carbs side by side, like correction / carb time below
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Stepper(
                label = rh.gs(R.string.dash_bg_label) + if (bgEdited) " · " + rh.gs(R.string.dashboard_wizard_bg_edited, sensorBgText) else "",
                labelColor = if (bgEdited) DashColors.High else DashColors.Sub, value = bgText, unit = units.asText,
                border = if (bgEdited) DashColors.High.copy(alpha = 0.55f) else DashColors.Line,
                onValue = { bgText = it; calculate() },
                onMinus = { bgText = step(bgText, -bgStep, 0.0, bgMax, if (units == GlucoseUnit.MGDL) 0 else 1); calculate() },
                onPlus = { bgText = step(bgText, bgStep, 0.0, bgMax, if (units == GlucoseUnit.MGDL) 0 else 1); calculate() },
                extra = if (bgEdited) ({ SmallAction("↺", DashColors.Accent) { bgText = sensorBgText!!; calculate() } }) else null,
                modifier = Modifier.weight(1f), compact = true
            )
            // carbs (the AI photo button sits on the food field below)
            Stepper(
                label = rh.gs(R.string.dash_carbs) + if (isAi) " · ✦ " + rh.gs(R.string.dashboard_wizard_ai_estimate) else "",
                labelColor = if (isAi) AiLilac else DashColors.Sub, value = carbsText, unit = "g",
                border = if (isAi) AiPurple.copy(alpha = 0.55f) else DashColors.Line,
                onValue = { carbsText = it; calculate() },
                onMinus = { carbsText = step(carbsText, -1.0, 0.0, maxCarbs.toDouble(), 0); calculate() },
                onPlus = { carbsText = step(carbsText, 1.0, 0.0, maxCarbs.toDouble(), 0); calculate() },
                modifier = Modifier.weight(1f), compact = true
            )
            }
            MealSection(isAi)
            // correction (U or %) and carb time + alarm
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Stepper(
                    label = rh.gs(R.string.dashboard_wizard_correction), labelColor = DashColors.Sub, value = correctionText, unit = if (usePercentage) "%" else "U",
                    onValue = { correctionText = it; calculate() },
                    onMinus = {
                        correctionText = if (usePercentage) step(correctionText, -5.0, 10.0, 200.0, 0) else step(correctionText, -bolusStep, -maxCorrection, maxCorrection, 2); calculate()
                    },
                    onPlus = {
                        correctionText = if (usePercentage) step(correctionText, 5.0, 10.0, 200.0, 0) else step(correctionText, bolusStep, -maxCorrection, maxCorrection, 2); calculate()
                    },
                    extra = {
                        SmallAction(if (usePercentage) "%" else "U", DashColors.Accent) {
                            usePercentage = !usePercentage
                            uiPrefs().edit().putBoolean(KEY_PERCENT, usePercentage).apply()
                            correctionText = if (usePercentage) calculatedPercentage.toString() else String.format(Locale.US, "%.2f", Round.roundTo(calculatedCorrection, bolusStep))
                            calculate()
                        }
                    },
                    modifier = Modifier.weight(1f), compact = true
                )
                Stepper(
                    label = rh.gs(R.string.dashboard_wizard_carb_time), labelColor = DashColors.Sub, value = carbTime.toString(), unit = rh.gs(R.string.dashboard_wizard_minutes),
                    onValue = { carbTime = SafeParse.stringToInt(it).coerceIn(-60, 60); alarm = carbTime > 0; calculate() },
                    onMinus = { carbTime = (carbTime - 5).coerceIn(-60, 60); alarm = carbTime > 0; calculate() },
                    onPlus = { carbTime = (carbTime + 5).coerceIn(-60, 60); alarm = carbTime > 0; calculate() },
                    extra = { SmallAction("⏰", if (alarm) DashColors.High else DashColors.Dim, dim = !alarm) { alarm = !alarm; calculate() } },
                    modifier = Modifier.weight(1f), compact = true
                )
            }
            if (preferences.get(BooleanKey.OverviewShowNotesInDialogs))
                FieldBox(Modifier.padding(top = 8.dp)) {
                    Column(Modifier.weight(1f)) {
                        FieldLabel(rh.gs(R.string.dashboard_wizard_notes))
                        BasicTextField(
                            notes, { notes = it; calculate() }, singleLine = true,
                            textStyle = TextStyle(color = DashColors.Text, fontSize = 14.sp), cursorBrush = SolidColor(DashColors.Accent),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            // what to include (classic checkboxes; COB needs IOB)
            FlowChips(
                listOf(
                    Chip(rh.gs(R.string.dash_bg_label), useBg, true) { useBg = !useBg; calculate() },
                    Chip(rh.gs(R.string.dash_tt_label), useTt, ttAvailable && useBg) { useTt = !useTt; calculate() },
                    Chip(rh.gs(R.string.dash_bg_trend_label), useTrend, true) {
                        useTrend = !useTrend; preferences.put(BooleanNonKey.WizardIncludeTrend, useTrend); calculate()
                    },
                    Chip("IOB", useIob, true) {
                        useIob = !useIob; if (!useIob) useCob = false; preferences.put(BooleanNonKey.WizardIncludeCob, useCob); calculate()
                    },
                    Chip("COB", useCob, true) {
                        useCob = !useCob; if (useCob) useIob = true; preferences.put(BooleanNonKey.WizardIncludeCob, useCob); calculate()
                    }
                )
            )
            // calculation (same order as the classic result table)
            r?.let { view ->
                Row(
                    Modifier
                        .padding(top = 10.dp)
                        .fillMaxWidth()
                        .clickable { showCalc = !showCalc; uiPrefs().edit().putBoolean(KEY_CALC, showCalc).apply() }
                        .padding(vertical = 4.dp)
                ) {
                    Text(rh.gs(R.string.dashboard_wizard_show_calc) + if (showCalc) " ▴" else " ▾", color = DashColors.Sub, fontSize = 11.sp, modifier = Modifier.weight(1f))
                    if (showCalc)
                        Text(
                            (if (showEasy) "✓ " else "") + rh.gs(R.string.dashboard_wizard_easy),
                            color = if (showEasy) DashColors.Accent else DashColors.Sub, fontSize = 11.sp, fontWeight = if (showEasy) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(if (showEasy) DashColors.Accent.copy(alpha = 0.1f) else Color.Transparent)
                                .border(1.dp, if (showEasy) DashColors.Accent.copy(alpha = 0.6f) else DashColors.Line, RoundedCornerShape(50))
                                .clickable { showEasy = !showEasy; uiPrefs().edit().putBoolean(KEY_EASY, showEasy).apply() }
                                .padding(horizontal = 9.dp, vertical = 2.dp)
                        )
                }
                if (showCalc) view.rows.forEach { row ->
                    val easy = showEasy && row.easy.isNotEmpty()
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(27.dp), verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            row.label, color = if (easy) DashColors.Text else DashColors.Sub, fontSize = 12.5.sp, fontWeight = if (easy) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier.width(80.dp), maxLines = 1
                        )
                        Text(row.detail, color = DashColors.Dim, fontSize = 11.sp, textAlign = TextAlign.End, modifier = Modifier.weight(1f).padding(end = 10.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(row.insulin, color = DashColors.Text, fontSize = 12.5.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.End, modifier = Modifier.width(72.dp))
                    }
                    if (easy) Text(row.easy, color = DashColors.Sub, fontSize = 11.5.sp, lineHeight = 16.sp, modifier = Modifier.padding(bottom = 6.dp))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(DashColors.Line)
                    )
                }
                Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        rh.gs(R.string.dashboard_wizard_result) + (view.percent?.let { " ($it)" } ?: ""),
                        color = if (view.percent != null) DashColors.High else DashColors.Sub, fontSize = 13.sp, modifier = Modifier.weight(1f)
                    )
                    Text(view.total, color = if (view.canDeliver) DashColors.Accent else DashColors.Cob, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                }
                if (showCalc && showEasy) view.roundingNote?.let {
                    Text(it, color = DashColors.Sub, fontSize = 11.sp, textAlign = TextAlign.End, modifier = Modifier.fillMaxWidth())
                }
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionButton(rh.gs(R.string.dash_cancel), DashColors.Card, DashColors.Text, Modifier.weight(1f)) { onClose() }
                    if (view.canDeliver)
                        ActionButton(rh.gs(R.string.dashboard_wizard_ok), DashColors.Accent, DashColors.Bg, Modifier.weight(1.4f)) { deliver() }
                }
            }
        }
    }

    // ---------- meal tracking ----------

    private fun selectFood(f: MealFood?, isNew: Boolean = false) {
        food = f
        foodIsNew = isNew
        tracked = f != null && !isNew && f.tracked
        foodHistory = if (f != null && !isNew) mealTracker.mealsOf(f.id) else emptyList()
        if (historyApplied != null) undoHistory(recalc = false)
        foodSheet = false
        foodQuery = ""
        calculate()
    }

    private fun applyHistory(units: Double) {
        // the history amount goes into the correction field in U (only for this calculation)
        usePercentage = false
        correctionText = String.format(Locale.US, "%.2f", units)
        historyApplied = units
        calculate()
    }

    private fun undoHistory(recalc: Boolean = true) {
        if (!usePercentage && historyApplied?.let { abs(SafeParse.stringToDouble(correctionText) - it) < 0.001 } == true) correctionText = "0"
        historyApplied = null
        if (recalc) calculate()
    }

    @Composable
    private fun MealSection(isAi: Boolean) {
        val f = food
        FieldBox(
            Modifier.padding(top = 8.dp), border = if (f != null) DashColors.Accent.copy(alpha = 0.45f) else DashColors.Line,
            onClick = { foodSheet = !foodSheet; foodQuery = if (foodIsNew) f?.name.orEmpty() else "" }
        ) {
            Column(Modifier.weight(1f)) {
                FieldLabel(rh.gs(R.string.meal_food))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                    Text(
                        if (f == null) rh.gs(R.string.meal_food_none) else f.name, color = if (f == null) DashColors.Dim else DashColors.Text,
                        fontSize = 14.sp, fontWeight = if (f != null) FontWeight.Bold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (f != null && foodIsNew)
                        Text(
                            rh.gs(R.string.meal_new_food), color = AiPurple, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .padding(start = 6.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(AiPurple.copy(alpha = 0.15f))
                                .padding(horizontal = 5.dp, vertical = 1.dp)
                        )
                    if (f != null) Text("  " + MealUi.typeLabel(rh, f.type), color = DashColors.Dim, fontSize = 11.sp, maxLines = 1)
                }
            }
            // AI photo: fills the carbs and names the food
            SmallAction("📷", DashColors.Accent) { onAiPhoto() }
            if (f != null) {
                Spacer(Modifier.width(6.dp))
                SmallAction("✕", DashColors.Sub) { selectFood(null) }
            }
        }
        if (isAi && aiFoods.isNotEmpty())
            Text(
                aiFoods, color = DashColors.Sub, fontSize = 11.5.sp,
                modifier = Modifier
                    .padding(top = 6.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(AiPurple.copy(alpha = 0.07f))
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            )
        if (f == null && !foodSheet)
            Row(
                Modifier
                    .padding(top = 6.dp)
                    .horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                recentFoods.take(6).forEach { MealChip(it.name, false) { selectFood(it) } }
                MealChip("🔍 " + rh.gs(R.string.meal_search_new), false) { foodSheet = true }
            }
        // a new AI name close to a food that already has records: offer to link it
        val similar = if (f != null && foodIsNew) recentFoods.firstOrNull { MealUi.similar(it.name, f.name) } else null
        if (similar != null)
            Text(
                rh.gs(R.string.meal_similar, similar.name, mealTracker.mealsOf(similar.id).size), color = DashColors.Accent, fontSize = 11.5.sp,
                modifier = Modifier
                    .padding(top = 6.dp)
                    .clickable { selectFood(similar) }
            )
        if (foodSheet) FoodSheet(f)
        if (f != null) {
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("📈 " + rh.gs(R.string.meal_track), color = DashColors.Text, fontSize = 12.5.sp)
                Text(
                    "  " + rh.gs(R.string.meal_track_hours, f.type.hours), color = DashColors.Dim, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                MealSwitch(tracked) { tracked = !tracked }
            }
            if (!foodIsNew && foodHistory.isNotEmpty()) HistoryCard(f)
        }
    }

    /** the typed name: an existing food with that name, a rename of the new food, or a new food */
    private fun useQuery(f: MealFood?, q: String) {
        val same = recentFoods.firstOrNull { MealUi.norm(it.name) == MealUi.norm(q) }
        when {
            same != null            -> selectFood(same)
            f != null && foodIsNew  -> {
                food = f.copy(name = q); foodSheet = false; foodQuery = ""
            }

            else                    -> selectFood(MealFood(MealStore.newId(), q, MealMath.typeFor(aiFat, aiProtein), false, dateUtil.now()), isNew = true)
        }
    }

    @Composable
    private fun FoodSheet(f: MealFood?) {
        Column(
            Modifier
                .padding(top = 8.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xFF1A1F27))
                .border(1.dp, PopupLine, RoundedCornerShape(14.dp))
                .padding(10.dp)
        ) {
            FieldLabel(rh.gs(if (f != null && foodIsNew) R.string.meal_name else R.string.meal_search_hint))
            BasicTextField(
                foodQuery, { foodQuery = it }, singleLine = true,
                textStyle = TextStyle(color = DashColors.Text, fontSize = 14.sp), cursorBrush = SolidColor(DashColors.Accent),
                modifier = Modifier
                    .padding(top = 4.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(DashColors.Card)
                    .border(1.dp, DashColors.Line, RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 8.dp)
            )
            val q = foodQuery.trim()
            recentFoods.filter { q.isEmpty() || MealUi.matches(it.name, q) }.take(6).forEach { item ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { selectFood(item) }
                        .padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(item.name, color = DashColors.Text, fontSize = 13.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(MealUi.typeLabel(rh, item.type) + if (item.tracked) "  📈" else "", color = DashColors.Dim, fontSize = 11.sp)
                }
            }
            if (q.isNotEmpty() && recentFoods.none { MealUi.norm(it.name) == MealUi.norm(q) }) {
                val rename = f != null && foodIsNew
                Text(
                    rh.gs(if (rename) R.string.meal_rename else R.string.meal_add_new, q), color = DashColors.Accent, fontSize = 12.5.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { useQuery(f, q) }
                        .padding(vertical = 8.dp)
                )
            }
            if (f != null) {
                FieldLabel(rh.gs(R.string.meal_type_title))
                Row(
                    Modifier
                        .padding(top = 4.dp)
                        .horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FoodType.entries.forEach { t -> MealChip(MealUi.typeLabel(rh, t), f.type == t) { food = f.copy(type = t) } }
                }
                if (aiFat != null || aiProtein != null)
                    Text(
                        rh.gs(R.string.meal_type_ai, aiFat?.let { f1(it) } ?: "?", aiProtein?.let { f1(it) } ?: "?", MealTracker.typeText(rh, MealMath.typeFor(aiFat, aiProtein))),
                        color = DashColors.Sub, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp)
                    )
            }
            Text(
                rh.gs(R.string.meal_done), color = DashColors.Accent, fontSize = 12.5.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.End,
                modifier = Modifier
                    .fillMaxWidth()
                    // a typed name counts as chosen (same as tapping the match or "+ new food")
                    .clickable { if (q.isEmpty()) foodSheet = false else useQuery(f, q) }
                    .padding(top = 8.dp)
            )
        }
    }

    private fun f1(v: Double) = String.format(Locale.getDefault(), "%.1f", v)

    private fun signedU(v: Double) = (if (v > 0) "+" else "") + String.format(Locale.getDefault(), "%.2f", v) + " U"

    @Composable
    private fun HistoryCard(f: MealFood) {
        val amber = DashColors.High
        val meals = foodHistory
        val day = SimpleDateFormat("M/d", Locale.getDefault())
        Column(
            Modifier
                .padding(top = 8.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(DashColors.Card2)
                .border(1.dp, amber.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
                .padding(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    rh.gs(R.string.meal_history_title, f.name), color = DashColors.Text, fontSize = 12.5.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Text(
                    rh.gs(R.string.meal_history_counts, meals.count { it.usable }, meals.count { it.eval != null && !it.usable }, meals.count { it.eval == null }),
                    color = DashColors.Sub, fontSize = 10.5.sp
                )
            }
            meals.take(3).forEach { m ->
                val e = m.eval
                Row(Modifier.padding(top = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    GradeChip(rh, if (m.excludedByUser) MealGrade.EXCLUDED else e?.grade, pending = e == null, ask = e?.needsAnswer == true && e.grade != MealGrade.EXCLUDED)
                    Text(
                        "  " + day.format(Date(m.time)) + "  " + rh.gs(
                            R.string.meal_history_line, m.carbs.toInt(),
                            e?.takeIf { it.neededInsulin > 0 }?.let { MealTracker.signed(it.ratioPct) + "%" } ?: "—"
                        ),
                        color = DashColors.Sub, fontSize = 11.5.sp, modifier = Modifier.weight(1f), maxLines = 1
                    )
                    if (e != null && e.maxBg > 0) Text(rh.gs(R.string.meal_max, e.maxBg), color = DashColors.Dim, fontSize = 11.sp)
                }
                // a less trusted start counts less in the suggestion
                if (m.usable && e!!.reliability != app.aaps.plugins.main.general.dashboard.meal.Reliability.HIGH)
                    Text(
                        rh.gs(R.string.meal_rel_short, MealTracker.reliabilityText(rh, e.reliability)), fontSize = 10.5.sp,
                        color = if (e.reliability == app.aaps.plugins.main.general.dashboard.meal.Reliability.LOW) DashColors.Low else DashColors.Sub,
                        modifier = Modifier.padding(start = 4.dp, top = 1.dp)
                    )
                val memo = (m.memoTags + listOfNotNull(m.memo.takeIf { it.isNotBlank() })).joinToString(" · ")
                if (memo.isNotEmpty()) Text("📝 $memo", color = AiLilac, fontSize = 11.sp, maxLines = 2, modifier = Modifier.padding(start = 4.dp, top = 1.dp))
            }
            Box(
                Modifier
                    .padding(vertical = 8.dp)
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(DashColors.Line)
            )
            val s = suggestion
            if (s == null)
                Text(
                    if (meals.count { it.countable } >= MealMath.MIN_RECORDS) rh.gs(R.string.meal_suggest_carbs)
                    else rh.gs(R.string.meal_suggest_need, MealMath.MIN_RECORDS, meals.count { it.countable }),
                    color = DashColors.Sub, fontSize = 11.5.sp
                )
            else {
                Text(rh.gs(R.string.meal_suggest_gpu, f1(s.gramsPerUnit), f1(currentIc)), color = DashColors.Text, fontSize = 12.sp)
                Text(
                    rh.gs(
                        R.string.meal_suggest_calc, lastCarbs, f1(s.gramsPerUnit), String.format(Locale.getDefault(), "%.2f", s.historyInsulin), f1(currentIc),
                        String.format(Locale.getDefault(), "%.2f", s.currentInsulin), signedU(if (s.capped) s.historyInsulin - s.currentInsulin else s.units)
                    ),
                    color = DashColors.Dim, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp)
                )
                if (s.capped) Text(rh.gs(R.string.meal_suggest_capped, (MealMath.CAP * 100).toInt(), signedU(s.units)), color = DashColors.Sub, fontSize = 11.sp)
                if (s.blockedByHypo) Text(rh.gs(R.string.meal_suggest_hypo), color = DashColors.Low, fontSize = 11.sp)
                if (abs(s.units) >= 0.001) {
                    val applied = historyApplied?.let { !usePercentage && abs(SafeParse.stringToDouble(correctionText) - it) < 0.001 } == true
                    Text(
                        if (applied) rh.gs(R.string.meal_applied, signedU(historyApplied ?: 0.0)) else rh.gs(R.string.meal_apply, signedU(s.units)),
                        color = amber, fontSize = 12.5.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                        modifier = Modifier
                            .padding(top = 8.dp)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(amber.copy(alpha = if (applied) 0.06f else 0.15f))
                            .border(1.dp, amber.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                            .clickable { if (applied) undoHistory() else applyHistory(s.units) }
                            .padding(vertical = 9.dp)
                    )
                } else if (!s.blockedByHypo) Text(rh.gs(R.string.meal_suggest_none), color = DashColors.Sub, fontSize = 11.5.sp, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }

    // ---------- small UI parts ----------

    private data class Chip(val text: String, val on: Boolean, val enabled: Boolean, val onClick: () -> Unit)

    @Composable
    private fun FlowChips(chips: List<Chip>) {
        // two simple rows are enough for 5-6 chips on a phone
        val rows = chips.chunked(4)
        rows.forEach { row ->
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { c ->
                    Text(
                        (if (c.on) "✓ " else "") + c.text, fontSize = 11.5.sp, maxLines = 1,
                        color = if (!c.enabled) DashColors.Dim.copy(alpha = 0.5f) else if (c.on) DashColors.Accent else DashColors.Sub,
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .border(1.dp, if (c.on && c.enabled) DashColors.Accent.copy(alpha = 0.6f) else DashColors.Line, RoundedCornerShape(999.dp))
                            .background(if (c.on && c.enabled) DashColors.Accent.copy(alpha = 0.08f) else Color.Transparent)
                            .clickable(enabled = c.enabled, onClick = c.onClick)
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                    )
                }
            }
        }
    }

    @Composable
    private fun FieldBox(modifier: Modifier = Modifier, border: Color = DashColors.Line, onClick: (() -> Unit)? = null, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
        Row(
            modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(DashColors.Card2)
                .border(1.dp, border, RoundedCornerShape(10.dp))
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically, content = content
        )
    }

    @Composable
    private fun FieldLabel(text: String, color: Color = DashColors.Sub) {
        Text(text, color = color, fontSize = 10.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }

    @Composable
    private fun Stepper(
        label: String, labelColor: Color, value: String, unit: String,
        onValue: (String) -> Unit, onMinus: () -> Unit, onPlus: () -> Unit,
        modifier: Modifier = Modifier, border: Color = DashColors.Line,
        extra: (@Composable () -> Unit)? = null, compact: Boolean = false
    ) {
        // label on its own line so narrow (half width) fields do not cut it
        Column(
            modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(DashColors.Card2)
                .border(1.dp, border, RoundedCornerShape(10.dp))
                .padding(horizontal = 10.dp, vertical = 7.dp)
        ) {
            FieldLabel(label, labelColor)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 3.dp)) {
            PmButton("−", onMinus)
            Column(
                Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp)
            ) {
                Row(verticalAlignment = Alignment.Bottom) {
                    BasicTextField(
                        value, { v -> onValue(v.filter { it.isDigit() || it == '.' || it == '-' }) }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        textStyle = TextStyle(color = DashColors.Text, fontSize = if (compact) 16.sp else 18.sp, fontWeight = FontWeight.Bold),
                        cursorBrush = SolidColor(DashColors.Accent),
                        // as wide as the number, so the unit right after it is never pushed out
                        modifier = Modifier
                            .width(IntrinsicSize.Min)
                            .widthIn(min = 32.dp, max = if (compact) 64.dp else 96.dp)
                    )
                    Text(" $unit", color = DashColors.Dim, fontSize = if (compact) 10.sp else 11.sp, maxLines = 1, softWrap = false)
                }
            }
            extra?.let {
                it()
                Spacer(Modifier.width(6.dp))
            }
            PmButton("+", onPlus)
            }
        }
    }

    @Composable
    private fun PmButton(text: String, onClick: () -> Unit) {
        Box(
            Modifier
                .size(30.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(DashColors.Line)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) { Text(text, color = DashColors.Sub, fontSize = 16.sp, fontWeight = FontWeight.Bold) }
    }

    @Composable
    private fun SmallAction(text: String, color: Color, dim: Boolean = false, onClick: () -> Unit) {
        Text(
            text, color = color, fontSize = 11.5.sp, fontWeight = FontWeight.Bold, maxLines = 1,
            modifier = Modifier
                .alpha(if (dim) 0.35f else 1f)
                .clip(RoundedCornerShape(8.dp))
                .border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                .clickable(onClick = onClick)
                .padding(horizontal = 8.dp, vertical = 5.dp)
        )
    }

    @Composable
    private fun ActionButton(text: String, bg: Color, fg: Color, modifier: Modifier, onClick: () -> Unit) {
        Box(
            modifier
                .height(44.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(bg)
                .border(1.dp, if (bg == DashColors.Card) DashColors.Line else bg, RoundedCornerShape(8.dp))
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) { Text(text, color = fg, fontSize = 14.sp, fontWeight = FontWeight.Bold) }
    }

    companion object {

        private val PopupBg = Color(0xFF222833)
        private val PopupLine = Color(0xFF3A4250)
        private val AiPurple = Color(0xFFA78BFA)
        private val AiLilac = Color(0xFFC4B5FD)

        private const val KEY_EASY = "wizard_easy"
        private const val KEY_PERCENT = "wizard_percent"
        private const val KEY_CALC = "wizard_calc"
    }
}
