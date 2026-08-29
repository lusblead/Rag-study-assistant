package com.rag.backend.agent.retrieval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class ChunkScoresTest {

    @Test
    void stageUpdatesPreserveScoresFromOtherStages() {
        ChunkScores scores = ChunkScores.empty()
                .withDenseScore(0.8)
                .withLexicalScore(0.3)
                .withFusionScore(0.6)
                .withRerankScore(0.9)
                .withFinalScore(0.7);

        assertEquals(0.8, scores.denseScore());
        assertEquals(0.3, scores.lexicalScore());
        assertEquals(0.6, scores.fusionScore());
        assertEquals(0.9, scores.rerankScore());
        assertEquals(0.7, scores.finalScore());
    }

    @Test
    void missingStageScoresRemainNull() {
        ChunkScores empty = ChunkScores.empty();

        assertNull(empty.denseScore());
        assertNull(empty.lexicalScore());
        assertNull(empty.fusionScore());
        assertNull(empty.rerankScore());
        assertNull(empty.finalScore());
        assertNull(empty.baseScore());
        assertNull(ChunkScores.legacy(null).baseScore());
    }

    @Test
    void baseScoreUsesFusionThenDenseThenLexicalThenLegacyFinal() {
        assertEquals(0.6, new ChunkScores(
                0.8, 0.3, 0.6, 0.9, 0.7).baseScore());
        assertEquals(0.8, new ChunkScores(
                0.8, 0.3, null, 0.9, 0.7).baseScore());
        assertEquals(0.3, new ChunkScores(
                null, 0.3, null, 0.9, 0.7).baseScore());
        assertEquals(0.7, new ChunkScores(
                null, null, null, 0.9, 0.7).baseScore());
        assertEquals(0.5, ChunkScores.legacy(0.5).baseScore());
    }

    @Test
    void retrievedChunkJsonKeepsScoreAndHidesInternalStageScores()
            throws Exception {
        RetrievedChunk chunk = new RetrievedChunk(
                1L,
                11L,
                "document",
                "title",
                "content",
                1,
                0.7,
                new ChunkScores(0.8, 0.3, 0.6, 0.9, 0.7));

        ObjectMapper objectMapper = new ObjectMapper();
        JsonNode json = objectMapper.readTree(
                objectMapper.writeValueAsString(chunk));

        assertEquals(0.7, json.path("score").doubleValue());
        assertFalse(json.has("scores"));
        assertFalse(json.has("denseScore"));
        assertFalse(json.has("lexicalScore"));
        assertFalse(json.has("fusionScore"));
        assertFalse(json.has("rerankScore"));
        assertFalse(json.has("finalScore"));
    }
}
