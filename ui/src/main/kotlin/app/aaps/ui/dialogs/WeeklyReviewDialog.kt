package app.aaps.ui.dialogs

import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.interfaces.autotune.Autotune
import app.aaps.core.interfaces.autotune.AutotuneSummary
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
import app.aaps.core.interfaces.ai.AiTextEngine
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
            """You explain the result of AndroidAPS Autotune to the user in Korean.
You get the current and suggested hourly basal (U/h), ISF (mg/dL/U) and IC (g/U) from the last days,
plus data quality notes per day.
Write 3 to 5 short bullet lines starting with "- ", plain Korean, no headings, no markdown other than the bullets.
Rules:
- Describe what changes and what it means in everyday words (e.g. more basal at night means BG tended to rise then).
- Mention when the data quality notes make the result less reliable.
- Do NOT tell the user to apply the changes, do NOT give doses or medical advice; say decisions belong to the user and their care team only if needed.
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
        addRow(
            binding.ratioRows, "IC (g/U)",
            String.format(Locale.getDefault(), "%.2f", s.currentIc), String.format(Locale.getDefault(), "%.2f", s.tunedIc),
            WeeklyReviewMath.changePct(s.currentIc, s.tunedIc)
        )
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
