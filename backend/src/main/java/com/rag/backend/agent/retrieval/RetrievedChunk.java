package com.rag.backend.agent.retrieval;

import com.fasterxml.jackson.annotation.JsonIgnore;

// 表示检索后可用于提示词引用的知识片段。
public record RetrievedChunk(
        Long chunkId,
        Long documentId,
        String documentName,
        String title,
        String content,
        Integer sourcePage,
        Double score,
        @JsonIgnore ChunkScores scores,
        Long documentVersionId
) {
    public RetrievedChunk(Long chunkId, Long documentId, String documentName, String title,
            String content, Integer sourcePage, Double score, ChunkScores scores) {
        this(chunkId, documentId, documentName, title, content, sourcePage, score, scores, null);
    }

    public RetrievedChunk withDocumentVersionId(Long versionId) {
        return new RetrievedChunk(chunkId, documentId, documentName, title,
                content, sourcePage, score, scores, versionId);
    }
    public RetrievedChunk {
        scores = scores == null ? ChunkScores.legacy(score) : scores;
        if (scores.finalScore() == null && score != null) {
            scores = scores.withFinalScore(score);
        } else if (scores.finalScore() != null) {
            score = scores.finalScore();
        }
    }

    public RetrievedChunk(
            Long chunkId,
            Long documentId,
            String documentName,
            String title,
            String content,
            Integer sourcePage,
            Double score) {
        this(chunkId, documentId, documentName, title, content, sourcePage,
                score, ChunkScores.legacy(score));
    }

    public RetrievedChunk(Long chunkId, Long documentId, String title, String content, Double score) {
        this(chunkId, documentId, title, title, content, null, score);
    }

    public RetrievedChunk(Long chunkId, Long documentId, String title, String content, Integer sourcePage, Double score) {
        this(chunkId, documentId, title, title, content, sourcePage, score);
    }

    public RetrievedChunk withScore(Double newScore) {
        return withFinalScore(newScore);
    }

    public RetrievedChunk withDenseScore(Double newScore) {
        return withScores(scores.withDenseScore(newScore));
    }

    public RetrievedChunk withLexicalScore(Double newScore) {
        return withScores(scores.withLexicalScore(newScore));
    }

    public RetrievedChunk withFusionScore(Double newScore) {
        return withScores(scores.withFusionScore(newScore));
    }

    public RetrievedChunk withRerankScore(Double newScore) {
        return withScores(scores.withRerankScore(newScore));
    }

    public RetrievedChunk withFinalScore(Double newScore) {
        return withScores(scores.withFinalScore(newScore));
    }

    private RetrievedChunk withScores(ChunkScores newScores) {
        return new RetrievedChunk(chunkId, documentId, documentName, title,
                content, sourcePage, newScores.finalScore(), newScores, documentVersionId);
    }
}
