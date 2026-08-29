package com.rag.backend.agent.rerank;

import com.rag.backend.agent.retrieval.RetrievedChunk;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Component
// 基于词法命中和向量分数进行本地重排序。
public class LocalLexicalKnowledgeReranker implements KnowledgeReranker {
    private final double vectorWeight;
    private final double lexicalWeight;

    public LocalLexicalKnowledgeReranker(@Value("${rerank.local.vector-weight:0.7}") double vectorWeight,
                                         @Value("${rerank.local.lexical-weight:0.3}") double lexicalWeight) {
        validateWeight("vectorWeight", vectorWeight);
        validateWeight("lexicalWeight", lexicalWeight);
        if (vectorWeight == 0.0 && lexicalWeight == 0.0) {
            throw new IllegalArgumentException(
                    "at least one local rerank weight must be > 0");
        }
        this.vectorWeight = vectorWeight;
        this.lexicalWeight = lexicalWeight;
    }

    public LocalLexicalKnowledgeReranker() {
        this(0.7, 0.3);
    }

    @Override
    public List<RetrievedChunk> rerank(String query, List<RetrievedChunk> chunks, int topK) {
        Set<String> queryTerms = tokenize(query);
        return chunks.stream()
                .map(chunk -> chunk.withRerankScore(
                        combinedScore(chunk, queryTerms)))
                // 分数相同时使用稳定业务 ID，避免底层向量库返回顺序变化污染 A/B 结果。
                .sorted(Comparator
                        .comparing(RetrievedChunk::score,
                                Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(RetrievedChunk::chunkId,
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(RetrievedChunk::documentId,
                                Comparator.nullsLast(Comparator.naturalOrder())))
                .limit(topK)
                .toList();
    }

    private double combinedScore(RetrievedChunk chunk, Set<String> queryTerms) {
        Double baseScore = chunk.scores().baseScore();
        double vectorScore = baseScore == null ? 0.0 : baseScore;
        double lexicalScore = lexicalScore(queryTerms, chunk.content());
        return vectorWeight * vectorScore + lexicalWeight * lexicalScore;
    }

    private double lexicalScore(Set<String> queryTerms, String content) {
        if (queryTerms.isEmpty() || content == null || content.isBlank()) {
            return 0.0;
        }
        Set<String> contentTerms = tokenize(content);
        if (contentTerms.isEmpty()) {
            return 0.0;
        }

        int hit = 0;
        for (String term : queryTerms) {
            if (contentTerms.contains(term)) {
                hit++;
            }
        }
        return hit / (double) queryTerms.size();
    }

    private Set<String> tokenize(String text) {
        Set<String> terms = new HashSet<>();
        if (text == null || text.isBlank()) {
            return terms;
        }

        String normalized = text.toLowerCase(Locale.ROOT);
        String[] pieces = normalized.split("[^\\p{IsHan}a-z0-9]+");
        for (String piece : pieces) {
            if (piece == null || piece.isBlank()) {
                continue;
            }
            if (piece.length() <= 2 && !piece.matches("\\p{IsHan}+")) {
                continue;
            }
            terms.add(piece);
        }
        return terms;
    }

    private static void validateWeight(String name, double value) {
        if (!Double.isFinite(value) || value < 0.0) {
            throw new IllegalArgumentException(
                    name + " must be finite and >= 0");
        }
    }
}
