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
            val text = localLlm.generate(path, LocalLlm.gemmaPrompt(systemPrompt, userText)).trim()
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

        /** "gemma-3n-E2B-it-int4.task" -> "Gemma 3n" */
        fun modelLabel(path: String): String {
            val name = File(path).nameWithoutExtension
            return Regex("""gemma[-_ ]?(\d+n?)""", RegexOption.IGNORE_CASE).find(name)?.let { "Gemma ${it.groupValues[1]}" } ?: name
        }
    }
}
