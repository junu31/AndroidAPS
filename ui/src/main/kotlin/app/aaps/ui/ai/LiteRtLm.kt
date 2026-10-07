package app.aaps.ui.ai

import android.content.Context
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Personal-fork: on-device LLM through LiteRT-LM for `.litertlm` models (e.g. Gemma 4 E2B / E4B).
 * Same lifecycle as [LocalLlm]: loaded when needed or preloaded, released right after the answer.
 */
@Singleton
class LiteRtLm @Inject constructor(
    private val context: Context,
    private val aapsLogger: AAPSLogger
) {

    private val lock = Any()
    private var engine: Engine? = null
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

    /** Blocking, call from a background thread. The chat template comes with the model file. */
    fun generate(modelPath: String, systemPrompt: String, userText: String): String = synchronized(lock) {
        try {
            val llm = engine?.takeIf { loadedPath == modelPath } ?: load(modelPath)
            llm.createConversation(
                ConversationConfig(
                    systemInstruction = Contents.of(systemPrompt),
                    samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = 0.3),
                    maxOutputToken = MAX_OUTPUT_TOKENS
                )
            ).use { conversation ->
                conversation.sendMessage(userText).contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
            }
        } finally {
            // the answer is stored by the caller and not asked again right away: give the memory back now
            keep = false
            close()
        }
    }

    private fun load(modelPath: String): Engine {
        engine?.close()
        engine = null
        require(File(modelPath).canRead()) { "Model file not found: $modelPath" }
        fun create(backend: Backend) = Engine(EngineConfig(modelPath = modelPath, backend = backend, cacheDir = context.cacheDir.path)).apply { initialize() }
        // GPU is several times faster on recent phones; CPU when the GPU path is not available
        val llm = try {
            create(Backend.GPU())
        } catch (e: Exception) {
            aapsLogger.warn(LTag.UI, "LiteRT-LM GPU init failed, using CPU: ${e.message}")
            create(Backend.CPU())
        }
        engine = llm
        loadedPath = modelPath
        return llm
    }

    companion object {

        private const val MAX_OUTPUT_TOKENS = 512
    }
}
