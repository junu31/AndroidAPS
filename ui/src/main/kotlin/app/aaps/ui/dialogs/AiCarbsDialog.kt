package app.aaps.ui.dialogs

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import androidx.appcompat.app.AlertDialog
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
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
import app.aaps.ui.ai.AiCarbsHistoryEntry
import app.aaps.ui.ai.AiCarbsHistoryStore
import app.aaps.ui.ai.CarbEstimatePayload
import app.aaps.ui.ai.GeminiCarbService
import app.aaps.ui.ai.ImageProcessor
import app.aaps.ui.databinding.DialogAiCarbsBinding
import dagger.android.support.DaggerDialogFragment
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.plusAssign
import retrofit2.HttpException
import java.io.File
import java.io.IOException
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
        const val RESULT_DURATION_H = "duration_h"

        private const val STATE_FOOD = "state_food"
        private const val STATE_FINAL_CARBS = "state_final_carbs"
        private const val STATE_IMAGE_URI = "state_image_uri"
        private const val STATE_PENDING_CAMERA_URI = "state_pending_camera_uri"
        private const val CAMERA_FILE_PREFIX = "ai_carb_capture"

        // FPU (Fat-Protein Unit) carb-equivalent coefficients.
        //
        // Derivation chain (every step traceable to a public source):
        //   1. Pankowska 2009 (Warsaw method, peer-reviewed):
        //        1 FPU = 100 kcal from fat+protein ≈ 10 g of carbs (delayed absorption)
        //   2. Per-gram Atwater-based equivalent (full Warsaw, no safety margin):
        //        protein:  4 kcal/g × 10/100 = 0.40 g_carb / g_protein
        //        fat:      9 kcal/g × 10/100 = 0.90 g_carb / g_fat
        //   3. × 0.5 global safety adjustment (community consensus):
        //        - iAPS docs: "Override With A Factor Of" default 0.5
        //        - Juicebox warcal: "Bolus Adjustment Factor" default 0.5
        //        - Rationale: Lopez 2018 (Diabetic Medicine) showed that strict
        //          Pankowska dosing increases hypoglycemia rate vs carb-counting;
        //          halving the result is the established loop-community mitigation.
        //   4. Final per-gram coefficients used here:
        //        FPU_PROTEIN_COEFF = 0.40 × 0.5 = 0.20
        //        FPU_FAT_COEFF     = 0.90 × 0.5 = 0.45
        //
        // Result is advisory only — never auto-applied to the bolus/wizard final value.
        private const val FPU_PROTEIN_COEFF = 0.20
        private const val FPU_FAT_COEFF = 0.45
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
    private var selectedImageUri: Uri? = null
    private var pendingCameraUri: Uri? = null

    private val galleryLauncher = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) bindImage(uri)
    }

    private val cameraLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        val captured = pendingCameraUri
        pendingCameraUri = null
        if (success && captured != null) bindImage(captured)
    }

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) launchCameraCapture()
        else ToastUtils.warnToast(context, rh.gs(R.string.ai_carbs_camera_permission_denied))
    }

    override fun onStart() {
        super.onStart()
        // Use ~90% of screen height so the ScrollView root can actually scroll when the
        // result panel (breakdown + assumptions + input_strategy) is tall. With the previous
        // WRAP_CONTENT height, long results were clipped instead of scrollable.
        val screenHeight = resources.displayMetrics.heightPixels
        dialog?.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            (screenHeight * 0.9).toInt()
        )
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
            it.getString(STATE_IMAGE_URI)?.let { uriString -> bindImage(Uri.parse(uriString)) }
            it.getString(STATE_PENDING_CAMERA_URI)?.let { pendingCameraUri = Uri.parse(it) }
        }

        binding.estimateButton.setOnClickListener { runEstimation() }

        binding.minusButton.setOnClickListener { adjustFinal(-1) }
        binding.plusButton.setOnClickListener { adjustFinal(+1) }

        binding.cancelButton.setOnClickListener { dismiss() }
        binding.applyButton.setOnClickListener { applyAndDismiss() }

        binding.imageGalleryButton.setOnClickListener { launchGalleryPicker() }
        binding.imageCameraButton.setOnClickListener { ensureCameraPermissionAndLaunch() }
        binding.imageClearButton.setOnClickListener { clearImage() }

        binding.historyButton.setOnClickListener { showHistory() }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_FOOD, binding.foodInput.text?.toString() ?: "")
        outState.putInt(STATE_FINAL_CARBS, readFinalCarbs())
        selectedImageUri?.let { outState.putString(STATE_IMAGE_URI, it.toString()) }
        pendingCameraUri?.let { outState.putString(STATE_PENDING_CAMERA_URI, it.toString()) }
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
        val imageUri = selectedImageUri
        if (food.isEmpty() && imageUri == null) {
            showError(rh.gs(R.string.ai_carbs_error_empty_food))
            return
        }

        showLoading(true)
        binding.errorText.visibility = View.GONE

        val ctx = requireContext().applicationContext
        val encode: Single<EncodeResult> = Single.fromCallable {
            if (imageUri != null) {
                val encoded = ImageProcessor.encodeForGemini(ctx, imageUri)
                EncodeResult(encoded.base64, encoded.mimeType)
            } else EncodeResult(null, "image/jpeg")
        }

        disposable += encode
            .subscribeOn(aapsSchedulers.io)
            .flatMap { enc -> geminiCarbService.estimateCarbs(apiKey, food.ifEmpty { null }, enc.base64, enc.mimeType) }
            .observeOn(aapsSchedulers.main)
            .subscribe(
                { payload ->
                    showLoading(false)
                    bindResult(payload)
                },
                { error ->
                    showLoading(false)
                    aapsLogger.error(LTag.UI, "AI carb estimation failed", error)
                    showError(mapErrorMessage(error))
                }
            )
    }

    private fun mapErrorMessage(error: Throwable): String = when {
        error is HttpException && error.code() in 500..504 -> rh.gs(R.string.ai_carbs_error_overloaded)
        error is HttpException && error.code() == 429      -> rh.gs(R.string.ai_carbs_error_rate_limited)
        error is HttpException && error.code() in setOf(401, 403) -> rh.gs(R.string.ai_carbs_error_auth)
        error is IOException                               -> rh.gs(R.string.ai_carbs_error_network)
        else                                               ->
            rh.gs(R.string.ai_carbs_error_generic, error.message ?: error.javaClass.simpleName)
    }

    private data class EncodeResult(val base64: String?, val mimeType: String)

    private fun launchGalleryPicker() {
        galleryLauncher.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        )
    }

    private fun ensureCameraPermissionAndLaunch() {
        val ctx = context ?: return
        val granted = ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (granted) launchCameraCapture()
        else cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun launchCameraCapture() {
        val ctx = context ?: return
        val cacheFile = File(ctx.cacheDir, "$CAMERA_FILE_PREFIX-${System.currentTimeMillis()}.jpg")
        val authority = "${ctx.packageName}.fileprovider"
        val uri = try {
            FileProvider.getUriForFile(ctx, authority, cacheFile)
        } catch (e: IllegalArgumentException) {
            aapsLogger.error(LTag.UI, "FileProvider misconfigured for $authority", e)
            showError(rh.gs(R.string.ai_carbs_image_load_failed, e.message ?: "FileProvider"))
            return
        }
        pendingCameraUri = uri
        cameraLauncher.launch(uri)
    }

    private fun bindImage(uri: Uri) {
        try {
            binding.imagePreview.setImageURI(uri)
        } catch (e: Exception) {
            aapsLogger.error(LTag.UI, "Failed to display image preview", e)
            showError(rh.gs(R.string.ai_carbs_image_load_failed, e.message ?: e.javaClass.simpleName))
            return
        }
        selectedImageUri = uri
        binding.imagePreview.visibility = View.VISIBLE
        binding.imagePlaceholder.visibility = View.GONE
        binding.imageClearButton.visibility = View.VISIBLE
    }

    private fun clearImage() {
        selectedImageUri = null
        binding.imagePreview.setImageDrawable(null)
        binding.imagePreview.visibility = View.GONE
        binding.imagePlaceholder.visibility = View.VISIBLE
        binding.imageClearButton.visibility = View.GONE
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

        val isLowConfidence = payload.confidence?.trim()?.lowercase() == "low"
        val extraAssumptions = buildList {
            if (isLowConfidence) add(rh.gs(R.string.ai_carbs_low_confidence_warning))
            if (!payload.confidence.isNullOrBlank()) add(rh.gs(R.string.ai_carbs_confidence_prefix, localizedConfidence(payload.confidence)))
            addAll(payload.assumptions)
        }
        binding.assumptionsText.text = if (extraAssumptions.isEmpty()) ""
        else extraAssumptions.joinToString("\n") { "– $it" }
        binding.assumptionsText.visibility = if (extraAssumptions.isEmpty()) View.GONE else View.VISIBLE
        val colorAttr = if (isLowConfidence) app.aaps.core.ui.R.attr.urgentColor
        else app.aaps.core.ui.R.attr.defaultTextColor
        binding.assumptionsText.setTextColor(rh.gac(requireContext(), colorAttr))

        // FPU (Fat-Protein Unit) carb-equivalent — surfaced as advisory, NOT auto-applied.
        // Coefficients per personal-fork policy: protein × 0.5 + fat × 0.1 grams of delayed carbs.
        // Section stays GONE unless Gemini extracted at least one of fat_g / protein_g.
        val fatG = payload.fatG ?: 0.0
        val proteinG = payload.proteinG ?: 0.0
        val hasFpuData = (payload.fatG != null && fatG > 0.0) || (payload.proteinG != null && proteinG > 0.0)
        if (hasFpuData) {
            val fpuCarbG = proteinG * FPU_PROTEIN_COEFF + fatG * FPU_FAT_COEFF
            binding.fpuLabel.visibility = View.VISIBLE
            binding.fpuText.visibility = View.VISIBLE
            binding.fpuText.text = rh.gs(R.string.ai_carbs_fpu_format, formatG(fatG), formatG(proteinG), formatG(fpuCarbG))
        } else {
            binding.fpuLabel.visibility = View.GONE
            binding.fpuText.visibility = View.GONE
        }

        val strategy = payload.inputStrategy?.trim().orEmpty()
        val hasStrategy = strategy.isNotEmpty()
        binding.strategyLabel.visibility = if (hasStrategy) View.VISIBLE else View.GONE
        binding.strategyText.visibility = if (hasStrategy) View.VISIBLE else View.GONE
        if (hasStrategy) binding.strategyText.text = strategy

        // Recommended eCarbs split-window duration in hours — populated by Gemini per AAPS
        // eCarbs guidance ("Pizza: 4-6h" etc.). Shown as advisory text only; the apply
        // button enters only the immediate carbs (S1 policy), so the user has to enter
        // the eCarbs second stage manually via the Carbs menu.
        val durationH = (payload.durationH ?: 0).coerceIn(0, 8)
        if (durationH > 0) {
            binding.durationText.visibility = View.VISIBLE
            binding.durationText.text = rh.gs(R.string.ai_carbs_duration_format, durationH)
        } else {
            binding.durationText.visibility = View.GONE
        }

        // S1 advisory note: when FPU or duration is present, tell the user that apply
        // only inputs the immediate carbs and the delayed portion has to be entered
        // manually via the Carbs menu.
        val showSplitAdvisory = hasFpuData || durationH > 0
        if (showSplitAdvisory) {
            val immediateG = max(0, payload.totalCarbsG.roundToInt())
            val delayedG = (proteinG * FPU_PROTEIN_COEFF + fatG * FPU_FAT_COEFF).roundToInt()
            binding.splitAdvisoryText.visibility = View.VISIBLE
            binding.splitAdvisoryText.text = rh.gs(
                R.string.ai_carbs_split_advisory,
                immediateG,
                delayedG,
                if (durationH > 0) durationH else 4
            )
        } else {
            binding.splitAdvisoryText.visibility = View.GONE
        }

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
        // S1 policy: only the immediate carbs portion is auto-applied. Any FPU delayed
        // equivalent and the recommended split window (duration_h) are surfaced in the
        // dialog as advisory text, but the user has to enter the eCarbs second-stage
        // manually via the Carbs menu — same approach as the AAPS Pizza guidance:
        //   step 1: (partial) immediate bolus / immediate carbs   ← this apply
        //   step 2: remaining carbs as eCarbs with duration       ← user does manually
        appendHistoryFromCurrentEstimate(appliedCarbsG = value)
        setFragmentResult(
            REQUEST_KEY,
            bundleOf(RESULT_CARBS_G to value, RESULT_DURATION_H to 0)
        )
        dismiss()
    }

    private fun formatG(value: Double): String =
        if (value % 1.0 == 0.0) value.toInt().toString()
        else "%.1f".format(value)

    private fun showHistory() {
        val ctx = context ?: return
        val entries = AiCarbsHistoryStore.read(ctx)
        val message = if (entries.isEmpty()) {
            rh.gs(R.string.ai_carbs_history_empty)
        } else {
            entries.joinToString("\n\n────────\n\n") { formatHistoryEntry(it) }
        }
        AlertDialog.Builder(ctx)
            .setTitle(R.string.ai_carbs_history_title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .create()
            .show()
    }

    private fun formatHistoryEntry(entry: AiCarbsHistoryEntry): String {
        val timeStr = DateFormat.format("yy-MM-dd HH:mm", entry.timestampMs).toString()
        val sb = StringBuilder()
        sb.append("[").append(timeStr).append("]")
        entry.foodDescription?.takeIf { it.isNotBlank() }?.let { sb.append(" ").append(it) }
        if (entry.hadImage && entry.foodDescription.isNullOrBlank()) sb.append(" (").append(rh.gs(R.string.ai_carbs_history_image_only)).append(")")
        sb.append("\n").append(rh.gs(R.string.ai_carbs_history_carbs_line, formatG(entry.totalCarbsG)))
        val fat = entry.fatG ?: 0.0
        val protein = entry.proteinG ?: 0.0
        if (fat > 0.0 || protein > 0.0) {
            val fpu = entry.fpuCarbG ?: 0.0
            sb.append("\n").append(rh.gs(R.string.ai_carbs_history_fpu_line, formatG(fat), formatG(protein), formatG(fpu)))
        }
        entry.durationH?.takeIf { it > 0 }?.let { sb.append("\n").append(rh.gs(R.string.ai_carbs_duration_format, it)) }
        entry.confidence?.takeIf { it.isNotBlank() }?.let {
            sb.append("\n").append(rh.gs(R.string.ai_carbs_confidence_prefix, localizedConfidence(it)))
        }
        entry.appliedCarbsG?.let {
            sb.append("\n").append(rh.gs(R.string.ai_carbs_history_applied_line, it))
        }
        return sb.toString()
    }

    /**
     * Persist the currently-displayed estimate to local AI-carbs history.
     * No-op when there is no estimate (apply pressed before a successful estimation
     * would normally be blocked, but we guard defensively).
     */
    private fun appendHistoryFromCurrentEstimate(appliedCarbsG: Int?) {
        val payload = lastEstimate ?: return
        val ctx = context?.applicationContext ?: return
        val fatG = payload.fatG ?: 0.0
        val proteinG = payload.proteinG ?: 0.0
        val fpuCarbG = if (fatG > 0.0 || proteinG > 0.0)
            proteinG * FPU_PROTEIN_COEFF + fatG * FPU_FAT_COEFF
        else null
        AiCarbsHistoryStore.append(
            ctx,
            AiCarbsHistoryEntry(
                timestampMs = System.currentTimeMillis(),
                foodDescription = binding.foodInput.text?.toString()?.trim()?.takeIf { it.isNotEmpty() },
                hadImage = selectedImageUri != null,
                totalCarbsG = payload.totalCarbsG,
                fatG = payload.fatG,
                proteinG = payload.proteinG,
                fpuCarbG = fpuCarbG,
                durationH = payload.durationH,
                confidence = payload.confidence,
                inputStrategy = payload.inputStrategy,
                appliedCarbsG = appliedCarbsG
            )
        )
    }

    private fun localizedConfidence(raw: String): String = when (raw.trim().lowercase()) {
        "low"    -> rh.gs(R.string.ai_carbs_confidence_low)
        "medium" -> rh.gs(R.string.ai_carbs_confidence_medium)
        "high"   -> rh.gs(R.string.ai_carbs_confidence_high)
        else     -> raw
    }
}
