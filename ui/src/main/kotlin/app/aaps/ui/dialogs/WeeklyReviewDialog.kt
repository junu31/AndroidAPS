package app.aaps.ui.dialogs

import android.content.Context
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.interfaces.ai.AiTextEngine
import app.aaps.core.interfaces.autotune.Autotune
import app.aaps.core.interfaces.autotune.AutotuneSummary
import app.aaps.core.interfaces.autotune.AutotuneTrace
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.AapsSchedulers
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.MidnightTime
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.ui.dialogs.OKDialog
import app.aaps.ui.R
import app.aaps.ui.ai.WeeklyReviewMath
import app.aaps.ui.databinding.DialogWeeklyReviewBinding
import dagger.android.support.DaggerDialogFragment
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.plusAssign
import retrofit2.HttpException
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Personal-fork feature (Dashboard weekly review).
 *
 * Runs Autotune for the last 7 days, shows the suggested basal / ISF / IC next to the current values,
 * lets Gemini explain the suggestion (advisory text only) and offers the same profile actions as the
 * Autotune tab: store as a new profile, overwrite the input profile, revert the overwrite.
 * Nothing is activated: the user still has to do a profile switch.
 */
class WeeklyReviewDialog : DaggerDialogFragment() {

    companion object {

        const val DAYS = 7

        // The AI explanation of the last Autotune run is stored, so reopening the dialog or restarting
        // the app shows it again without another API call. A new Autotune run replaces it.
        private const val PREFS = "weekly_review"
        private const val KEY_AI_RUN = "ai_run_time"
        private const val KEY_AI_TEXT = "ai_text"

        private const val SYSTEM_PROMPT =
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

    @Inject lateinit var aapsLogger: AAPSLogger
    @Inject lateinit var aapsSchedulers: AapsSchedulers
    @Inject lateinit var rh: ResourceHelper
    @Inject lateinit var preferences: Preferences
    @Inject lateinit var dateUtil: DateUtil
    @Inject lateinit var profileUtil: ProfileUtil
    @Inject lateinit var persistenceLayer: PersistenceLayer
    @Inject lateinit var autotune: Autotune
    @Inject lateinit var aiTextEngine: AiTextEngine

    private val disposable = CompositeDisposable()
    private var _binding: DialogWeeklyReviewBinding? = null
    private val binding get() = _binding!!
    private var quality: List<WeeklyReviewMath.DayQuality> = emptyList()

    override fun onStart() {
        super.onStart()
        dialog?.window?.setBackgroundDrawableResource(android.R.color.transparent)
        val metrics = resources.displayMetrics
        dialog?.window?.setLayout(metrics.widthPixels - (24 * metrics.density).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        dialog?.window?.requestFeature(Window.FEATURE_NO_TITLE)
        isCancelable = true
        _binding = DialogWeeklyReviewBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.closeButton.setOnClickListener { dismiss() }
        binding.runButton.setOnClickListener { confirmRun() }
        binding.aiButton.setOnClickListener { autotune.lastResultSummary()?.let { explain(it) } }
        binding.saveNewButton.setOnClickListener { saveNew() }
        binding.overwriteButton.setOnClickListener { overwrite() }
        binding.revertButton.setOnClickListener { revert() }

        loadQuality()
        if (autotune.calculationRunning) watchRun()
        else showSummary(autotune.lastResultSummary())
    }

    override fun onDestroyView() {
        disposable.clear()
        // a preloaded model that was not used is freed with the dialog
        aiTextEngine.release()
        _binding = null
        super.onDestroyView()
    }

    // ---------- data quality ----------

    private fun loadQuality() {
        disposable += Single.fromCallable {
            val now = dateUtil.now()
            WeeklyReviewMath.dayStarts(now, MidnightTime.calc(now), DAYS).map { start ->
                val end = start + T_DAY
                WeeklyReviewMath.DayQuality(
                    dayStart = start,
                    bgCount = persistenceLayer.getBgReadingsDataFromTimeToTime(start, end, true).size,
                    carbEntries = persistenceLayer.getCarbsFromTimeToTimeExpanded(start, end, true).count { it.amount > 0 }
                )
            }
        }
            .subscribeOn(aapsSchedulers.io)
            .observeOn(aapsSchedulers.main)
            .subscribe({ showQuality(it) }, { aapsLogger.error(LTag.UI, "Weekly review quality", it) })
    }

    private fun showQuality(days: List<WeeklyReviewMath.DayQuality>) {
        quality = days
        _binding ?: return
        val ctx = requireContext()
        val dp = resources.displayMetrics.density
        val dayFmt = SimpleDateFormat("E", Locale.getDefault())
        binding.qualityDays.removeAllViews()
        days.forEachIndexed { i, d ->
            binding.qualityDays.addView(TextView(ctx).apply {
                text = dayFmt.format(Date(d.dayStart))
                gravity = Gravity.CENTER
                textSize = 11f
                setPadding(0, (6 * dp).toInt(), 0, (6 * dp).toInt())
                setTextColor(ContextCompat.getColor(ctx, if (d.ok) R.color.ai_ok else R.color.ai_low))
                setBackgroundResource(if (d.ok) R.drawable.weekly_review_day_ok else R.drawable.weekly_review_day_bad)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).also { if (i > 0) it.marginStart = (4 * dp).toInt() })
        }
        val bad = days.filterNot { it.ok }
        binding.qualitySummary.text = if (bad.isEmpty()) rh.gs(R.string.weekly_review_quality_ok) else rh.gs(R.string.weekly_review_quality_bad, days.size, bad.size)
        binding.qualitySummary.setTextColor(ContextCompat.getColor(ctx, if (bad.isEmpty()) R.color.ai_ok else R.color.ai_high))
        binding.qualityNote.text = bad.joinToString("\n") { qualityLine(it, dayFmt) }
        binding.qualityNote.visibility = if (bad.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun qualityLine(d: WeeklyReviewMath.DayQuality, dayFmt: SimpleDateFormat): String {
        val reasons = buildList {
            if (d.sensorGap) add(rh.gs(R.string.weekly_review_quality_sensor, d.bgCount))
            if (d.noCarbs) add(rh.gs(R.string.weekly_review_quality_carbs))
        }
        return dayFmt.format(Date(d.dayStart)) + ": " + reasons.joinToString(" · ")
    }

    // ---------- run ----------

    private fun confirmRun() {
        val summary = autotune.lastResultSummary()
        val message =
            if (summary?.canRevert == true) rh.gs(R.string.weekly_review_run_confirm_reverted, summary.profileName)
            else rh.gs(R.string.weekly_review_run_confirm)
        OKDialog.showConfirmation(requireContext(), rh.gs(R.string.weekly_review_run), message, { run() })
    }

    private fun run() {
        if (autotune.calculationRunning) return
        // all weekdays, current profile; same calculation as the Autotune tab
        disposable += Single.fromCallable { autotune.aapsAutotune(DAYS, false, "", BooleanArray(7) { true }) }
            .subscribeOn(aapsSchedulers.io)
            .subscribe({}, {
                // Autotune can throw (e.g. NaN with too little data) and then leaves its "running" flag set
                aapsLogger.error(LTag.UI, "Weekly review autotune", it)
                autotune.calculationRunning = false
                autotune.lastRunSuccess = false
            })
        watchRun()
    }

    private fun watchRun() {
        showStatus(rh.gs(R.string.weekly_review_running), running = true)
        binding.resultSection.visibility = View.GONE
        binding.runButton.isEnabled = false
        disposable += Observable.interval(1, TimeUnit.SECONDS)
            .observeOn(aapsSchedulers.main)
            .filter { !autotune.calculationRunning }
            .take(1)
            .subscribe {
                binding.runButton.isEnabled = true
                val summary = autotune.lastResultSummary()
                if (autotune.lastRunSuccess && summary != null) {
                    showSummary(summary)
                    explain(summary)
                } else showStatus(rh.gs(R.string.weekly_review_failed), running = false)
            }
    }

    private fun showStatus(text: String, running: Boolean) {
        _binding ?: return
        binding.statusCard.visibility = View.VISIBLE
        binding.statusText.text = text
        binding.progress.visibility = if (running) View.VISIBLE else View.GONE
    }

    // ---------- result ----------

    private fun showSummary(summary: AutotuneSummary?) {
        _binding ?: return
        if (summary == null) {
            binding.meta.text = ""
            showStatus(rh.gs(R.string.weekly_review_none), running = false)
            binding.resultSection.visibility = View.GONE
            return
        }
        binding.meta.text = rh.gs(R.string.weekly_review_meta, summary.days, summary.profileName, dateUtil.dateString(summary.runTime))
        binding.statusCard.visibility = View.GONE
        binding.resultSection.visibility = View.VISIBLE
        renderBasal(summary)
        renderRatios(summary)
        binding.overwriteButton.visibility = if (summary.canUpdate) View.VISIBLE else View.GONE
        binding.revertButton.visibility = if (summary.canRevert) View.VISIBLE else View.GONE
        val cached = storedExplanation(summary.runTime)
        binding.aiText.text = cached.orEmpty()
        binding.aiText.visibility = if (cached == null) View.GONE else View.VISIBLE
        binding.aiButton.visibility = if (cached == null) View.VISIBLE else View.GONE
        // the AI button is shown: load the local model now so the explanation starts at once when tapped
        if (cached == null) aiTextEngine.preload()
    }

    private fun renderBasal(s: AutotuneSummary) {
        binding.basalRows.removeAllViews()
        WeeklyReviewMath.basalRanges(s.currentBasal, s.tunedBasal).forEach { r ->
            addRow(
                binding.basalRows,
                String.format(Locale.getDefault(), "%02d–%02d", r.startHour, r.endHour),
                String.format(Locale.getDefault(), "%.3f", r.current),
                String.format(Locale.getDefault(), "%.3f", r.tuned),
                r.changePct
            )
            s.trace?.let { trace ->
                val why = WeeklyReviewMath.basalReason(r, trace)
                val detail = basalDetail(why, trace)
                val reason = reasonText(basalTag(why, trace), basalReasonText(why, trace), expandable = true)
                reason.setOnClickListener { detail.visibility = if (detail.visibility == View.VISIBLE) View.GONE else View.VISIBLE }
                binding.basalRows.addView(reason)
                binding.basalRows.addView(detail)
            }
        }
        s.trace?.let { binding.basalRows.addView(noteText(rh.gs(R.string.weekly_review_why_basal_note, it.days.size))) }
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

    /** per-day bars of the driving hours plus the formula; hidden until the reason is tapped */
    private fun basalDetail(r: WeeklyReviewMath.BasalReason, trace: AutotuneTrace): LinearLayout {
        val ctx = requireContext()
        val dp = resources.displayMetrics.density
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setBackgroundResource(R.drawable.weekly_review_reason_bg)
            setPadding((9 * dp).toInt(), (7 * dp).toInt(), (9 * dp).toInt(), (8 * dp).toInt())
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.bottomMargin = (6 * dp).toInt() }
        }
        box.addView(TextView(ctx).apply {
            text = rh.gs(R.string.weekly_review_why_detail_title, hourLabel(r.driverFrom, r.driverTo), profileUtil.units.asText)
            textSize = 11f
            setTextColor(ContextCompat.getColor(ctx, R.color.ai_sub))
        })
        val maxAbs = r.perDay.filterNotNull().maxOfOrNull { abs(it) }?.takeIf { it > 0 } ?: 1.0
        val barsH = (52 * dp).toInt()
        val bars = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, barsH).also { it.topMargin = (6 * dp).toInt() }
        }
        val labels = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val dayFmt = SimpleDateFormat("E", Locale.getDefault())
        trace.days.forEachIndexed { i, d ->
            val v = r.perDay.getOrNull(i)
            val color = when {
                v == null -> R.color.ai_dim
                v > 0     -> R.color.ai_high
                v < 0     -> R.color.ai_blue
                else      -> R.color.ai_dim
            }
            val h = if (v == null) (4 * dp).toInt() else ((abs(v) / maxAbs) * (barsH - 4 * dp) + 3 * dp).toInt()
            bars.addView(View(ctx).apply { setBackgroundColor(ContextCompat.getColor(ctx, color)) },
                         LinearLayout.LayoutParams(0, h, 1f).also { lp -> if (i > 0) lp.marginStart = (4 * dp).toInt() })
            labels.addView(TextView(ctx).apply {
                text = if (v == null) dayFmt.format(Date(d.dayStart)) + "·" + rh.gs(R.string.weekly_review_why_none) else dayFmt.format(Date(d.dayStart)) + "\n" + signedBg(v)
                textSize = 9.5f
                gravity = Gravity.CENTER
                setTextColor(ContextCompat.getColor(ctx, R.color.ai_dim))
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).also { lp -> if (i > 0) lp.marginStart = (4 * dp).toInt() })
        }
        box.addView(bars)
        box.addView(labels)
        if (r.daysWithData > 0 && r.avgIsf > 0) {
            val units = r.avgPerDay / r.avgIsf
            box.addView(TextView(ctx).apply {
                text = rh.gs(
                    if (units >= 0) R.string.weekly_review_why_detail_calc_more else R.string.weekly_review_why_detail_calc_less,
                    signedBg(r.avgPerDay), fmtIsf(r.avgIsf), String.format(Locale.getDefault(), "%.2f", abs(units))
                )
                textSize = 11.5f
                setLineSpacing(0f, 1.2f)
                setTextColor(ContextCompat.getColor(ctx, R.color.ai_text))
                setPadding(0, (6 * dp).toInt(), 0, 0)
            })
        }
        return box
    }

    private fun signedBg(mgdl: Double): String =
        (if (mgdl > 0) "+" else if (mgdl < 0) "−" else "") + profileUtil.fromMgdlToStringInUnits(abs(mgdl))

    private fun fmtIsf(mgdl: Double) =
        String.format(Locale.getDefault(), if (profileUtil.units == GlucoseUnit.MMOL) "%.2f" else "%.1f", profileUtil.fromMgdlToUnits(mgdl))

    /** small grey reason line under a row; [tag] is shown as a coloured prefix */
    private fun reasonText(tag: String?, text: String, expandable: Boolean = false): TextView {
        val ctx = requireContext()
        val dp = resources.displayMetrics.density
        val sb = SpannableStringBuilder()
        tag?.let {
            sb.append("[$it] ", ForegroundColorSpan(ContextCompat.getColor(ctx, R.color.ai_high)), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(StyleSpan(android.graphics.Typeface.BOLD), 0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        sb.append(text)
        if (expandable) sb.append("  ›", ForegroundColorSpan(ContextCompat.getColor(ctx, R.color.ai_dim)), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return TextView(ctx).apply {
            this.text = sb
            textSize = 11.5f
            setLineSpacing(0f, 1.2f)
            setTextColor(ContextCompat.getColor(ctx, R.color.ai_sub))
            setPadding(0, 0, 0, (6 * dp).toInt())
        }
    }

    private fun noteText(text: String) = TextView(requireContext()).apply {
        this.text = text
        textSize = 11f
        setLineSpacing(0f, 1.2f)
        setTextColor(ContextCompat.getColor(requireContext(), R.color.ai_dim))
        setPadding(0, (6 * resources.displayMetrics.density).toInt(), 0, 0)
    }

    private fun calcText(text: String) = TextView(requireContext()).apply {
        val dp = resources.displayMetrics.density
        this.text = text
        textSize = 11.5f
        setLineSpacing(0f, 1.2f)
        setTextColor(ContextCompat.getColor(requireContext(), R.color.ai_text))
        setBackgroundResource(R.drawable.weekly_review_reason_bg)
        setPadding((9 * dp).toInt(), (6 * dp).toInt(), (9 * dp).toInt(), (6 * dp).toInt())
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.bottomMargin = (6 * dp).toInt() }
    }

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

    private fun renderRatios(s: AutotuneSummary) {
        binding.ratioRows.removeAllViews()
        // same precision as the Autotune tab: ISF 1 decimal (mg/dL) / 2 decimals (mmol/L), IC 2 decimals
        val units = profileUtil.units
        val isfFormat = if (units == GlucoseUnit.MMOL) "%.2f" else "%.1f"
        addRow(
            binding.ratioRows, "ISF (${units.asText}/U)",
            String.format(Locale.getDefault(), isfFormat, profileUtil.fromMgdlToUnits(s.currentIsfMgdl)),
            String.format(Locale.getDefault(), isfFormat, profileUtil.fromMgdlToUnits(s.tunedIsfMgdl)),
            WeeklyReviewMath.changePct(s.currentIsfMgdl, s.tunedIsfMgdl)
        )
        s.trace?.let { trace ->
            val r = WeeklyReviewMath.isfReason(trace, s.currentIsfMgdl, s.tunedIsfMgdl)
            binding.ratioRows.addView(reasonText(if (r.capped) rh.gs(R.string.weekly_review_why_tag_limit) else null, isfReasonText(r, s)))
            r.medianRatio?.let { binding.ratioRows.addView(calcText(rh.gs(R.string.weekly_review_why_isf_calc, String.format(Locale.getDefault(), "%.2f", it), r.tunedDays, trace.days.size))) }
        }
        addRow(
            binding.ratioRows, "IC (g/U)",
            String.format(Locale.getDefault(), "%.2f", s.currentIc), String.format(Locale.getDefault(), "%.2f", s.tunedIc),
            WeeklyReviewMath.changePct(s.currentIc, s.tunedIc)
        )
        s.trace?.let { trace ->
            val r = WeeklyReviewMath.icReason(trace, s.currentIc, s.tunedIc)
            binding.ratioRows.addView(reasonText(if (r.capped) rh.gs(R.string.weekly_review_why_tag_limit) else null, icReasonText(r, s)))
            r.measuredIc?.let {
                binding.ratioRows.addView(
                    calcText(rh.gs(R.string.weekly_review_why_ic_calc, String.format(Locale.getDefault(), "%.0f", r.carbs), String.format(Locale.getDefault(), "%.1f", r.insulin), String.format(Locale.getDefault(), "%.2f", it)))
                )
            }
        }
    }

    /** label | current → suggested | change %, coloured: more = yellow, less = blue, same = dim */
    private fun addRow(parent: LinearLayout, label: String, current: String, tuned: String, pct: Int) {
        val ctx = requireContext()
        val dp = resources.displayMetrics.density
        val color = ContextCompat.getColor(ctx, if (pct > 0) R.color.ai_high else if (pct < 0) R.color.ai_blue else R.color.ai_dim)
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, (5 * dp).toInt(), 0, (5 * dp).toInt())
        }
        fun cell(text: String, c: Int, weight: Float, alignEnd: Boolean, bold: Boolean = false) = TextView(ctx).apply {
            this.text = text
            setTextColor(c)
            textSize = 12.5f
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = if (alignEnd) Gravity.END else Gravity.START
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight)
        }
        row.addView(cell(label, ContextCompat.getColor(ctx, R.color.ai_text), 1.4f, false))
        row.addView(cell(current, ContextCompat.getColor(ctx, R.color.ai_sub), 1f, true))
        row.addView(cell(tuned, if (pct == 0) ContextCompat.getColor(ctx, R.color.ai_dim) else color, 1f, true, bold = pct != 0))
        row.addView(cell(if (pct == 0) "0%" else String.format(Locale.getDefault(), "%+d%%", pct), color, 1f, true, bold = pct != 0))
        if (parent.childCount > 0) parent.addView(View(ctx).apply {
            setBackgroundColor(ContextCompat.getColor(ctx, R.color.ai_line))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp.toInt().coerceAtLeast(1))
        })
        parent.addView(row)
    }

