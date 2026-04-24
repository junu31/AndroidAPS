package app.aaps.ui.ai

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import io.reactivex.rxjava3.core.Single
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.adapter.rxjava3.RxJava3CallAdapterFactory
import retrofit2.converter.gson.GsonConverterFactory
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

        private const val SYSTEM_PROMPT =
            """You are a nutritionist estimating carbohydrate content of foods.
Given a user's natural-language description of what they are about to eat,
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
- Answer in the same language as the user's input (e.g. Korean in -> Korean out).
- Respond ONLY with the JSON object, no prose."""
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
     * Calls Gemini with [foodDescription] and returns the parsed [CarbEstimatePayload].
     * Errors are propagated via [Single.onError] with a human-readable message.
     */
    fun estimateCarbs(apiKey: String, foodDescription: String, model: String = DEFAULT_MODEL): Single<CarbEstimatePayload> {
        if (apiKey.isBlank()) {
            return Single.error(IllegalStateException("API key is empty"))
        }
        if (foodDescription.isBlank()) {
            return Single.error(IllegalArgumentException("Food description is empty"))
        }

        val userText = "Food description:\n$foodDescription"
        val request = GeminiRequest(
            contents = listOf(
                GeminiContent(parts = listOf(GeminiPart(SYSTEM_PROMPT)), role = "user"),
                GeminiContent(parts = listOf(GeminiPart(userText)), role = "user")
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
