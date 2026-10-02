package app.aaps.ui.dialogs

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import androidx.core.os.bundleOf
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.AapsSchedulers
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.ui.R
import app.aaps.ui.ai.GeminiCarbService
import app.aaps.ui.databinding.DialogLoopExplainBinding
import dagger.android.support.DaggerDialogFragment
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.plusAssign
import retrofit2.HttpException
import java.io.IOException
import javax.inject.Inject

/**
 * Personal-fork feature.
 *
 * Explains one loop decision in plain Korean via Gemini. Opened only when the user taps
 * "왜? AI 설명" on the Dashboard, and the answer is cached per loop run, so each loop run
 * costs at most one API call. The explanation is advisory text only.
 */
class LoopExplainDialog : DaggerDialogFragment() {

    companion object {

        private const val ARG_RUN_TIME = "run_time"
        private const val ARG_DECISION = "decision"
        private const val ARG_REASON = "reason"
        private const val ARG_FACTS = "facts"

        /** Cached explanations by loop run time; only the last few runs are kept. */
        private val cache = LinkedHashMap<Long, String>()
        private const val CACHE_SIZE = 10

        private const val SYSTEM_PROMPT =
            """You explain decisions of an automated insulin delivery loop (AndroidAPS, oref1 algorithm) to its user.
You get the decision the loop made, a few current values and the algorithm's original reason text.
Explain in Korean, plain language, as 2 to 4 short bullet lines starting with "- ".
Rules:
- Explain WHY the loop chose this temp basal / SMB, using the numbers given (IOB, COB, predicted BG, target).
- Do NOT recommend doses, settings changes or any action. Do NOT give medical advice.
- If the reason text is unclear, say what can be read from it and keep it short.
- No markdown other than the "- " bullets, no headings, no English sentences."""

        fun newInstance(runTime: Long, decision: String, reason: String, facts: String) = LoopExplainDialog().also {
            it.arguments = bundleOf(ARG_RUN_TIME to runTime, ARG_DECISION to decision, ARG_REASON to reason, ARG_FACTS to facts)
        }
    }

    @Inject lateinit var aapsLogger: AAPSLogger
    @Inject lateinit var aapsSchedulers: AapsSchedulers
    @Inject lateinit var rh: ResourceHelper
    @Inject lateinit var preferences: Preferences
    @Inject lateinit var dateUtil: DateUtil
    @Inject lateinit var geminiService: GeminiCarbService

    private val disposable = CompositeDisposable()
    private var _binding: DialogLoopExplainBinding? = null
    private val binding get() = _binding!!

    override fun onStart() {
        super.onStart()
        dialog?.window?.setBackgroundDrawableResource(android.R.color.transparent)
        val metrics = resources.displayMetrics
        dialog?.window?.setLayout(metrics.widthPixels - (24 * metrics.density).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        dialog?.window?.requestFeature(Window.FEATURE_NO_TITLE)
        isCancelable = true
        _binding = DialogLoopExplainBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val args = requireArguments()
        val runTime = args.getLong(ARG_RUN_TIME)
        val decision = args.getString(ARG_DECISION).orEmpty()
        val reason = args.getString(ARG_REASON).orEmpty()
        val facts = args.getString(ARG_FACTS).orEmpty()

        binding.runTime.text = dateUtil.timeString(runTime)
        binding.decision.text = decision
        binding.facts.text = facts
        binding.rawReason.text = reason
        binding.closeButton.setOnClickListener { dismiss() }

        val cached = synchronized(cache) { cache[runTime] }
        if (cached != null) showExplanation(cached)
        else explain(runTime, decision, reason, facts)
    }

    override fun onDestroyView() {
        disposable.clear()
        _binding = null
        super.onDestroyView()
    }

    private fun explain(runTime: Long, decision: String, reason: String, facts: String) {
        val apiKey = preferences.get(StringKey.OverviewAiCarbsApiKey).trim()
        if (apiKey.isEmpty()) {
            showError(rh.gs(R.string.ai_carbs_error_no_key))
            return
        }
        val userText = "결정: $decision\n값: $facts\n원문:\n${reason.take(1500)}"
        disposable += geminiService.generateText(apiKey, SYSTEM_PROMPT, userText)
            .subscribeOn(aapsSchedulers.io)
            .observeOn(aapsSchedulers.main)
            .subscribe({ text ->
                           synchronized(cache) {
                               cache[runTime] = text
                               while (cache.size > CACHE_SIZE) cache.remove(cache.keys.first())
                           }
                           showExplanation(text)
                       }, { error ->
                           aapsLogger.error(LTag.UI, "Loop explanation failed", error)
                           showError(errorMessage(error))
                       })
    }

    private fun showExplanation(text: String) {
        _binding ?: return
        binding.loading.visibility = View.GONE
        binding.errorText.visibility = View.GONE
        binding.explanation.visibility = View.VISIBLE
        binding.explanation.text = text.lines().filter { it.isNotBlank() }.joinToString("\n") { it.trim().replaceFirst(Regex("^[-*•]\\s*"), "• ") }
    }

    private fun showError(message: String) {
        _binding ?: return
        binding.loading.visibility = View.GONE
        binding.errorText.visibility = View.VISIBLE
        binding.errorText.text = message
    }

    // same user-facing messages as the AI carbs dialog
    private fun errorMessage(error: Throwable): String = when {
        error is HttpException && error.code() == 429                      -> rh.gs(R.string.ai_carbs_error_rate_limited)
        error is HttpException && error.code() in setOf(401, 403)          -> rh.gs(R.string.ai_carbs_error_auth)
        error is HttpException && error.code() in setOf(500, 502, 503, 504) -> rh.gs(R.string.ai_carbs_error_overloaded)
        error is IOException                                               -> rh.gs(R.string.ai_carbs_error_network)
        else                                                               -> rh.gs(R.string.ai_carbs_error_generic, error.message ?: "")
    }
}
