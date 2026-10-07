package app.aaps.ui.ai

import app.aaps.core.interfaces.ai.AiTextEngine
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.interfaces.Preferences
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.schedulers.Schedulers
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Personal-fork: Gemini or the local model for the advisory explanations, see [AiTextEngine]. */
@Singleton
class AiTextEngineImpl @Inject constructor(
    private val gemini: GeminiCarbService,
    private val localLlm: LocalLlm,
    private val liteRtLm: LiteRtLm,
    private val preferences: Preferences,
    private val aapsLogger: AAPSLogger
) : AiTextEngine {

    override val usesLocal: Boolean get() = preferences.get(StringKey.AiTextEngine) == ENGINE_LOCAL

    override fun generate(systemPrompt: String, userText: String): Single<AiTextEngine.Result> {
        if (!usesLocal) return viaGemini(systemPrompt, userText)
        val local = Single.fromCallable {
            val path = preferences.get(StringKey.AiLocalModelPath)
            check(path.isNotEmpty() && File(path).canRead()) { "No local model file" }
            val start = System.currentTimeMillis()
            // .litertlm (Gemma 4) runs on LiteRT-LM, .task (Gemma 3n) on MediaPipe
            val raw = if (path.endsWith(".litertlm", ignoreCase = true)) liteRtLm.generate(path, systemPrompt, userText)
            else localLlm.generate(path, LocalLlm.gemmaPrompt(systemPrompt, userText))
            val text = cleanLocal(raw)
            check(text.isNotEmpty()) { "Empty answer from the local model" }
            AiTextEngine.Result(text, modelLabel(path), local = true, millis = System.currentTimeMillis() - start)
        }.subscribeOn(Schedulers.io())
        if (!preferences.get(BooleanKey.AiLocalFallbackGemini)) return local
        return local.onErrorResumeNext { error ->
            aapsLogger.warn(LTag.UI, "Local model failed, falling back to Gemini: ${error.message}")
            viaGemini(systemPrompt, userText)
        }
    }

    private fun viaGemini(systemPrompt: String, userText: String): Single<AiTextEngine.Result> = Single.defer {
        val start = System.currentTimeMillis()
        gemini.generateText(preferences.get(StringKey.OverviewAiCarbsApiKey).trim(), systemPrompt, userText)
            .map { AiTextEngine.Result(it, "Gemini", local = false, millis = System.currentTimeMillis() - start) }
    }.subscribeOn(Schedulers.io())

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
