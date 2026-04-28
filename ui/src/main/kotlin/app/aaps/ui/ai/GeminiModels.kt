package app.aaps.ui.ai

import com.google.gson.annotations.SerializedName

/** Minimal DTOs for Google Generative Language API (Gemini) generateContent endpoint. */

data class GeminiRequest(
    val contents: List<GeminiContent>,
    @SerializedName("generationConfig") val generationConfig: GeminiGenerationConfig? = null
)

data class GeminiContent(
    val parts: List<GeminiPart>,
    val role: String = "user"
)

data class GeminiPart(
    val text: String? = null,
    @SerializedName("inline_data") val inlineData: GeminiInlineData? = null
)

data class GeminiInlineData(
    @SerializedName("mime_type") val mimeType: String,
    val data: String  // base64-encoded payload
)

data class GeminiGenerationConfig(
    @SerializedName("responseMimeType") val responseMimeType: String = "application/json",
    val temperature: Double = 0.2
)

data class GeminiResponse(
    val candidates: List<GeminiCandidate>? = null,
    val error: GeminiError? = null
)

data class GeminiCandidate(
    val content: GeminiContent? = null,
    @SerializedName("finishReason") val finishReason: String? = null
)

data class GeminiError(
    val code: Int? = null,
    val message: String? = null,
    val status: String? = null
)

/** Parsed payload that the AI should return as JSON (in the `text` part). */
data class CarbEstimatePayload(
    val items: List<CarbItem> = emptyList(),
    @SerializedName("total_carbs_g") val totalCarbsG: Double = 0.0,
    val assumptions: List<String> = emptyList(),
    @SerializedName("confidence") val confidence: String? = null
)

data class CarbItem(
    val name: String = "",
    @SerializedName("carbs_g") val carbsG: Double = 0.0,
    val assumption: String? = null
)
