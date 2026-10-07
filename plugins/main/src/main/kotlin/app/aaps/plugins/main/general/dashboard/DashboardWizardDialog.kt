package app.aaps.plugins.main.general.dashboard

import android.graphics.Color as AndroidColor
import android.graphics.drawable.ColorDrawable
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.time.T
import app.aaps.core.interfaces.constraints.ConstraintsChecker
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.Profile
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.AapsSchedulers
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventAutosensCalculationFinished
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.Round
import app.aaps.core.interfaces.utils.SafeParse
import app.aaps.core.interfaces.utils.fabric.FabricPrivacy
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.objects.constraints.ConstraintObject
import app.aaps.core.objects.extensions.valueToUnits
import app.aaps.core.objects.profile.ProfileSealed
import app.aaps.core.objects.wizard.BolusWizard
import app.aaps.core.ui.toast.ToastUtils
import app.aaps.plugins.main.R
import dagger.android.support.DaggerDialogFragment
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.plusAssign
import java.util.Locale
import javax.inject.Inject
import javax.inject.Provider
import kotlin.math.abs

/**
 * Personal-fork: Dashboard calculator. Same inputs, defaults and engine as the classic Bolus wizard
 * ([BolusWizard.doCalc] / [BolusWizard.confirmAndExecute], which are only called, not changed), plus the AI photo carbs
 * (Gemini) feeding the carbs field and an on-demand AI explanation of the result.
 */
class DashboardWizardDialog : DaggerDialogFragment() {

    @Inject lateinit var aapsLogger: AAPSLogger
    @Inject lateinit var rh: ResourceHelper
    @Inject lateinit var preferences: Preferences
    @Inject lateinit var profileFunction: ProfileFunction
    @Inject lateinit var profileUtil: ProfileUtil
    @Inject lateinit var activePlugin: ActivePlugin
    @Inject lateinit var persistenceLayer: PersistenceLayer
    @Inject lateinit var iobCobCalculator: IobCobCalculator
    @Inject lateinit var constraintChecker: ConstraintsChecker
    @Inject lateinit var dateUtil: DateUtil
    @Inject lateinit var bolusWizardProvider: Provider<BolusWizard>
    @Inject lateinit var uiInteraction: UiInteraction
    @Inject lateinit var rxBus: RxBus
    @Inject lateinit var aapsSchedulers: AapsSchedulers
    @Inject lateinit var fabricPrivacy: FabricPrivacy

    private val disposable = CompositeDisposable()

    // ---- inputs (same meaning and defaults as the classic wizard) ----
    private var profileNames by mutableStateOf(listOf<String>())
    private var profileIndex by mutableIntStateOf(0)
    private var bgText by mutableStateOf("0")
    private var sensorBgText by mutableStateOf<String?>(null)
    private var carbsText by mutableStateOf("0")
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
    private var useSb by mutableStateOf(false)
    private var showCalc by mutableStateOf(true)
    private var showEasy by mutableStateOf(true)

    // ---- results ----
    private var result by mutableStateOf<WizardView?>(null)

