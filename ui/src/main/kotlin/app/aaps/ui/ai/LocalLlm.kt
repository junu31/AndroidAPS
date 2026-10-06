package app.aaps.ui.ai

import android.content.Context
import android.os.Handler
import android.os.Looper
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Personal-fork: on-device LLM (MediaPipe LLM Inference, e.g. Gemma 3n E2B .task file).
 * The model is loaded on first use and released after a short idle time, so the few GB of memory
 * are only taken while explanations are being generated.
 */
@Singleton
class LocalLlm @Inject constructor(
    private val context: Context,
    private val aapsLogger: AAPSLogger
) {

    private val lock = Any()
    private var engine: LlmInference? = null
    private var loadedPath: String? = null
    private val handler = Handler(Looper.getMainLooper())
    private val release = Runnable {
        synchronized(lock) {
            engine?.close()
            engine = null
            loadedPath = null
            aapsLogger.debug(LTag.UI, "Local LLM released")
        }
    }

    /** Blocking, call from a background thread. */
    fun generate(modelPath: String, prompt: String): String = synchronized(lock) {
        handler.removeCallbacks(release)
        try {
            val llm = engine?.takeIf { loadedPath == modelPath } ?: load(modelPath)
            val session = LlmInferenceSession.createFromOptions(
                llm,
                LlmInferenceSession.LlmInferenceSessionOptions.builder()
                    .setTemperature(0.3f)
                    .setTopK(40)
                    .build()
            )
            try {
                session.addQueryChunk(prompt)
                session.generateResponse()
            } finally {
                session.close()
            }
        } finally {
            handler.postDelayed(release, IDLE_RELEASE_MS)
        }
    }

    private fun load(modelPath: String): LlmInference {
        engine?.close()
        engine = null
        require(File(modelPath).canRead()) { "Model file not found: $modelPath" }
        fun create(backend: LlmInference.Backend) = LlmInference.createFromOptions(
            context,
            LlmInference.LlmInferenceOptions.builder()
                .setModelPath(modelPath)
                .setMaxTokens(MAX_TOKENS)
                .setPreferredBackend(backend)
                .build()
        )
        // GPU is much faster on recent phones; fall back to CPU when the model or driver does not support it
        val llm = try {
            create(LlmInference.Backend.GPU)
        } catch (e: Exception) {
            aapsLogger.warn(LTag.UI, "Local LLM GPU init failed, using CPU: ${e.message}")
            create(LlmInference.Backend.CPU)
        }
        engine = llm
        loadedPath = modelPath
        return llm
    }

    companion object {

        /** prompt + answer */
        private const val MAX_TOKENS = 2048
        private const val IDLE_RELEASE_MS = 2 * 60 * 1000L

        /** Gemma chat format */
        fun gemmaPrompt(systemPrompt: String, userText: String) =
            "<start_of_turn>user\n$systemPrompt\n\n$userText<end_of_turn>\n<start_of_turn>model\n"
    }
}
