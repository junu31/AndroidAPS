package app.aaps.ui.ai

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import io.reactivex.rxjava3.core.Flowable
import io.reactivex.rxjava3.core.Single
import okhttp3.OkHttpClient
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.adapter.rxjava3.RxJava3CallAdapterFactory
import retrofit2.converter.gson.GsonConverterFactory
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Personal-fork feature: estimate carbohydrate grams from a natural-language food description
 * via Google Gemini REST API.
 *
 * Result is intended as a *hint* — users must confirm the value before applying it to the bolus wizard.
 */
@Singleton
class GeminiCarbService @Inject constructor() {

    companion object {

        private const val BASE_URL = "https://generativelanguage.googleapis.com/"
        const val DEFAULT_MODEL = "gemini-2.5-flash"

        // Retry policy for transient Gemini errors (503/504/502/500/429 + IOException).
        // Total attempts = MAX_RETRIES + 1 (initial call). Delays: 1s, 4s.
        private const val MAX_RETRIES = 2

        private const val SYSTEM_PROMPT =
            """You are a nutritionist estimating carbohydrate content of foods.
Given a user's natural-language description (and/or an image) of what they are about to eat,
return a strict JSON object with this shape:

{
  "items": [
    { "name": "<food name>", "carbs_g": <number>, "assumption": "<portion assumed>" }
  ],
  "total_carbs_g": <number>,
  "assumptions": ["<global assumption>", ...],
  "confidence": "low" | "medium" | "high"
}

Rules:
- Use grams of net digestible carbohydrates (exclude fiber if obvious).
- If portions are ambiguous, assume a typical adult single serving and list the assumption.
- Be conservative when unsure; prefer slightly lower carbs and mark confidence "low".
- ALL human-readable text MUST be written in Korean (한국어):
  the "name" field, every "assumption" string (per-item and global). No English words for these.
- Keep JSON keys (items, name, carbs_g, assumption, total_carbs_g, assumptions, confidence)
  in English exactly as shown.
- Keep the "confidence" enum value as one of the literal strings: "low", "medium", "high".
- Respond ONLY with the JSON object, no prose."""
    }

    private fun isRetryableError(error: Throwable): Boolean = when (error) {
        is IOException   -> true                                          // network glitches
        is HttpException -> error.code() in setOf(429, 500, 502, 503, 504) // transient server states
        else             -> false
    }

    private val gson: Gson = GsonBuilder().setLenient().create()

    // No HTTP logging interceptor: the request URL carries the API key as a query param,
    // so silent-by-default prevents leaks to logcat.
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    private val api: GeminiApi by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .addCallAdapterFactory(RxJava3CallAdapterFactory.create())
            .build()
            .create(GeminiApi::class.java)
    }

    /**
     * Calls Gemini and returns the parsed [CarbEstimatePayload].
     *
     * At least one of [foodDescription] or [imageBase64] must be non-blank.
     * Errors are propagated via [Single.onError] with a human-readable message.
     */
    fun estimateCarbs(
        apiKey: String,
        foodDescription: String?,
        imageBase64: String? = null,
        imageMimeType: String = "image/jpeg",
        model: String = DEFAULT_MODEL
    ): Single<CarbEstimatePayload> {
        if (apiKey.isBlank()) {
            return Single.error(IllegalStateException("API key is empty"))
        }
        val hasText = !foodDescription.isNullOrBlank()
        val hasImage = !imageBase64.isNullOrBlank()
        if (!hasText && !hasImage) {
            return Single.error(IllegalArgumentException("Provide a food description, an image, or both"))
        }

        val userParts = buildList {
            if (hasImage) add(GeminiPart(inlineData = GeminiInlineData(mimeType = imageMimeType, data = imageBase64!!)))
            val userText = if (hasText) "Food description:\n${foodDescription!!.trim()}"
            else "Estimate carbs for the food shown in the image."
            add(GeminiPart(text = userText))
        }

        val request = GeminiRequest(
            contents = listOf(
                GeminiContent(parts = listOf(GeminiPart(text = SYSTEM_PROMPT)), role = "user"),
                GeminiContent(parts = userParts, role = "user")
            ),
            generationConfig = GeminiGenerationConfig()
        )

        return api.generateContent(model, apiKey, request)
            .map { response ->
                response.error?.let { err ->
                    throw RuntimeException("Gemini error: ${err.message ?: err.status ?: "unknown"}")
                }
                val text = response.candidates
                    ?.firstOrNull()
                    ?.content
                    ?.parts
                    ?.firstOrNull()
                    ?.text
                    ?.trim()
                    ?: throw RuntimeException("Empty response from Gemini")

                parseCarbJson(text)
            }
            .retryWhen { errors ->
                // Pair each error with an attempt index 1..MAX_RETRIES.
                // If error is non-retryable, fail immediately.
                // If we run out of attempts, zip completes -> original error propagates.
                errors.zipWith(Flowable.range(1, MAX_RETRIES)) { error, attempt ->
                    if (!isRetryableError(error)) throw error
                    attempt
                }.flatMap { attempt ->
                    val delaySec = (attempt.toLong() * attempt.toLong()) // 1s, 4s
                    Flowable.timer(delaySec, TimeUnit.SECONDS)
                }
            }
    }

    private fun parseCarbJson(raw: String): CarbEstimatePayload {
        // Strip markdown code fence if the model added one despite the config.
        val cleaned = raw
            .removePrefix("```json").removePrefix("```")
            .removeSuffix("```")
            .trim()
        return try {
            gson.fromJson(cleaned, CarbEstimatePayload::class.java)
                ?: throw RuntimeException("Null JSON payload")
        } catch (e: Exception) {
            throw RuntimeException("Failed to parse AI response as JSON: ${e.message}\nRaw: ${cleaned.take(200)}")
        }
    }
}
