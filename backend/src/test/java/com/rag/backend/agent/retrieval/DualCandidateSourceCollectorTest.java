package com.rag.backend.agent.retrieval;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DualCandidateSourceCollectorTest {

    @Test
    void oneSourceFailureFailsOpenWithoutFusion() {
        CandidateSource dense = failing(
                CandidateSourceType.DENSE,
                CandidateSourceFailureType.TIMEOUT);
        CandidateSource lexical = returning(
                CandidateSourceType.LEXICAL,
                CandidateBatch.empty(CandidateSourceType.LEXICAL));

        CandidateCollectionResult result = new DualCandidateSourceCollector(
                dense, lexical).collect(scope(), "query", 20);

        assertTrue(result.degraded());
        assertEquals(CandidateSourceType.DENSE,
                result.failedSource().orElseThrow());
        assertEquals(CandidateSourceFailureType.TIMEOUT,
                result.failureType().orElseThrow());
        assertTrue(result.batch(CandidateSourceType.DENSE).isEmpty());
        assertTrue(result.batch(CandidateSourceType.LEXICAL).isPresent());
        assertEquals(0, result.candidateCount().get(CandidateSourceType.DENSE));
        assertEquals(0, result.candidateCount().get(CandidateSourceType.LEXICAL));
        assertEquals(Set.of(CandidateSourceType.DENSE, CandidateSourceType.LEXICAL),
                result.sourceLatencyNanos().keySet());
    }

    @Test
    void bothSourceFailuresFailClosed() {
        DualCandidateSourceCollector collector = new DualCandidateSourceCollector(
                failing(CandidateSourceType.DENSE,
                        CandidateSourceFailureType.SOURCE_ERROR),
                failing(CandidateSourceType.LEXICAL,
                        CandidateSourceFailureType.INVALID_BATCH));

        CandidateCollectionException failure = assertThrows(
                CandidateCollectionException.class,
                () -> collector.collect(scope(), "query", 20));

        assertEquals(2, failure.diagnostics().size());
        assertTrue(failure.diagnostics().stream()
                .noneMatch(CandidateSourceDiagnostic::succeeded));
    }

    @Test
    void emptySuccessfulBatchesAreNotDegraded() {
        CandidateCollectionResult result = new DualCandidateSourceCollector(
                returning(CandidateSourceType.DENSE,
                        CandidateBatch.empty(CandidateSourceType.DENSE)),
                returning(CandidateSourceType.LEXICAL,
                        CandidateBatch.empty(CandidateSourceType.LEXICAL)))
                .collect(scope(), "query", 20);

        assertFalse(result.degraded());
        assertTrue(result.failedSource().isEmpty());
        assertTrue(result.failureType().isEmpty());
        assertEquals(2, result.batches().size());
    }

    @Test
    void passesIndependentLimitsToDenseAndLexicalSources() {
        AtomicInteger denseLimit = new AtomicInteger();
        AtomicInteger lexicalLimit = new AtomicInteger();
        CandidateSource dense = recordingLimit(
                CandidateSourceType.DENSE, denseLimit);
        CandidateSource lexical = recordingLimit(
                CandidateSourceType.LEXICAL, lexicalLimit);

        new DualCandidateSourceCollector(dense, lexical)
                .collect(scope(), "query", 17, 29);

        assertEquals(17, denseLimit.get());
        assertEquals(29, lexicalLimit.get());
    }

    private static RetrievalScope scope() {
        return new RetrievalScope(7L, Set.of(101L));
    }

    private static CandidateSource returning(
            CandidateSourceType type,
            CandidateBatch batch) {
        return new CandidateSource() {
            @Override public CandidateSourceType type() { return type; }
            @Override public CandidateBatch retrieve(
                    RetrievalScope scope, String query, int candidateK) {
                return batch;
            }
        };
    }

    private static CandidateSource failing(
            CandidateSourceType type,
            CandidateSourceFailureType failureType) {
        return new CandidateSource() {
            @Override public CandidateSourceType type() { return type; }
            @Override public CandidateBatch retrieve(
                    RetrievalScope scope, String query, int candidateK) {
                throw new CandidateSourceException(failureType, "isolated failure");
            }
        };
    }

    private static CandidateSource recordingLimit(
            CandidateSourceType type,
            AtomicInteger receivedLimit) {
        return new CandidateSource() {
            @Override public CandidateSourceType type() { return type; }
            @Override public CandidateBatch retrieve(
                    RetrievalScope scope, String query, int candidateK) {
                receivedLimit.set(candidateK);
                return CandidateBatch.empty(type);
            }
        };
    }
}
