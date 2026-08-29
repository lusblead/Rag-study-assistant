package com.rag.backend.agent.prompt;

import com.rag.backend.agent.model.RagPromptContext;
import com.rag.backend.agent.retrieval.RetrievedChunk;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RagPromptTemplateCitationTest {

    @Test
    void promptUsesRequestLocalSourceIdsWithoutInternalIds() {
        RetrievedChunk chunk = new RetrievedChunk(
                123L, 456L, "规则.md", "退货规则",
                "本店支持退货。", 2, 0.9);

        String prompt = new RagPromptTemplate().render(
                new RagPromptContext("是否支持退货？", List.of(chunk)));

        assertTrue(prompt.contains("[S1] 资料来源：《规则.md》，第 2 页"));
        assertTrue(prompt.contains("每条原子事实主张后必须紧跟"));
        assertFalse(prompt.contains("123"));
        assertFalse(prompt.contains("456"));
    }
}
