package com.rag.backend.agent.retrieval;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CandidateContractTest {

    @Test
    void candidateCarriesRawScoreAndStableOrderButNoFusionRank() {
        List<String> components = Arrays.stream(
                        RetrievalCandidate.class.getRecordComponents())
                .map(component -> component.getName())
                .toList();

        assertEquals(List.of("chunk", "rawScore", "stableOrder"), components);
        assertFalse(components.contains("rank"));
    }

    @Test
    void candidateBatchDefensivelyCopiesCandidates() {
        java.util.ArrayList<RetrievalCandidate> mutable = new java.util.ArrayList<>();
        CandidateBatch batch = new CandidateBatch(
                CandidateSourceType.DENSE, mutable);

        mutable.add(new RetrievalCandidate(
                new RetrievedChunk(1L, 2L, "title", "content", 0.5),
                0.5,
                0));

        assertEquals(0, batch.candidates().size());
        assertThrows(UnsupportedOperationException.class,
                () -> batch.candidates().add(null));
    }
}
