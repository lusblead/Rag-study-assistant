package com.rag.backend.agent.retrieval;

// 表示检索后可用于提示词引用的知识片段。
public record RetrievedChunk(
        Long chunkId,
        Long documentId,
        String documentName,
        String title,
        String content,
        Integer sourcePage,
        Double score
) {
    public RetrievedChunk(Long chunkId, Long documentId, String title, String content, Double score) {
        this(chunkId, documentId, title, title, content, null, score);
    }

    public RetrievedChunk(Long chunkId, Long documentId, String title, String content, Integer sourcePage, Double score) {
        this(chunkId, documentId, title, title, content, sourcePage, score);
    }

    public RetrievedChunk withScore(Double newScore) {
        return new RetrievedChunk(chunkId, documentId, documentName, title, content, sourcePage, newScore);
    }
}
