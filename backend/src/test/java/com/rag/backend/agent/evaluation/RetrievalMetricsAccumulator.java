package com.rag.backend.agent.evaluation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 保存 richer Runner 报告所需的多 K Core 结果，不计算任何检索指标。
 */
public final class RetrievalMetricsAccumulator {
    private final List<RetrievalGroundTruth> groundTruths = new ArrayList<>();
    private final Map<Integer, List<CaseRetrievalMetrics>> resultsByK =
            new LinkedHashMap<>();

    public void add(RetrievalGroundTruth groundTruth,
                    Map<Integer, CaseRetrievalMetrics> results) {
        Objects.requireNonNull(groundTruth, "groundTruth");
        Objects.requireNonNull(results, "results");
        if (results.isEmpty()) {
            throw new IllegalArgumentException("results must not be empty");
        }
        results.forEach((k, metrics) -> {
            if (k == null || k <= 0) {
                throw new IllegalArgumentException("k must be positive");
            }
            if (metrics == null
                    || !Objects.equals(groundTruth.caseId(), metrics.caseId())) {
                throw new IllegalArgumentException(
                        "ground truth/result case mismatch for k=" + k);
            }
        });
        if (!resultsByK.isEmpty() && !resultsByK.keySet().equals(results.keySet())) {
            throw new IllegalArgumentException("inconsistent metric K set");
        }

        groundTruths.add(groundTruth);
        results.forEach((k, metrics) -> resultsByK
                .computeIfAbsent(k, ignored -> new ArrayList<>())
                .add(metrics));
    }

    public long size() {
        return groundTruths.size();
    }

    public List<RetrievalGroundTruth> groundTruths() {
        return List.copyOf(groundTruths);
    }

    public List<CaseRetrievalMetrics> resultsAt(int k) {
        List<CaseRetrievalMetrics> results = resultsByK.get(k);
        if (results == null) {
            throw new IllegalArgumentException("metrics not collected for k=" + k);
        }
        return List.copyOf(results);
    }
}