    private var wizard: BolusWizard? = null
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // the AI photo dialog hands its carbs back here instead of opening the classic wizard
        childFragmentManager.setFragmentResultListener(UiInteraction.AI_CARBS_RESULT_KEY, this) { _, bundle ->
            val carbs = bundle.getInt(UiInteraction.AI_CARBS_RESULT_CARBS)
            carbsText = carbs.toString()
            aiCarbs = carbs
            aiFoods = bundle.getString(UiInteraction.AI_CARBS_RESULT_FOODS).orEmpty()
            calculate()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        dialog?.window?.requestFeature(Window.FEATURE_NO_TITLE)
        dialog?.window?.setBackgroundDrawable(ColorDrawable(AndroidColor.TRANSPARENT))
        dialog?.setCanceledOnTouchOutside(false)
        init()
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { WizardScreen() }
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    override fun onDestroy() {
        disposable.clear()
        super.onDestroy()
    }

    // ---------- logic (mirrors WizardDialog) ----------

    private fun init() {
        val profileStore = activePlugin.activeProfileSource.profile
        if (profileFunction.getProfile() == null || profileStore == null) {
            ToastUtils.errorToast(context, app.aaps.core.ui.R.string.noprofile)
            dismiss()
            return
        }
        units = profileFunction.getUnits()
        bolusStep = activePlugin.activePump.pumpDescription.bolusStep
        maxCarbs = constraintChecker.getMaxCarbsAllowed().value()
        maxCorrection = constraintChecker.getMaxBolusAllowed().value()
        profileNames = listOf(rh.gs(app.aaps.core.ui.R.string.active)) + profileStore.getProfileList().map { it.toString() }
        useTrend = preferences.get(BooleanKey.WizardIncludeTrend)
        useCob = preferences.get(BooleanKey.WizardIncludeCob)
        if (useCob) useIob = true
        usePercentage = preferences.get(BooleanKey.WizardCorrectionPercent)
        showCalc = preferences.get(BooleanKey.WizardCalculationVisible)
        showEasy = uiPrefs().getBoolean(KEY_EASY, true)
        ttAvailable = persistenceLayer.getTemporaryTargetActiveAt(dateUtil.now()) != null
        calculatedPercentage = preferences.get(IntKey.OverviewBolusPercentage)
        if (usePercentage) {
            // same as the classic wizard: without a recent BG the percentage falls back to 100 %
            var percentage = calculatedPercentage
            val time = preferences.get(IntKey.OverviewResetBolusPercentageTime).toLong()
            val last = persistenceLayer.getLastGlucoseValue()
            if (last == null || last.timestamp < dateUtil.now() - T.mins(time).msecs()) percentage = 100
            correctionText = percentage.toString()
        }
        val bg = iobCobCalculator.ads.actualBg()?.valueToUnits(units) ?: 0.0
        bgText = formatBg(bg)
        sensorBgText = if (bg > 0) bgText else null
        disposable += rxBus.toObservable(EventAutosensCalculationFinished::class.java)
            .observeOn(aapsSchedulers.main)
            .subscribe({ calculate() }, fabricPrivacy::logException)
        calculate()
    }

    private fun uiPrefs() = requireContext().getSharedPreferences("dashboard_ui", Context.MODE_PRIVATE)

    private fun formatBg(v: Double) = if (units == GlucoseUnit.MGDL) String.format(Locale.US, "%.0f", v) else String.format(Locale.US, "%.1f", v)

    private fun calculate() {
        val profileStore = activePlugin.activeProfileSource.profile ?: return
        val selected = profileNames.getOrNull(profileIndex) ?: return
        var profileName = selected
        val profile: Profile? = if (profileIndex == 0) {
            profileName = profileFunction.getProfileName()
            profileFunction.getProfile()
        } else profileStore.getSpecificProfile(selected)?.let { ProfileSealed.Pure(it, activePlugin) }
        profile ?: return

        var bg = SafeParse.stringToDouble(bgText)
        val carbs = SafeParse.stringToInt(carbsText)
        val correction = if (!usePercentage) {
            if (Round.roundTo(calculatedCorrection, bolusStep) == SafeParse.stringToDouble(correctionText)) calculatedCorrection
            else SafeParse.stringToDouble(correctionText)
        } else 0.0
        val percentageCorrection = if (usePercentage) {
            if (calculatedPercentage == SafeParse.stringToInt(correctionText)) calculatedPercentage else SafeParse.stringToInt(correctionText)
        } else preferences.get(IntKey.OverviewBolusPercentage)
        val carbsAfterConstraint = constraintChecker.applyCarbsConstraints(ConstraintObject(carbs, aapsLogger)).value()
        if (abs(carbs - carbsAfterConstraint) > 0.01) {
            carbsText = "0"
            ToastUtils.warnToast(context, rh.gs(R.string.dashboard_wizard_carbs_constraint))
            return
        }
        bg = if (useBg) bg else 0.0
        val tempTarget = persistenceLayer.getTemporaryTargetActiveAt(dateUtil.now())
        var cob = 0.0
        if (useCob) iobCobCalculator.getCobInfo("Wizard COB").displayCob?.let { cob = it }

        val w = bolusWizardProvider.get().doCalc(
            profile, profileName, tempTarget, carbsAfterConstraint, cob, bg, correction, preferences.get(IntKey.OverviewBolusPercentage),
            useBg, useCob, useIob, useIob, useSb, useTt && ttAvailable, useTrend, alarm, notes, carbTime,
            usePercentage = usePercentage, totalPercentage = percentageCorrection.toDouble()
        )
        wizard = w
        calculatedPercentage = w.calculatedPercentage
        calculatedCorrection = w.calculatedCorrection

        val u = { v: Double -> rh.gs(app.aaps.core.ui.R.string.format_insulin_units, v) }
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
                    rh.gs(app.aaps.core.ui.R.string.bg_label), bgFormula, u(w.insulinFromBG),
                    if (useBg && has(w.insulinFromBG))
                        rh.gs(if (bgDiff > 0) R.string.dashboard_wizard_easy_bg_high else R.string.dashboard_wizard_easy_bg_low, formatBg(abs(bgDiff)), f1(w.sens))
                    else ""
                )
            )
            add(
                WizardRow(
                    rh.gs(app.aaps.core.ui.R.string.bg_trend_label), if (useTrend && w.glucoseStatus != null) "$trendText ÷ ISF ${f1(w.sens)}" else "", u(w.insulinFromTrend),
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
                    rh.gs(app.aaps.core.ui.R.string.carbs), "${carbs}g ÷ IC ${f1(w.ic)}", u(w.insulinFromCarbs),
                    if (has(w.insulinFromCarbs)) rh.gs(R.string.dashboard_wizard_easy_carbs, f1(w.ic)) else ""
                )
            )
            if (preferences.get(BooleanKey.OverviewUseSuperBolus))
                add(
                    WizardRow(
                        rh.gs(app.aaps.core.ui.R.string.superbolus), if (useSb) rh.gs(R.string.dashboard_wizard_sb_formula) else "", u(w.insulinFromSuperBolus),
                        if (useSb && has(w.insulinFromSuperBolus)) rh.gs(R.string.dashboard_wizard_easy_sb) else ""
                    )
                )
            add(
                WizardRow(
                    rh.gs(R.string.dashboard_wizard_correction), if (usePercentage) "" else rh.gs(R.string.dashboard_wizard_entered), u(w.insulinFromCorrection),
                    if (has(w.insulinFromCorrection)) rh.gs(R.string.dashboard_wizard_easy_correction) else ""
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
        calculate()
        // same confirmation, constraints and delivery as the classic wizard
        context?.let { wizard?.confirmAndExecute(it) }
        dismiss()
    }

    private fun step(text: String, delta: Double, min: Double, max: Double, decimals: Int): String {
        val v = (SafeParse.stringToDouble(text) + delta).coerceIn(min, max)
        return if (decimals == 0) String.format(Locale.US, "%.0f", v) else String.format(Locale.US, "%.${decimals}f", Round.roundTo(v, delta.let { abs(it) }))
    }

    // ---------- UI ----------

    @Composable
    private fun WizardScreen() {
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
                Text("🧮 " + rh.gs(app.aaps.core.ui.R.string.boluswizard), color = DashColors.Text, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
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
                        rh.gs(app.aaps.core.ui.R.string.profile) + " ", color = DashColors.Dim, fontSize = 11.sp
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
                label = rh.gs(app.aaps.core.ui.R.string.bg_label) + if (bgEdited) " · " + rh.gs(R.string.dashboard_wizard_bg_edited, sensorBgText) else "",
                labelColor = if (bgEdited) DashColors.High else DashColors.Sub, value = bgText, unit = units.asText,
                border = if (bgEdited) DashColors.High.copy(alpha = 0.55f) else DashColors.Line,
                onValue = { bgText = it; calculate() },
                onMinus = { bgText = step(bgText, -bgStep, 0.0, bgMax, if (units == GlucoseUnit.MGDL) 0 else 1); calculate() },
                onPlus = { bgText = step(bgText, bgStep, 0.0, bgMax, if (units == GlucoseUnit.MGDL) 0 else 1); calculate() },
                extra = if (bgEdited) ({ SmallAction("↺", DashColors.Accent) { bgText = sensorBgText!!; calculate() } }) else null,
                modifier = Modifier.weight(1f), compact = true
            )
            // carbs (+ AI photo)
            Stepper(
                label = rh.gs(app.aaps.core.ui.R.string.carbs) + if (isAi) " · ✦ " + rh.gs(R.string.dashboard_wizard_ai_estimate) else "",
                labelColor = if (isAi) AiLilac else DashColors.Sub, value = carbsText, unit = "g",
                border = if (isAi) AiPurple.copy(alpha = 0.55f) else DashColors.Line,
                onValue = { carbsText = it; calculate() },
                onMinus = { carbsText = step(carbsText, -1.0, 0.0, maxCarbs.toDouble(), 0); calculate() },
                onPlus = { carbsText = step(carbsText, 1.0, 0.0, maxCarbs.toDouble(), 0); calculate() },
                extra = { SmallAction("📷", AiLilac) { uiInteraction.runAiCarbsDialog(childFragmentManager, returnResult = true) } },
                modifier = Modifier.weight(1f), compact = true
            )
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
                            preferences.put(BooleanKey.WizardCorrectionPercent, usePercentage)
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
                    Chip(rh.gs(app.aaps.core.ui.R.string.bg_label), useBg, true) { useBg = !useBg; calculate() },
                    Chip(rh.gs(app.aaps.core.ui.R.string.tt_label), useTt, ttAvailable && useBg) { useTt = !useTt; calculate() },
                    Chip(rh.gs(app.aaps.core.ui.R.string.bg_trend_label), useTrend, true) {
                        useTrend = !useTrend; preferences.put(BooleanKey.WizardIncludeTrend, useTrend); calculate()
                    },
                    Chip("IOB", useIob, true) {
                        useIob = !useIob; if (!useIob) useCob = false; preferences.put(BooleanKey.WizardIncludeCob, useCob); calculate()
                    },
                    Chip("COB", useCob, true) {
                        useCob = !useCob; if (useCob) useIob = true; preferences.put(BooleanKey.WizardIncludeCob, useCob); calculate()
                    }
                ) + if (preferences.get(BooleanKey.OverviewUseSuperBolus)) listOf(Chip(rh.gs(app.aaps.core.ui.R.string.superbolus), useSb, true) { useSb = !useSb; calculate() }) else emptyList()
            )
            // calculation (same order as the classic result table)
            r?.let { view ->
                Row(
                    Modifier
                        .padding(top = 10.dp)
                        .fillMaxWidth()
                        .clickable { showCalc = !showCalc; preferences.put(BooleanKey.WizardCalculationVisible, showCalc) }
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
                    ActionButton(rh.gs(app.aaps.core.ui.R.string.cancel), DashColors.Card, DashColors.Text, Modifier.weight(1f)) { dismiss() }
                    if (view.canDeliver)
                        ActionButton(rh.gs(R.string.dashboard_wizard_ok), DashColors.Accent, DashColors.Bg, Modifier.weight(1.4f)) { deliver() }
                }
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
                            .widthIn(min = 14.dp, max = if (compact) 64.dp else 96.dp)
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
    }
}
