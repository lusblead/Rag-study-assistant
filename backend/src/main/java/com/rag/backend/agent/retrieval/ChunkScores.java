package com.rag.backend.agent.retrieval;

/**
 * 保存一个候选在检索、融合、重排和最终排序阶段的独立分数。
 *
 * <p>缺失值始终使用 {@code null}；不能用 0 伪装某个阶段没有产生分数。
 */
public record ChunkScores(
        Double denseScore,
        Double lexicalScore,
        Double fusionScore,
        Double rerankScore,
        Double finalScore
) {
    public ChunkScores {
        requireFiniteOrNull("denseScore", denseScore);
        requireFiniteOrNull("lexicalScore", lexicalScore);
        requireFiniteOrNull("fusionScore", fusionScore);
        requireFiniteOrNull("rerankScore", rerankScore);
        requireFiniteOrNull("finalScore", finalScore);
    }

    public static ChunkScores empty() {
        return new ChunkScores(null, null, null, null, null);
    }

    /** 兼容旧构造器：只能证明有一个当前分数，不能猜测它来自哪个阶段。 */
    public static ChunkScores legacy(Double score) {
        return empty().withFinalScore(score);
    }

    /**
     * Rerank 前的基准分数：优先使用融合分；单源时使用对应 source 分；
     * 旧调用方只提供一个 score 时最后回退到 finalScore。
     */
    public Double baseScore() {
        if (fusionScore != null) {
            return fusionScore;
        }
        if (denseScore != null) {
            return denseScore;
        }
        if (lexicalScore != null) {
            return lexicalScore;
        }
        return finalScore;
    }

    public ChunkScores withDenseScore(Double value) {
        return new ChunkScores(value, lexicalScore, fusionScore,
                rerankScore, value);
    }

    public ChunkScores withLexicalScore(Double value) {
        return new ChunkScores(denseScore, value, fusionScore,
                rerankScore, value);
    }

    public ChunkScores withFusionScore(Double value) {
        return new ChunkScores(denseScore, lexicalScore, value,
                rerankScore, value);
    }

    public ChunkScores withRerankScore(Double value) {
        return new ChunkScores(denseScore, lexicalScore, fusionScore,
                value, value);
    }

    public ChunkScores withFinalScore(Double value) {
        return new ChunkScores(denseScore, lexicalScore, fusionScore,
                rerankScore, value);
    }

    private static void requireFiniteOrNull(String name, Double value) {
        if (value != null && !Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite or null");
        }
    }
}
