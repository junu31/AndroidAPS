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
import java.util.concurrent.atomic.AtomicInteger
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
  "confidence": "low" | "medium" | "high",
  "input_strategy": "<2~5줄 한국어 권고 텍스트>",
  "fat_g": <number or null>,
  "protein_g": <number or null>,
  "duration_h": <integer 0..8>
}

Rules:
- Use grams of net digestible carbohydrates (exclude fiber if obvious).
- If the input is a nutrition facts label image (영양성분표) and the carbohydrate value is
  missing, unreadable, or zero while calories are present, derive carbs from Atwater factors:
  carbs_g ≈ (kcal − 9 × fat_g − 4 × protein_g) / 4 . If the result is negative, treat as 0.
  Use the serving size shown on the label. Mark such items confidence "low" and explain the
  derivation in "assumption" (예: "영양성분표에서 탄수화물 미기재 → 칼로리 역산").
- If portions are ambiguous, assume a typical adult single serving and list the assumption.
- Be conservative when unsure; prefer slightly lower carbs and mark confidence "low".
- ALL human-readable text MUST be written in Korean (한국어):
  the "name" field, every "assumption" string (per-item and global). No English words for these.
- Keep JSON keys (items, name, carbs_g, assumption, total_carbs_g, assumptions, confidence,
  input_strategy, fat_g, protein_g) in English exactly as shown.
- Populate "fat_g" and "protein_g" with the TOTAL grams of fat and protein for the SAME serving
  basis used for "total_carbs_g" (whole package, single serving, or whatever the user is eating
  — must match). Read the values straight off a nutrition facts label when one is visible
  (지방, 단백질). When only a food photo is supplied, estimate from typical composition.
  Use null when the value is genuinely unknown — do NOT guess zero. Numbers only, no units.
- "duration_h" is the recommended SPLIT WINDOW in hours for AAPS eCarbs (Extended Carbs).
  This goes into the AAPS Carbs dialog's "기간/Duration" field. Map by food type:
    * 0  → simple/fast carbs: white rice, fruit, candy, soft drink, plain bread
    * 2  → mixed light meal: sandwich, fried rice, regular Korean side dishes
    * 4  → high-fat OR high-protein meal: pizza, fried chicken, Korean BBQ, ramen with broth
    * 6  → very-high-fat-AND-protein meal: cheese-stuffed processed meats, fatty cuts,
           creamy pasta, sausage platters, hamburger combos
  Always return an integer (0..8). When uncertain, prefer the lower number (safer — user
  can always extend). Cross-check: if fat_g ≥ 25 OR protein_g ≥ 30, duration_h SHOULD be ≥ 4.
- Keep the "confidence" enum value as one of the literal strings: "low", "medium", "high".
- The "input_strategy" field is a Korean natural-language note (2~5 lines, plain text, no JSON
  inside) advising HOW the user should enter the carbs in their insulin pump app:
  * Consider fat and protein content visible on the label / inferred from the food (지방, 단백질).
  * High-fat or high-protein meals (지방 ≥ 15g 또는 단백질 ≥ 20g) delay carb absorption: recommend
    a SPLIT or EXTENDED entry (예: "즉시 60% + 2시간 후 40%", 또는 "W/Carbs Duration 3~4시간").
  * Simple/low-fat meals (지방 < 10g, 단순당 위주): recommend a SINGLE immediate entry
    (예: "단일 입력 권장").
  * Be conservative — do NOT prescribe specific basal rates (TBR%), SMB toggles, or insulin
    doses. Only describe the carb-entry timing pattern.
  * Prefix the recommendation with one of: "단일 입력 권장", "분할 입력 권장", "연장 입력 권장".
  * If the food is unclear, write a short generic tip and mark the overall confidence "low".
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
                // Use a counter so the final exhausted attempt explicitly emits the error
                // instead of completing the inner Flowable empty (which would surface as
                // NoSuchElementException at the Single layer).
                val attemptNo = AtomicInteger(0)
                errors.flatMap { error ->
                    val attempt = attemptNo.incrementAndGet()
                    if (!isRetryableError(error) || attempt > MAX_RETRIES) {
                        Flowable.error(error)
                    } else {
                        val delaySec = (attempt.toLong() * attempt.toLong()) // 1s, 4s
                        Flowable.timer(delaySec, TimeUnit.SECONDS)
                    }
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
