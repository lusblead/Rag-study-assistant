package com.rag.backend.agent.retrieval;

public record RetrievedChunk(
        Long chunkId,
        Long documentId,
        String title,
        String content,
        Double score
) {}
