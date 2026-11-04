package app.aaps.plugins.main.general.dashboard.ai

import app.aaps.core.interfaces.di.ApplicationScope
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.interfaces.Preferences
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Personal-fork: text generation for the advisory AI explanations (weekly review, loop decision, meal pattern).
 * Either Gemini (API key) or a local model file on the phone, chosen in the settings.
 * The photo carb estimation always uses Gemini and does not go through this.
 */
@SingleIn(AppScope::class)
@Inject
class AiTextEngine(
    private val gemini: GeminiCarbService,
    private val localLlm: LocalLlm,
    private val liteRtLm: LiteRtLm,
    private val preferences: Preferences,
    private val aapsLogger: AAPSLogger,
    @ApplicationScope private val appScope: CoroutineScope
) {

    data class Result(val text: String, val source: String, val local: Boolean, val millis: Long)

    val usesLocal: Boolean get() = preferences.get(StringKey.AiTextEngine) == ENGINE_LOCAL

    suspend fun generate(systemPrompt: String, userText: String): Result = withContext(Dispatchers.IO) {
        if (!usesLocal) return@withContext viaGemini(systemPrompt, userText)
        try {
            val path = preferences.get(StringKey.AiLocalModelPath)
            check(path.isNotEmpty() && File(path).canRead()) { "No local model file" }
            val start = System.currentTimeMillis()
            // .litertlm (Gemma 4) runs on LiteRT-LM, .task (Gemma 3n) on MediaPipe
            val raw = if (isLiteRt(path)) liteRtLm.generate(path, systemPrompt, userText)
            else localLlm.generate(path, LocalLlm.gemmaPrompt(systemPrompt, userText))
            val text = cleanLocal(raw)
            check(text.isNotEmpty()) { "Empty answer from the local model" }
            Result(text, modelLabel(path), local = true, millis = System.currentTimeMillis() - start)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!preferences.get(BooleanKey.AiLocalFallbackGemini)) throw e
            aapsLogger.warn(LTag.UI, "Local model failed, falling back to Gemini: ${e.message}")
            viaGemini(systemPrompt, userText)
        }
    }

    fun preload() {
        if (!usesLocal) return
        val path = preferences.get(StringKey.AiLocalModelPath)
        if (path.isEmpty() || !File(path).canRead()) return
        appScope.launch(Dispatchers.IO) {
            try {
                if (isLiteRt(path)) liteRtLm.preload(path) else localLlm.preload(path)
            } catch (e: Exception) {
                // generate() loads again and reports the error (or falls back to Gemini)
                aapsLogger.warn(LTag.UI, "Local model preload failed: ${e.message}")
            }
        }
    }

    fun release() {
        appScope.launch(Dispatchers.IO) {
            liteRtLm.release()
            localLlm.release()
        }
    }

    private fun isLiteRt(path: String) = path.endsWith(".litertlm", ignoreCase = true)

    private suspend fun viaGemini(systemPrompt: String, userText: String): Result {
        val start = System.currentTimeMillis()
        val text = gemini.generateText(preferences.get(StringKey.OverviewAiCarbsApiKey).trim(), systemPrompt, userText)
        return Result(text, "Gemini", local = false, millis = System.currentTimeMillis() - start)
    }

    companion object {

        const val ENGINE_GEMINI = "gemini"
        const val ENGINE_LOCAL = "local"

        private val filler = Regex("""^(안녕하세요|안녕|네[,.!]|물론|좋습니다|알겠습니다|설명해 ?드릴게요|다음과 같습니다)[^.!?\n]*[.!?:]?\s*""")

        /** opening sentences like "루프 판단을 설명해 드릴게요." */
        private val intro = Regex("""^[^.!?\n]*(드릴게요|드리겠습니다|살펴볼게요|살펴보겠습니다|다음과 같습니다)[.!?:]?\s*""")

        /** Small local models open with greetings and use markdown; keep the plain answer only. */
        fun cleanLocal(raw: String): String {
            var t = raw.replace("<end_of_turn>", "").replace(Regex("""\*\*|__|#+\s"""), "").trim()
            repeat(3) { t = t.replace(filler, "").replace(intro, "").trim() }
            return t.lines().map { it.trim().removePrefix("- ").removePrefix("* ") }.filter { it.isNotEmpty() }.joinToString("\n")
        }

        /** "gemma-3n-E2B-it-int4.task" -> "Gemma 3n E2B", "gemma-4-E4B-it.litertlm" -> "Gemma 4 E4B" */
        fun modelLabel(path: String): String {
            val name = File(path).nameWithoutExtension
            return Regex("""gemma[-_ ]?(\d+n?)(?:[-_ ](e\d+b))?""", RegexOption.IGNORE_CASE).find(name)
                ?.let { m -> listOf("Gemma", m.groupValues[1], m.groupValues[2].uppercase()).filter { it.isNotEmpty() }.joinToString(" ") } ?: name
        }
    }
}
