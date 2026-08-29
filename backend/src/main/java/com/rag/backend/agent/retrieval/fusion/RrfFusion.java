package com.rag.backend.agent.retrieval.fusion;

import com.rag.backend.agent.retrieval.CandidateBatch;
import com.rag.backend.agent.retrieval.CandidateSourceType;
import com.rag.backend.agent.retrieval.RetrievedChunk;
import com.rag.backend.agent.retrieval.RetrievalCandidate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 对有序候选批次执行稳定回退或 Reciprocal Rank Fusion。
 */
public final class RrfFusion {
    private final int k;
    private final Map<CandidateSourceType, Double> weights;

    public RrfFusion(int k, double denseWeight, double lexicalWeight) {
        if (k <= 0) {
            throw new IllegalArgumentException("RRF k must be > 0");
        }
        validateWeight("denseWeight", denseWeight);
        validateWeight("lexicalWeight", lexicalWeight);
        if (denseWeight == 0.0 && lexicalWeight == 0.0) {
            throw new IllegalArgumentException(
                    "At least one RRF source weight must be > 0");
        }
        this.k = k;
        EnumMap<CandidateSourceType, Double> configured =
                new EnumMap<>(CandidateSourceType.class);
        configured.put(CandidateSourceType.DENSE, denseWeight);
        configured.put(CandidateSourceType.LEXICAL, lexicalWeight);
        this.weights = Map.copyOf(configured);
    }

    /**
     * 两路均非空时执行 RRF；否则稳定去重并保留唯一非空路的原始分数与顺序。
     */
    public List<RetrievalCandidate> fuse(List<CandidateBatch> orderedBatches) {
        Objects.requireNonNull(orderedBatches, "orderedBatches");
        List<CandidateBatch> batches = List.copyOf(orderedBatches);
        validateBatches(batches);

        List<CandidateBatch> nonEmpty = batches.stream()
                .filter(batch -> !batch.candidates().isEmpty())
                .toList();
        if (nonEmpty.isEmpty()) {
            return List.of();
        }
        if (nonEmpty.size() == 1) {
            return stableDeduplicate(nonEmpty.get(0));
        }
        List<CandidateBatch> positiveWeightBatches = nonEmpty.stream()
                .filter(batch -> weights.get(batch.source()) > 0.0)
                .toList();
        if (positiveWeightBatches.size() == 1) {
            return stableDeduplicate(positiveWeightBatches.get(0));
        }
        return reciprocalRankFusion(positiveWeightBatches);
    }

    private List<RetrievalCandidate> stableDeduplicate(CandidateBatch batch) {
        Map<Long, RetrievalCandidate> firstByChunk = new LinkedHashMap<>();
        for (RetrievalCandidate candidate : batch.candidates()) {
            firstByChunk.putIfAbsent(chunkId(candidate),
                    new RetrievalCandidate(
                            withSourceScore(candidate.chunk(), batch.source(),
                                    candidate.rawScore()),
                            candidate.rawScore(),
                            candidate.stableOrder()));
        }
        return List.copyOf(firstByChunk.values());
    }

    private List<RetrievalCandidate> reciprocalRankFusion(
            List<CandidateBatch> batches) {
        Map<Long, ScoreAccumulator> byChunk = new HashMap<>();
        for (CandidateBatch batch : batches) {
            double weight = weights.get(batch.source());
            List<RetrievalCandidate> candidates = stableDeduplicate(batch);
            for (int index = 0; index < candidates.size(); index++) {
                RetrievalCandidate candidate = candidates.get(index);
                long chunkId = chunkId(candidate);
                int rank = index + 1;
                ScoreAccumulator accumulator = byChunk.computeIfAbsent(
                        chunkId, ignored -> new ScoreAccumulator(candidate));
                accumulator.add(batch.source(), candidate,
                        weight / (k + (double) rank));
            }
        }

        List<RetrievalCandidate> fused = new ArrayList<>(byChunk.size());
        for (ScoreAccumulator accumulator : byChunk.values()) {
            double score = accumulator.score;
            RetrievalCandidate metadata = accumulator.metadata;
            fused.add(new RetrievalCandidate(
                    accumulator.chunk.withFusionScore(score),
                    score,
                    metadata.stableOrder()));
        }
        fused.sort(Comparator
                .comparingDouble(RetrievalCandidate::rawScore)
                .reversed()
                .thenComparing(candidate -> candidate.chunk().chunkId()));
        return List.copyOf(fused);
    }

    private void validateBatches(List<CandidateBatch> batches) {
        Set<CandidateSourceType> sources = new HashSet<>();
        for (CandidateBatch batch : batches) {
            Objects.requireNonNull(batch, "orderedBatches must not contain null");
            if (!sources.add(batch.source())) {
                throw new IllegalArgumentException(
                        "orderedBatches must contain at most one batch per source");
            }
            for (RetrievalCandidate candidate : batch.candidates()) {
                chunkId(candidate);
            }
        }
    }

    private long chunkId(RetrievalCandidate candidate) {
        Objects.requireNonNull(candidate, "candidate");
        return Objects.requireNonNull(
                candidate.chunk().chunkId(), "candidate chunkId");
    }

    private static void validateWeight(String name, double weight) {
        if (!Double.isFinite(weight) || weight < 0.0) {
            throw new IllegalArgumentException(
                    name + " must be finite and >= 0");
        }
    }

    private static RetrievedChunk withSourceScore(
            RetrievedChunk chunk,
            CandidateSourceType source,
            double score) {
        return switch (source) {
            case DENSE -> chunk.withDenseScore(score);
            case LEXICAL -> chunk.withLexicalScore(score);
        };
    }

    private static final class ScoreAccumulator {
        private final RetrievalCandidate metadata;
        private RetrievedChunk chunk;
        private double score;

        private ScoreAccumulator(RetrievalCandidate metadata) {
            this.metadata = metadata;
            this.chunk = metadata.chunk();
        }

        private void add(
                CandidateSourceType source,
                RetrievalCandidate candidate,
                double contribution) {
            chunk = withSourceScore(
                    chunk, source, candidate.rawScore());
            score += contribution;
        }
    }
}