    // ---------- AI explanation ----------

    private fun explain(summary: AutotuneSummary) {
        storedExplanation(summary.runTime)?.let { showAi(it, done = true); return }
        val apiKey = preferences.get(StringKey.OverviewAiCarbsApiKey).trim()
        if (!aiTextEngine.usesLocal && apiKey.isEmpty()) {
            showAi(rh.gs(R.string.ai_carbs_error_no_key), done = false)
            return
        }
        binding.aiButton.isEnabled = false
        binding.aiText.visibility = View.VISIBLE
        binding.aiText.text = rh.gs(R.string.weekly_review_ai_loading)
        // Gemini or the local model, as chosen in the Dashboard settings
        disposable += aiTextEngine.generate(SYSTEM_PROMPT, promptData(summary))
            .observeOn(aapsSchedulers.main)
            .subscribe({ result ->
                           val cleaned = result.text.lines().filter { it.isNotBlank() }.joinToString("\n") { it.trim().replaceFirst(Regex("^[-*•]\\s*"), "• ") } +
                               // which engine answered and how long it took; stored with the text so it survives restarts
                               "\n\n" + rh.gs(
                                   if (result.local) R.string.weekly_review_ai_source_local else R.string.weekly_review_ai_source,
                                   result.source, ((result.millis + 500) / 1000).toInt()
                               )
                           storeExplanation(summary.runTime, cleaned)
                           showAi(cleaned, done = true)
                       }, { error ->
                           aapsLogger.error(LTag.UI, "Weekly review AI", error)
                           _binding?.aiButton?.isEnabled = true
                           showAi(errorMessage(error), done = false)
                       })
    }

