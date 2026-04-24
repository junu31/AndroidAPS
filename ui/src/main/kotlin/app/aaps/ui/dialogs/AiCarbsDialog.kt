package app.aaps.ui.dialogs

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import androidx.core.os.bundleOf
import androidx.fragment.app.setFragmentResult
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.AapsSchedulers
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.ui.toast.ToastUtils
import app.aaps.ui.R
import app.aaps.ui.ai.CarbEstimatePayload
import app.aaps.ui.ai.GeminiCarbService
import app.aaps.ui.databinding.DialogAiCarbsBinding
import dagger.android.support.DaggerDialogFragment
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.plusAssign
import javax.inject.Inject
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Personal-fork feature.
 *
 * Dialog that asks the user to describe the food they are about to eat, calls Gemini,
 * shows an estimated carbohydrate total with a breakdown, and lets the user fine-tune the
 * number with ± buttons before confirming. On confirm, the final value is returned to
 * the caller via FragmentResult under key [RESULT_KEY] (bundle field [RESULT_CARBS_G]).
 */
class AiCarbsDialog : DaggerDialogFragment() {

    companion object {

        const val REQUEST_KEY = "AiCarbsDialog.request"
        const val RESULT_CARBS_G = "carbs_g"

        private const val STATE_FOOD = "state_food"
        private const val STATE_FINAL_CARBS = "state_final_carbs"
    }

    @Inject lateinit var aapsLogger: AAPSLogger
    @Inject lateinit var aapsSchedulers: AapsSchedulers
    @Inject lateinit var rh: ResourceHelper
    @Inject lateinit var preferences: Preferences
    @Inject lateinit var geminiCarbService: GeminiCarbService

    private var _binding: DialogAiCarbsBinding? = null
    private val binding get() = _binding!!
    private val disposable = CompositeDisposable()

    private var lastEstimate: CarbEstimatePayload? = null

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        aapsLogger.debug(LTag.UI, "Dialog opened: ${this.javaClass.simpleName}")
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        dialog?.window?.requestFeature(Window.FEATURE_NO_TITLE)
        dialog?.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN)
        isCancelable = true
        dialog?.setCanceledOnTouchOutside(false)
        _binding = DialogAiCarbsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        savedInstanceState?.let {
            binding.foodInput.setText(it.getString(STATE_FOOD, ""))
            val savedFinal = it.getInt(STATE_FINAL_CARBS, -1)
            if (savedFinal >= 0) binding.finalCarbs.setText(savedFinal.toString())
        }

        binding.estimateButton.setOnClickListener { runEstimation() }

        binding.minusButton.setOnClickListener { adjustFinal(-1) }
        binding.plusButton.setOnClickListener { adjustFinal(+1) }

        binding.cancelButton.setOnClickListener { dismiss() }
        binding.applyButton.setOnClickListener { applyAndDismiss() }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_FOOD, binding.foodInput.text?.toString() ?: "")
        outState.putInt(STATE_FINAL_CARBS, readFinalCarbs())
    }

    override fun onDestroyView() {
        disposable.clear()
        _binding = null
        super.onDestroyView()
    }

    private fun runEstimation() {
        val apiKey = preferences.get(StringKey.OverviewAiCarbsApiKey).trim()
        if (apiKey.isEmpty()) {
            showError(rh.gs(R.string.ai_carbs_error_no_key))
            return
        }
        val food = binding.foodInput.text?.toString()?.trim().orEmpty()
        if (food.isEmpty()) {
            showError(rh.gs(R.string.ai_carbs_error_empty_food))
            return
        }

        showLoading(true)
        binding.errorText.visibility = View.GONE

        disposable += geminiCarbService.estimateCarbs(apiKey, food)
            .subscribeOn(aapsSchedulers.io)
            .observeOn(aapsSchedulers.main)
            .subscribe(
                { payload ->
                    showLoading(false)
                    bindResult(payload)
                },
                { error ->
                    showLoading(false)
                    aapsLogger.error(LTag.UI, "AI carb estimation failed", error)
                    showError(rh.gs(R.string.ai_carbs_error_generic, error.message ?: error.javaClass.simpleName))
                }
            )
    }

    private fun showLoading(loading: Boolean) {
        binding.progress.visibility = if (loading) View.VISIBLE else View.GONE
        binding.estimateButton.isEnabled = !loading
    }

    private fun showError(message: String) {
        binding.errorText.text = message
        binding.errorText.visibility = View.VISIBLE
    }

    private fun bindResult(payload: CarbEstimatePayload) {
        lastEstimate = payload
        val breakdown = if (payload.items.isNotEmpty()) {
            payload.items.joinToString("\n") { item ->
                val suffix = item.assumption?.let { " (${it})" } ?: ""
                "• ${item.name}: ${formatG(item.carbsG)} g$suffix"
            }
        } else {
            rh.gs(R.string.ai_carbs_no_breakdown)
        }
        binding.breakdownText.text = breakdown + "\n" + rh.gs(R.string.ai_carbs_total_prefix, formatG(payload.totalCarbsG))

        val extraAssumptions = buildList {
            if (!payload.confidence.isNullOrBlank()) add(rh.gs(R.string.ai_carbs_confidence_prefix, payload.confidence))
            addAll(payload.assumptions)
        }
        binding.assumptionsText.text = if (extraAssumptions.isEmpty()) ""
        else extraAssumptions.joinToString("\n") { "– $it" }
        binding.assumptionsText.visibility = if (extraAssumptions.isEmpty()) View.GONE else View.VISIBLE

        val rounded = max(0, payload.totalCarbsG.roundToInt())
        binding.finalCarbs.setText(rounded.toString())
        binding.resultSection.visibility = View.VISIBLE
        binding.applyButton.isEnabled = true
    }

    private fun adjustFinal(delta: Int) {
        val current = readFinalCarbs()
        val next = max(0, current + delta)
        binding.finalCarbs.setText(next.toString())
    }

    private fun readFinalCarbs(): Int =
        binding.finalCarbs.text?.toString()?.toIntOrNull()?.coerceAtLeast(0) ?: 0

    private fun applyAndDismiss() {
        val value = readFinalCarbs()
        if (value <= 0) {
            ToastUtils.warnToast(context, rh.gs(R.string.ai_carbs_error_invalid_final))
            return
        }
        setFragmentResult(REQUEST_KEY, bundleOf(RESULT_CARBS_G to value))
        dismiss()
    }

    private fun formatG(value: Double): String =
        if (value % 1.0 == 0.0) value.toInt().toString()
        else "%.1f".format(value)
}
