package app.aaps.ui.ai

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class AiTextEngineCleanTest {

    @Test
    fun `greeting and markdown are removed`() {
        val raw = "안녕하세요! 루프 판단을 설명해 드릴게요.\n**SMB 0.2U**를 넣었어요.\n- 예상 혈당이 목표보다 높아서예요.<end_of_turn>"
        assertThat(AiTextEngineImpl.cleanLocal(raw)).isEqualTo("SMB 0.2U를 넣었어요.\n예상 혈당이 목표보다 높아서예요.")
    }

    @Test
    fun `plain answer stays as it is`() {
        val raw = "기저를 0.45U/h로 올렸어요. 예상 혈당 168이 목표 110보다 높아서예요."
        assertThat(AiTextEngineImpl.cleanLocal(raw)).isEqualTo(raw)
    }

    @Test
    fun `model label from file name`() {
        assertThat(AiTextEngineImpl.modelLabel("/x/llm/gemma-3n-E2B-it-int4.task")).isEqualTo("Gemma 3n E2B")
        assertThat(AiTextEngineImpl.modelLabel("/x/llm/gemma-4-E4B-it.litertlm")).isEqualTo("Gemma 4 E4B")
    }
}
