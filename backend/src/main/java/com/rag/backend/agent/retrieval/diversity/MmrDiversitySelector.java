package com.rag.backend.agent.retrieval.diversity;

import com.rag.backend.agent.retrieval.RetrievalDiagnostics;
import com.rag.backend.agent.retrieval.RetrievedChunk;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Maximal Marginal Relevance 实验候选。
 * 相关性来自上游稳定排序，避免混用不同 Reranker 的不可比分数。
 */
@Component
public final class MmrDiversitySelector implements DiversitySelector {
    public static final String STRATEGY = "jaccard-token-v1";
    private static final double SCORE_EPSILON = 1.0e-12;

    private final boolean enabled;
    private final double lambda;
    private final JaccardTextSimilarity similarity;

    @Autowired
    public MmrDiversitySelector(
            @Value("${rag.diversity.mmr.enabled:false}") boolean enabled,
            @Value("${rag.diversity.mmr.lambda:0.7}") double lambda,
            @Value("${rag.diversity.mmr.similarity:jaccard-token-v1}")
            String strategy) {
        this(enabled, lambda, strategy, new JaccardTextSimilarity());
    }

    public MmrDiversitySelector(
            boolean enabled,
            double lambda,
            String strategy,
            JaccardTextSimilarity similarity) {
        if (!Double.isFinite(lambda) || lambda < 0.0 || lambda > 1.0) {
            throw new IllegalArgumentException(
                    "MMR lambda must be finite and within [0, 1]");
        }
        String normalizedStrategy = strategy == null
                ? ""
                : strategy.trim().toLowerCase(Locale.ROOT);
        if (!STRATEGY.equals(normalizedStrategy)
                && !"jaccard".equals(normalizedStrategy)) {
            throw new IllegalArgumentException(
                    "Unsupported MMR similarity strategy: " + strategy);
        }
        this.enabled = enabled;
        this.lambda = lambda;
        this.similarity = Objects.requireNonNull(similarity, "similarity");
    }

    @Override
    public boolean enabled() {
        return enabled;
    }

    @Override
    public DiversitySelectionResult select(
            List<RetrievedChunk> rankedCandidates,
            int finalK) {
        Objects.requireNonNull(rankedCandidates, "rankedCandidates");
        if (finalK <= 0) {
            throw new IllegalArgumentException("FinalK must be > 0");
        }
        List<RetrievedChunk> candidates = List.copyOf(rankedCandidates);
        validateCandidates(candidates);
        if (!enabled) {
            List<RetrievedChunk> output = candidates.stream()
                    .limit(finalK)
                    .toList();
            return new DiversitySelectionResult(
                    output,
                    RetrievalDiagnostics.Diversity.disabled(
                            candidates.size(), output.size()));
        }

        long started = System.nanoTime();
        int selectionCount = Math.min(finalK, candidates.size());
        List<RetrievedChunk> baseline = candidates.stream()
                .limit(selectionCount)
                .toList();
        List<RetrievedChunk> selected = selectMmr(
                candidates, selectionCount);
        RetrievalDiagnostics.Diversity diagnostics =
                new RetrievalDiagnostics.Diversity(
                        true,
                        STRATEGY,
                        lambda,
                        candidates.size(),
                        selected.size(),
                        meanPairwiseSimilarity(baseline),
                        meanPairwiseSimilarity(selected),
                        uniqueDocumentCount(baseline),
                        uniqueDocumentCount(selected),
                        Math.max(0L, System.nanoTime() - started));
        return new DiversitySelectionResult(selected, diagnostics);
    }

    private List<RetrievedChunk> selectMmr(
            List<RetrievedChunk> candidates,
            int selectionCount) {
        if (selectionCount == 0) {
            return List.of();
        }
        Map<Long, Integer> upstreamRanks = new HashMap<>();
        for (int index = 0; index < candidates.size(); index++) {
            upstreamRanks.put(candidates.get(index).chunkId(), index);
        }
        List<RetrievedChunk> remaining = new ArrayList<>(candidates);
        List<RetrievedChunk> selected = new ArrayList<>(selectionCount);

        while (selected.size() < selectionCount) {
            RetrievedChunk best = null;
            double bestScore = Double.NEGATIVE_INFINITY;
            for (RetrievedChunk candidate : remaining) {
                int rank = upstreamRanks.get(candidate.chunkId());
                double relevance = 1.0
                        - ((double) rank / candidates.size());
                double redundancy = selected.stream()
                        .mapToDouble(existing -> similarity.similarity(
                                candidate.content(), existing.content()))
                        .max()
                        .orElse(0.0);
                double score = lambda * relevance
                        - (1.0 - lambda) * redundancy;
                if (isBetter(candidate, score, best, bestScore,
                        upstreamRanks)) {
                    best = candidate;
                    bestScore = score;
                }
            }
            selected.add(Objects.requireNonNull(best, "best candidate"));
            remaining.remove(best);
        }
        return List.copyOf(selected);
    }

    private boolean isBetter(
            RetrievedChunk candidate,
            double score,
            RetrievedChunk best,
            double bestScore,
            Map<Long, Integer> upstreamRanks) {
        if (best == null || score > bestScore + SCORE_EPSILON) {
            return true;
        }
        if (Math.abs(score - bestScore) > SCORE_EPSILON) {
            return false;
        }
        int rankComparison = Integer.compare(
                upstreamRanks.get(candidate.chunkId()),
                upstreamRanks.get(best.chunkId()));
        return rankComparison < 0
                || (rankComparison == 0
                && candidate.chunkId() < best.chunkId());
    }

    private double meanPairwiseSimilarity(List<RetrievedChunk> chunks) {
        if (chunks.size() < 2) {
            return 0.0;
        }
        double total = 0.0;
        int pairs = 0;
        for (int left = 0; left < chunks.size(); left++) {
            for (int right = left + 1; right < chunks.size(); right++) {
                total += similarity.similarity(
                        chunks.get(left).content(),
                        chunks.get(right).content());
                pairs++;
            }
        }
        return total / pairs;
    }

    private int uniqueDocumentCount(List<RetrievedChunk> chunks) {
        Set<String> documents = new HashSet<>();
        for (RetrievedChunk chunk : chunks) {
            if (chunk.documentId() != null) {
                documents.add("id:" + chunk.documentId());
            } else {
                documents.add("name:" + Objects.toString(
                        chunk.documentName(), ""));
            }
        }
        return documents.size();
    }

    private void validateCandidates(List<RetrievedChunk> candidates) {
        Set<Long> chunkIds = new HashSet<>();
        for (RetrievedChunk candidate : candidates) {
            if (candidate == null || candidate.chunkId() == null) {
                throw new IllegalArgumentException(
                        "MMR candidates require non-null chunkId");
            }
            if (!chunkIds.add(candidate.chunkId())) {
                throw new IllegalArgumentException(
                        "MMR candidates require unique chunkId");
            }
        }
    }
}
