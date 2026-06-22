package com.rag.backend.agent.retrieval;

// 表示检索后可用于提示词引用的知识片段。
public record RetrievedChunk(
        Long chunkId,
        Long documentId,
        String title,
        String content,
        Double score
) {
    public RetrievedChunk withScore(Double newScore) {
        return new RetrievedChunk(chunkId, documentId, title, content, newScore);
    }
}
