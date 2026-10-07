package app.aaps.ui.ai

import android.content.Context
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Personal-fork: on-device LLM (MediaPipe LLM Inference, e.g. Gemma 3n E2B .task file).
 * The model is loaded when needed (or [preload]ed by an open screen) and released right after the answer,
 * so its memory is only taken while an explanation is being prepared or generated.
 */
@Singleton
class LocalLlm @Inject constructor(
    private val context: Context,
    private val aapsLogger: AAPSLogger
) {

    private val lock = Any()
    private var engine: LlmInference? = null
    private var loadedPath: String? = null
    /** false once the screen that asked for [preload] is gone; a late preload then frees the model again */
    @Volatile private var keep = false

    /** Loads the model in advance (blocking, background thread) so the next [generate] starts at once. */
    fun preload(modelPath: String) {
        keep = true
        synchronized(lock) {
            if (!keep) return
            if (engine == null || loadedPath != modelPath) load(modelPath)
            if (!keep) close()
        }
    }

    /** Frees the model (blocking until a running load or answer is done; call from a background thread). */
    fun release() {
        keep = false
        synchronized(lock) { close() }
    }

    private fun close() {
        if (engine == null) return
        engine?.close()
        engine = null
        loadedPath = null
        aapsLogger.debug(LTag.UI, "Local model released")
    }

    /** Blocking, call from a background thread. */
    fun generate(modelPath: String, prompt: String): String = synchronized(lock) {
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
            // the answer is stored by the caller and not asked again right away: give the memory back now
            keep = false
            close()
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

        /** Gemma chat format */
        fun gemmaPrompt(systemPrompt: String, userText: String) =
            "<start_of_turn>user\n$systemPrompt\n\n$userText<end_of_turn>\n<start_of_turn>model\n"
    }
}
