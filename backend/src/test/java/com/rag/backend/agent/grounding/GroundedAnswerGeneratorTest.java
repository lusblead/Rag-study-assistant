package com.rag.backend.agent.grounding;

import com.rag.backend.agent.retrieval.RetrievedChunk;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GroundedAnswerGeneratorTest {

    @Test
    void acceptedAnswerUsesOneGeneration() {
        AtomicInteger calls = new AtomicInteger();
        GroundedAnswerResult result = enabledGenerator().generate(
                "prompt", catalog(), prompt -> {
                    calls.incrementAndGet();
                    return "本店支持退货。[S1]";
                });

        assertEquals(GroundingExecutionStatus.ACCEPTED,
                result.diagnostics().status());
        assertEquals(1, calls.get());
        assertEquals("本店支持退货。[S1]", result.answer());
    }

    @Test
    void unsupportedFirstAnswerIsRepairedOnceAndFullyRevalidated() {
        AtomicInteger calls = new AtomicInteger();
        Deque<String> answers = new ArrayDeque<>(List.of(
                "本店支持30天无理由退货。[S1]",
                "本店支持退货。[S1]"));

        GroundedAnswerResult result = enabledGenerator().generate(
                "prompt", catalog(), prompt -> {
                    calls.incrementAndGet();
                    return answers.removeFirst();
                });

        assertEquals(GroundingExecutionStatus.REPAIRED,
                result.diagnostics().status());
        assertEquals(2, calls.get());
        assertEquals("本店支持退货。[S1]", result.answer());
    }

    @Test
    void secondFailureDiscardsBothCandidatesAndReturnsSafeText() {
        AtomicInteger calls = new AtomicInteger();
        Deque<String> answers = new ArrayDeque<>(List.of(
                "本店支持30天无理由退货。[S1]",
                "本店支持90天退货。[S1]"));

        GroundedAnswerResult result = enabledGenerator().generate(
                "prompt", catalog(), prompt -> {
                    calls.incrementAndGet();
                    return answers.removeFirst();
                });

        assertEquals(GroundingExecutionStatus.REJECTED,
                result.diagnostics().status());
        assertEquals(2, calls.get());
        assertFalse(result.answer().contains("30天"));
        assertFalse(result.answer().contains("90天"));
        assertTrue(result.answer().contains("没有提供足够依据"));
    }

    @Test
    void disabledModePreservesSingleGenerationAndMarksTheGap() {
        AtomicInteger calls = new AtomicInteger();
        GroundedAnswerResult result = GroundedAnswerGenerator.disabled()
                .generate("prompt", catalog(), prompt -> {
                    calls.incrementAndGet();
                    return "未校验兼容回答";
                });

        assertEquals(GroundingExecutionStatus.DISABLED,
                result.diagnostics().status());
        assertEquals(1, calls.get());
        assertEquals("未校验兼容回答", result.answer());
    }

    private GroundedAnswerGenerator enabledGenerator() {
        GroundingProperties properties = new GroundingProperties();
        properties.setEnabled(true);
        return new GroundedAnswerGenerator(
                properties,
                new GroundingValidator(
                        new CitationIntegrityValidator(),
                        new ClaimSupportEvaluator(
                                new UncalibratedSemanticClaimJudge())),
                new GroundingRepairPromptTemplate(),
                new GroundingFailureRenderer());
    }

    private CitationCatalog catalog() {
        return CitationCatalog.from(List.of(new RetrievedChunk(
                1L, 10L, "规则.md", "退货规则",
                "本店支持退货。", 1, 0.9)));
    }
}
