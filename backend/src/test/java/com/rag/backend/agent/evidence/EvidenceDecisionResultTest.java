package com.rag.backend.agent.evidence;

import com.rag.backend.agent.retrieval.RetrievalDiagnostics;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;

class EvidenceDecisionResultTest {

    @Test
    void answerCannotExistWithoutUsableEvidence() {
        assertThrows(IllegalArgumentException.class,
                () -> new EvidenceDecisionResult(
                        AnswerabilityDecision.ANSWER,
                        EvidenceDecisionReason.DIRECT_SUPPORT_OBSERVED,
                        List.of(),
                        null,
                        signals(),
                        "policy-v1"));
    }

    private EvidenceObservedSignals signals() {
        return new EvidenceObservedSignals(
                0, 0, 0, 0, 0.0, false,
                false, false, false, false,
                null, null, null, false,
                RetrievalDiagnostics.EmptyReason.NONE);
    }
}
