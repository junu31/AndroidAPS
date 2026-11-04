package app.aaps.plugins.main.general.dashboard.ai

import android.content.Context
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.meal.AiEstimateLog
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.main.R
import app.aaps.plugins.main.general.dashboard.DashColors
import app.aaps.plugins.main.general.dashboard.WizardPrefill
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import java.io.File
import java.io.IOException
import kotlin.math.max
import kotlin.math.roundToInt

/** FPU carb equivalents (Warsaw, halved as the loop community does). Advisory only, never applied to the bolus. */
internal fun aiFpuCarbs(fatG: Double, proteinG: Double): Double = proteinG * 0.20 + fatG * 0.45

/**
 * Personal-fork: describe or photograph the food, Gemini estimates the carbs, the user corrects the number and hands
 * it to the calculator. Only the immediate carbs are handed over; FPU and the split window stay advisory text.
 */
@Inject
class AiCarbs(
    private val context: Context,
    private val rh: ResourceHelper,
    private val preferences: Preferences,
    private val gemini: GeminiCarbService,
    private val aiEstimateLog: AiEstimateLog,
    private val aapsLogger: AAPSLogger
) {

    private var food by mutableStateOf("")
    private var imageUri by mutableStateOf<Uri?>(null)
    private var pendingCamera: Uri? = null
    private var loading by mutableStateOf(false)
    private var error by mutableStateOf<String?>(null)
    private var estimate by mutableStateOf<CarbEstimatePayload?>(null)
    private var estimateId: String? = null
    private var finalCarbs by mutableStateOf("")

    @Composable
    fun Content(onClose: () -> Unit, onApply: (WizardPrefill) -> Unit) {
        val scope = rememberCoroutineScope()
        val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) imageUri = uri }
        val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
            if (ok) imageUri = pendingCamera
            pendingCamera = null
        }
        Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
            Column(
                Modifier
                    .padding(12.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(DashColors.Bg)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
            ) {
                Text("✦ " + rh.gs(R.string.ai_carbs_title), color = DashColors.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(rh.gs(R.string.ai_carbs_warning), color = DashColors.High, fontSize = 11.5.sp, modifier = Modifier.padding(top = 6.dp))
                Label(rh.gs(R.string.ai_carbs_food_label))
                BasicTextField(
                    food, { food = it }, textStyle = TextStyle(color = DashColors.Text, fontSize = 14.sp), cursorBrush = SolidColor(DashColors.Accent),
                    decorationBox = { inner ->
                        Box {
                            if (food.isEmpty()) Text(rh.gs(R.string.ai_carbs_food_hint), color = DashColors.Dim, fontSize = 13.sp)
                            inner()
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(72.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(DashColors.Card)
                        .border(1.dp, DashColors.Line, RoundedCornerShape(10.dp))
                        .padding(10.dp)
                )
                Label(rh.gs(R.string.ai_carbs_image_label))
                imageUri?.let { uri -> Preview(uri) } ?: Text(rh.gs(R.string.ai_carbs_image_placeholder), color = DashColors.Dim, fontSize = 12.sp)
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Action(rh.gs(R.string.ai_carbs_image_gallery), Modifier.weight(1f)) {
                        gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }
                    Action(rh.gs(R.string.ai_carbs_image_camera), Modifier.weight(1f)) {
                        try {
                            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(context.cacheDir, "ai_carb_capture-${System.currentTimeMillis()}.jpg"))
                            pendingCamera = uri
                            camera.launch(uri)
                        } catch (e: IllegalArgumentException) {
                            aapsLogger.error(LTag.UI, "FileProvider", e)
                            error = rh.gs(R.string.ai_carbs_image_load_failed, e.message ?: "FileProvider")
                        }
                    }
                    if (imageUri != null) Action(rh.gs(R.string.ai_carbs_image_clear), Modifier.weight(1f)) { imageUri = null }
                }
                Action(rh.gs(R.string.ai_carbs_estimate_button), Modifier.padding(top = 12.dp).fillMaxWidth(), enabled = !loading, strong = true) { scope.launch { run() } }
                if (loading) Text("…", color = DashColors.Sub, modifier = Modifier.padding(top = 8.dp))
                error?.let { Text(it, color = DashColors.Low, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp)) }
                estimate?.let { Result(it) }
                Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Action(rh.gs(android.R.string.cancel), Modifier.weight(1f), onClick = onClose)
                    Action(rh.gs(R.string.ai_carbs_to_dashboard_calculator), Modifier.weight(1.4f), enabled = estimate != null, strong = true) { handOver(onApply) }
                }
            }
        }
    }

    private suspend fun run() {
        val key = preferences.get(StringKey.OverviewAiCarbsApiKey).trim()
        val text = food.trim()
        val uri = imageUri
        error = when {
            key.isEmpty()                 -> rh.gs(R.string.ai_carbs_error_no_key)
            text.isEmpty() && uri == null -> rh.gs(R.string.ai_carbs_error_empty_food)
            else                          -> null
        }
        if (error != null) return
        loading = true
        try {
            val image = uri?.let { withContext(Dispatchers.IO) { ImageProcessor.encodeForGemini(context, it) } }
            val p = gemini.estimateCarbs(key, text.ifEmpty { null }, image?.base64, image?.mimeType ?: "image/jpeg")
            estimate = p
            finalCarbs = max(0, p.totalCarbsG.roundToInt()).toString()
            estimateId = aiEstimateLog.addAiEstimate(
                name = p.items.map { it.name.trim() }.filter { it.isNotEmpty() }.joinToString(" + "),
                description = text.ifEmpty { null }, hadImage = uri != null, items = p.items.map { it.name to it.carbsG },
                totalCarbs = p.totalCarbsG, fat = p.fatG, protein = p.proteinG, confidence = p.confidence, note = p.inputStrategy
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            aapsLogger.error(LTag.UI, "AI carb estimation failed", e)
            error = errorText(e)
        }
        loading = false
    }

    private fun errorText(e: Throwable): String = when {
        e is HttpException && e.code() in 500..504      -> rh.gs(R.string.ai_carbs_error_overloaded)
        e is HttpException && e.code() == 429           -> rh.gs(R.string.ai_carbs_error_rate_limited)
        e is HttpException && e.code() in setOf(401, 403) -> rh.gs(R.string.ai_carbs_error_auth)
        e is IOException                                -> rh.gs(R.string.ai_carbs_error_network)
        else                                            -> rh.gs(R.string.ai_carbs_error_generic, e.message ?: e.javaClass.simpleName)
    }

    private fun handOver(onApply: (WizardPrefill) -> Unit) {
        val value = finalCarbs.toIntOrNull()?.coerceAtLeast(0) ?: 0
        if (value <= 0) {
            error = rh.gs(R.string.ai_carbs_error_invalid_final)
            return
        }
        estimateId?.let { aiEstimateLog.setApplied(it, value) }
        val items = estimate?.items.orEmpty()
        onApply(
            WizardPrefill(
                carbs = value, name = items.map { it.name.trim() }.filter { it.isNotEmpty() }.joinToString(" + "),
                foods = items.joinToString(" · ") { "${it.name} ${g(it.carbsG)}g" }, fat = estimate?.fatG, protein = estimate?.proteinG
            )
        )
    }

    private fun g(v: Double) = if (v % 1.0 == 0.0) v.toInt().toString() else "%.1f".format(v)

    private fun confidence(raw: String) = when (raw.trim().lowercase()) {
        "low"    -> rh.gs(R.string.ai_carbs_confidence_low)
        "medium" -> rh.gs(R.string.ai_carbs_confidence_medium)
        "high"   -> rh.gs(R.string.ai_carbs_confidence_high)
        else     -> raw
    }

    @Composable
    private fun Result(p: CarbEstimatePayload) {
        Label(rh.gs(R.string.ai_carbs_result_label))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(g(p.totalCarbsG) + " g", color = DashColors.Text, fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            p.confidence?.takeIf { it.isNotBlank() }?.let { c ->
                val color = when (c.trim().lowercase()) {
                    "low"    -> DashColors.Low
                    "medium" -> DashColors.High
                    else     -> DashColors.InRange
                }
                Text(
                    rh.gs(R.string.ai_carbs_confidence_prefix, confidence(c)), color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(color.copy(alpha = 0.12f))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }
        }
        if (p.items.isEmpty()) Text(rh.gs(R.string.ai_carbs_no_breakdown), color = DashColors.Sub, fontSize = 13.sp)
        p.items.forEach { item ->
            Row(Modifier.padding(vertical = 5.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(item.name, color = DashColors.Text, fontSize = 13.sp)
                    item.assumption?.takeIf { it.isNotBlank() }?.let { Text(it, color = DashColors.Sub, fontSize = 11.sp) }
                }
                Text(rh.gs(R.string.ai_carbs_item_grams, g(item.carbsG)), color = DashColors.Sub, fontSize = 13.sp, modifier = Modifier.padding(start = 8.dp))
            }
        }
        val low = p.confidence?.trim()?.lowercase() == "low"
        val notes = buildList {
            if (low) add(rh.gs(R.string.ai_carbs_low_confidence_warning))
            addAll(p.assumptions)
        }
        if (notes.isNotEmpty()) Text(notes.joinToString("\n") { "– $it" }, color = if (low) DashColors.Low else DashColors.Sub, fontSize = 11.5.sp, modifier = Modifier.padding(top = 4.dp))
        val fat = p.fatG ?: 0.0
        val protein = p.proteinG ?: 0.0
        val hasFpu = fat > 0.0 || protein > 0.0
        if (hasFpu) {
            Label(rh.gs(R.string.ai_carbs_fpu_label))
            Text(rh.gs(R.string.ai_carbs_fpu_format, g(fat), g(protein), g(aiFpuCarbs(fat, protein))), color = DashColors.Text, fontSize = 12.5.sp)
        }
        p.inputStrategy?.trim()?.takeIf { it.isNotEmpty() }?.let {
            Label(rh.gs(R.string.ai_carbs_strategy_label))
            Text(it, color = DashColors.Text, fontSize = 12.5.sp)
        }
        val hours = (p.durationH ?: 0).coerceIn(0, 8)
        if (hours > 0) Text(rh.gs(R.string.ai_carbs_duration_format, hours), color = DashColors.Sub, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
        if (hasFpu || hours > 0)
            Text(
                rh.gs(R.string.ai_carbs_split_advisory, max(0, p.totalCarbsG.roundToInt()), aiFpuCarbs(fat, protein).roundToInt(), if (hours > 0) hours else 4),
                color = DashColors.High, fontSize = 11.5.sp, modifier = Modifier.padding(top = 4.dp)
            )
        Label(rh.gs(R.string.ai_carbs_final_label))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Action("−", Modifier.weight(1f)) { finalCarbs = max(0, (finalCarbs.toIntOrNull() ?: 0) - 1).toString() }
            BasicTextField(
                finalCarbs, { v -> finalCarbs = v.filter { it.isDigit() } }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                textStyle = TextStyle(color = DashColors.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center),
                cursorBrush = SolidColor(DashColors.Accent), modifier = Modifier.weight(1.4f)
            )
            Action("+", Modifier.weight(1f)) { finalCarbs = ((finalCarbs.toIntOrNull() ?: 0) + 1).toString() }
        }
    }

    @Composable
    private fun Preview(uri: Uri) {
        val bitmap by produceState<ImageBitmap?>(null, uri) {
            value = try {
                withContext(Dispatchers.IO) { ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)).asImageBitmap() }
            } catch (e: Exception) {
                aapsLogger.error(LTag.UI, "Failed to display image preview", e)
                error = rh.gs(R.string.ai_carbs_image_load_failed, e.message ?: e.javaClass.simpleName)
                null
            }
        }
        bitmap?.let {
            Image(
                it, rh.gs(R.string.ai_carbs_image_preview_description), contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(150.dp)
                    .clip(RoundedCornerShape(10.dp))
            )
        }
    }

    @Composable
    private fun Label(text: String) {
        Text(text, color = DashColors.Sub, fontSize = 11.5.sp, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
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
