package com.rag.backend.agent.rerank;

import com.rag.backend.agent.retrieval.RetrievedChunk;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Component
@ConditionalOnExpression("'${rerank.provider:local}' == 'local'")
public class LocalLexicalKnowledgeReranker implements KnowledgeReranker {
    private final double vectorWeight;
    private final double lexicalWeight;

    public LocalLexicalKnowledgeReranker(@Value("${rerank.local.vector-weight:0.7}") double vectorWeight,
                                         @Value("${rerank.local.lexical-weight:0.3}") double lexicalWeight) {
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
                .map(chunk -> chunk.withScore(combinedScore(chunk, queryTerms)))
                .sorted(Comparator.comparing(RetrievedChunk::score).reversed())
                .limit(topK)
                .toList();
    }

    private double combinedScore(RetrievedChunk chunk, Set<String> queryTerms) {
        double vectorScore = chunk.score() == null ? 0.0 : chunk.score();
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
}