    /** [done] = explanation received (hide the button); otherwise keep the button for a retry */
    private val aiPrefs by lazy { requireContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    private fun storedExplanation(runTime: Long): String? =
        if (aiPrefs.getLong(KEY_AI_RUN, 0L) == runTime) aiPrefs.getString(KEY_AI_TEXT, null) else null

    private fun storeExplanation(runTime: Long, text: String) {
        aiPrefs.edit().putLong(KEY_AI_RUN, runTime).putString(KEY_AI_TEXT, text).apply()
    }

    private fun showAi(text: String, done: Boolean) {
        _binding ?: return
        binding.aiText.visibility = View.VISIBLE
        binding.aiText.text = text
        binding.aiButton.isEnabled = true
        binding.aiButton.visibility = if (done) View.GONE else View.VISIBLE
    }

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

    // ---------- profile actions (same as the Autotune tab) ----------

    private fun saveNew() {
        val name = "at_" + SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(dateUtil.now()))
        OKDialog.showConfirmation(requireContext(), rh.gs(R.string.weekly_review_save_new), rh.gs(R.string.weekly_review_save_new_confirm, name), {
            val used = autotune.copyTunedToNewProfile(name)
            OKDialog.show(requireContext(), rh.gs(R.string.weekly_review_save_new), if (used != null) rh.gs(R.string.weekly_review_saved, used) else rh.gs(R.string.weekly_review_failed))
        })
    }

    private fun overwrite() {
        val summary = autotune.lastResultSummary() ?: return
        OKDialog.showConfirmation(
            requireContext(), rh.gs(R.string.weekly_review_overwrite), rh.gs(R.string.weekly_review_overwrite_confirm, summary.profileName), {
                autotune.updateInputProfileWithTuned()
                showSummary(autotune.lastResultSummary())
            })
    }

    private fun revert() {
        val summary = autotune.lastResultSummary() ?: return
        OKDialog.showConfirmation(
            requireContext(), rh.gs(R.string.weekly_review_revert), rh.gs(R.string.weekly_review_revert_confirm, summary.profileName), {
                autotune.revertInputProfile()
                showSummary(autotune.lastResultSummary())
            })
    }
}

private const val T_DAY = 24 * 60 * 60 * 1000L
