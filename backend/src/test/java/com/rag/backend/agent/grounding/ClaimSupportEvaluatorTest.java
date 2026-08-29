package com.rag.backend.agent.grounding;

import com.rag.backend.agent.retrieval.RetrievedChunk;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClaimSupportEvaluatorTest {

    @Test
    void exactEvidenceSupportsTheClaim() {
        ClaimSupportResult result = evaluate(
                "本店支持退货", "本店支持退货。",
                new UncalibratedSemanticClaimJudge());

        assertTrue(result.allSupported());
        assertEquals(ClaimSupportStatus.SUPPORTED,
                result.assessments().getFirst().status());
    }

    @Test
    void realCitationCannotSupportANewPreciseValue() {
        ClaimSupportResult result = evaluate(
                "本店支持30天无理由退货", "本店支持退货。",
                new UncalibratedSemanticClaimJudge());

        assertFalse(result.allSupported());
        assertEquals(ClaimSupportStatus.UNSUPPORTED,
                result.assessments().getFirst().status());
        assertEquals("PRECISE_VALUE_NOT_IN_EVIDENCE",
                result.assessments().getFirst().reasonCode());
    }

    @Test
    void explicitOppositePolarityIsContradicted() {
        ClaimSupportResult result = evaluate(
                "本店不支持退货", "本店支持退货。",
                new UncalibratedSemanticClaimJudge());

        assertEquals(ClaimSupportStatus.CONTRADICTED,
                result.assessments().getFirst().status());
    }

    @Test
    void complexParaphraseIsUncertainWithoutHumanCalibration() {
        ClaimSupportResult result = evaluate(
                "顾客可以把商品退回商店", "本店支持退货。",
                new UncalibratedSemanticClaimJudge());

        assertEquals(ClaimSupportStatus.UNCERTAIN,
                result.assessments().getFirst().status());
    }

    @Test
    void separateEvidenceFragmentsCannotBecomeDirectSupportByConcatenation() {
        CitationCatalog catalog = CitationCatalog.from(List.of(
                new RetrievedChunk(
                        1L, 10L, "规则上.md", "规则上",
                        "本店支持", 1, 0.9),
                new RetrievedChunk(
                        2L, 10L, "规则下.md", "规则下",
                        "退货。", 2, 0.8)));

        ClaimSupportResult result = new ClaimSupportEvaluator(
                new UncalibratedSemanticClaimJudge()).evaluate(
                List.of(new AtomicClaim(
                        1, "本店支持退货", List.of("S1", "S2"))),
                catalog);

        assertEquals(ClaimSupportStatus.UNCERTAIN,
                result.assessments().getFirst().status());
        assertEquals("SEMANTIC_JUDGE_NOT_CALIBRATED",
                result.assessments().getFirst().reasonCode());
    }

    @Test
    void calibratedJudgeCannotOverrideDeterministicFailure() {
        SemanticClaimJudge permissiveJudge = new SemanticClaimJudge() {
            @Override
            public boolean calibrated() {
                return true;
            }

            @Override
            public String calibrationId() {
                return "human-dev-v1";
            }

            @Override
            public SemanticJudgeDecision judge(
                    AtomicClaim claim, List<CitationSource> evidence) {
                return new SemanticJudgeDecision(
                        ClaimSupportStatus.SUPPORTED, "MODEL_SUPPORTED");
            }
        };

        ClaimSupportResult result = evaluate(
                "本店支持30天退货", "本店支持退货。", permissiveJudge);

        assertEquals(ClaimSupportStatus.UNSUPPORTED,
                result.assessments().getFirst().status());
    }

    private ClaimSupportResult evaluate(
            String claim,
            String evidence,
            SemanticClaimJudge judge) {
        CitationCatalog catalog = CitationCatalog.from(List.of(
                new RetrievedChunk(
                        1L, 10L, "规则.md", "退货规则",
                        evidence, 1, 0.9)));
        return new ClaimSupportEvaluator(judge).evaluate(
                List.of(new AtomicClaim(1, claim, List.of("S1"))), catalog);
    }
}
