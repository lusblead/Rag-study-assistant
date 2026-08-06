package com.rag.backend.agent.rerank;

import com.rag.backend.agent.retrieval.RetrievedChunk;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LocalLexicalKnowledgeRerankerTest {

    @Test
    void equalScoresUseChunkIdAsStableTieBreaker() {
        LocalLexicalKnowledgeReranker reranker =
                new LocalLexicalKnowledgeReranker(0.7, 0.3);
        RetrievedChunk laterChunk = new RetrievedChunk(
                20L, 2L, "later", "没有查询词", 0.5);
        RetrievedChunk earlierChunk = new RetrievedChunk(
                10L, 1L, "earlier", "同样没有查询词", 0.5);

        List<RetrievedChunk> result = reranker.rerank(
                "needle", List.of(laterChunk, earlierChunk), 2);

        assertEquals(List.of(10L, 20L),
                result.stream().map(RetrievedChunk::chunkId).toList());
    }
}
