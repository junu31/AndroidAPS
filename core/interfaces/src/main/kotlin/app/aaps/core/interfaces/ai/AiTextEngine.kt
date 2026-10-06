package app.aaps.core.interfaces.ai

import io.reactivex.rxjava3.core.Single

/**
 * Personal-fork: text generation for the advisory AI explanations (weekly review, loop decision).
 * Either Gemini (API key) or a local model file on the phone, chosen in the Dashboard settings.
 * The photo carb estimation always uses Gemini and does not go through this.
 */
interface AiTextEngine {

    data class Result(
        val text: String,
        /** e.g. "Gemini" or the local model name */
        val source: String,
        val local: Boolean,
        val millis: Long
    )

    /** true when the local model is selected (no API key needed) */
    val usesLocal: Boolean

    /** Generates on a background thread; errors when neither engine can answer. */
    fun generate(systemPrompt: String, userText: String): Single<Result>
}
