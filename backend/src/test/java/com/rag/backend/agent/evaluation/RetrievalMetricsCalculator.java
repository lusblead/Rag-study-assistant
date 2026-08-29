package com.rag.backend.agent.evaluation;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

// 计算严格证据、可替代证据、来源和证据组等确定性检索指标。
public final class RetrievalMetricsCalculator {

    // 兼容入口：旧 GoldenRagCase 只有严格 chunk 真值，其 acceptable 真值等同于严格真值。
    public CaseRetrievalMetrics evaluateCase(GoldenRagCase item,
                                             List<Long> rawRankedChunkIds,
                                             int k) {
        Objects.requireNonNull(item, "item");
        return evaluateCase(RetrievalGroundTruth.from(item), rawRankedChunkIds, k);
    }

    // 单样本入口：先按首次出现去重并截断 K，再按 ground truth 的不同口径计算指标。
    public CaseRetrievalMetrics evaluateCase(RetrievalGroundTruth groundTruth,
                                             List<Long> rawRankedChunkIds,
                                             int k) {
        validateK(k);
        Objects.requireNonNull(groundTruth, "groundTruth");

        List<Long> ranked = deduplicate(rawRankedChunkIds).stream().limit(k).toList();
        if (groundTruth.answerability() == Answerability.UNANSWERABLE) {
            // 不可回答样例没有正例真值；只记录检索器是否错误返回了任意候选。
            return new CaseRetrievalMetrics(
                    groundTruth.caseId(), ranked,
                    0.0, 0.0, 0.0,
                    0.0, 0.0,
                    0.0, 0.0,
                    0.0, 0.0,
                    ranked.isEmpty() ? 0.0 : 1.0);
        }

        Set<Long> strictRelevant = groundTruth.relevantChunkIds();
        Set<Long> acceptable = groundTruth.acceptableChunkIds();
        return new CaseRetrievalMetrics(
                groundTruth.caseId(),
                ranked,
                recallAtK(ranked, strictRelevant),
                recallAtK(ranked, acceptable),
                // Precision@K 使用固定 K 为分母；未返回满 K 的位置不计为命中。
                precisionAtK(ranked, strictRelevant, k),
                reciprocalRank(ranked, strictRelevant),
                reciprocalRank(ranked, acceptable),
                ndcgAtK(ranked, strictRelevant, k),
                ndcgAtK(ranked, acceptable, k),
                sourceCoverageAtK(ranked, groundTruth),
                requiredEvidenceGroupCoverageAtK(ranked, groundTruth.requiredEvidenceGroups()),
                0.0);
    }

    // 兼容入口：按 GoldenRagCase 顺序校验并聚合；额外指标由兼容 ground truth 推导。
    public RetrievalEvalReport summarize(List<GoldenRagCase> dataset,
                                         List<CaseRetrievalMetrics> results,
                                         int k) {
        return summarizeInternal(
                dataset, results, k, GoldenRagCase::caseId, GoldenRagCase::answerability);
    }

    // Ground-truth 聚合入口单独命名，避免 List<T> 参数在 Java 泛型擦除后与旧入口冲突。
    public RetrievalEvalReport summarizeGroundTruth(List<RetrievalGroundTruth> dataset,
                                                    List<CaseRetrievalMetrics> results,
                                                    int k) {
        return summarizeInternal(
                dataset, results, k,
                RetrievalGroundTruth::caseId, RetrievalGroundTruth::answerability);
    }

    private <T> RetrievalEvalReport summarizeInternal(List<T> dataset,
                                                      List<CaseRetrievalMetrics> results,
                                                      int k,
                                                      Function<T, String> caseId,
                                                      Function<T, Answerability> answerability) {
        validateK(k);
        Objects.requireNonNull(dataset, "dataset");
        Objects.requireNonNull(results, "results");
        if (dataset.size() != results.size()) {
            throw new IllegalArgumentException("dataset/result size mismatch");
        }

        List<Double> recalls = new ArrayList<>();
        List<Double> precisions = new ArrayList<>();
        List<Double> reciprocalRanks = new ArrayList<>();
        List<Double> ndcgs = new ArrayList<>();
        List<Double> acceptableRecalls = new ArrayList<>();
        List<Double> acceptableReciprocalRanks = new ArrayList<>();
        List<Double> acceptableNdcgs = new ArrayList<>();
        List<Double> sourceCoverages = new ArrayList<>();
        List<Double> evidenceGroupCoverages = new ArrayList<>();
        int answerableCases = 0;
        int unanswerableCases = 0;
        double falsePositives = 0.0;

        for (int index = 0; index < dataset.size(); index++) {
            T item = dataset.get(index);
            CaseRetrievalMetrics result = results.get(index);
            if (!Objects.equals(caseId.apply(item), result.caseId())) {
                throw new IllegalArgumentException("case order mismatch at index " + index);
            }
            if (answerability.apply(item) == Answerability.ANSWERABLE) {
                answerableCases++;
                recalls.add(result.recallAtK());
                precisions.add(result.precisionAtK());
                reciprocalRanks.add(result.reciprocalRank());
                ndcgs.add(result.ndcgAtK());
                acceptableRecalls.add(result.acceptableRecallAtK());
                acceptableReciprocalRanks.add(result.acceptableReciprocalRank());
                acceptableNdcgs.add(result.acceptableNdcgAtK());
                sourceCoverages.add(result.sourceCoverageAtK());
                evidenceGroupCoverages.add(result.requiredEvidenceGroupCoverageAtK());
            } else {
                unanswerableCases++;
                // 重新由规范化 TopK 判定，兼容旧构造器产生的 CaseRetrievalMetrics。
                falsePositives += result.rankedChunkIds().isEmpty() ? 0.0 : 1.0;
            }
        }

        double falsePositiveRate = unanswerableCases == 0
                ? 0.0 : falsePositives / unanswerableCases;
        double emptyRetrievalAccuracy = unanswerableCases == 0
                ? 0.0 : 1.0 - falsePositiveRate;
        return new RetrievalEvalReport(
                k,
                answerableCases,
                unanswerableCases,
                average(recalls),
                average(acceptableRecalls),
                average(precisions),
                average(reciprocalRanks),
                average(acceptableReciprocalRanks),
                average(ndcgs),
                average(acceptableNdcgs),
                average(sourceCoverages),
                average(evidenceGroupCoverages),
                emptyRetrievalAccuracy,
                falsePositiveRate,
                results);
    }

    // Recall@K：TopK 命中的真值数 / 全部真值数；真值为空时定义为 0.0。
    private double recallAtK(List<Long> ranked, Set<Long> relevant) {
        if (relevant.isEmpty()) {
            return 0.0;
        }
        return hitCount(ranked, relevant) / (double) relevant.size();
    }

    // Precision@K：TopK 命中的严格真值数 / K；K 已在入口保证为正数。
    private double precisionAtK(List<Long> ranked, Set<Long> relevant, int k) {
        return hitCount(ranked, relevant) / (double) k;
    }

    // MRR：TopK 中首个相关 chunk 排名的倒数；无命中时为 0.0。
    private double reciprocalRank(List<Long> ranked, Set<Long> relevant) {
        for (int index = 0; index < ranked.size(); index++) {
            if (relevant.contains(ranked.get(index))) {
                return 1.0 / (index + 1.0);
            }
        }
        return 0.0;
    }

    // nDCG@K：二元相关性 DCG / 最多 min(K, 真值数) 个理想命中的 IDCG。
    private double ndcgAtK(List<Long> ranked, Set<Long> relevant, int k) {
        if (relevant.isEmpty()) {
            return 0.0;
        }
        double dcg = 0.0;
        for (int index = 0; index < ranked.size(); index++) {
            if (relevant.contains(ranked.get(index))) {
                dcg += 1.0 / log2(index + 2.0);
            }
        }
        double idcg = 0.0;
        for (int index = 0; index < Math.min(k, relevant.size()); index++) {
            idcg += 1.0 / log2(index + 2.0);
        }
        return idcg == 0.0 ? 0.0 : dcg / idcg;
    }

    // Source Coverage@K：TopK 覆盖的必需 sourceId 数 / requiredSourceIds 数。
    private double sourceCoverageAtK(List<Long> ranked, RetrievalGroundTruth groundTruth) {
        Set<Long> requiredSources = groundTruth.requiredSourceIds();
        if (requiredSources.isEmpty()) {
            return 0.0;
        }
        Set<Long> coveredSources = new LinkedHashSet<>();
        for (Long chunkId : ranked) {
            Long sourceId = groundTruth.chunkIdToSourceId().get(chunkId);
            if (sourceId != null && requiredSources.contains(sourceId)) {
                coveredSources.add(sourceId);
            }
        }
        return coveredSources.size() / (double) requiredSources.size();
    }

    // Required Evidence Group Coverage@K：至少命中一个可替代 chunk 的组数 / 必需组总数。
    private double requiredEvidenceGroupCoverageAtK(List<Long> ranked,
                                                    List<Set<Long>> requiredGroups) {
        if (requiredGroups.isEmpty()) {
            return 0.0;
        }
        Set<Long> topK = new LinkedHashSet<>(ranked);
        long covered = requiredGroups.stream()
                .filter(group -> group.stream().anyMatch(topK::contains))
                .count();
        return covered / (double) requiredGroups.size();
    }

    private long hitCount(List<Long> ranked, Set<Long> relevant) {
        return ranked.stream().filter(relevant::contains).count();
    }

    // 原始排名为 null 时视为空；null chunk 被忽略，其余 chunk 按首次出现去重。
    private List<Long> deduplicate(List<Long> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<Long> unique = new LinkedHashSet<>();
        for (Long value : values) {
            if (value != null) {
                unique.add(value);
            }
        }
        return new ArrayList<>(unique);
    }

    // 宏平均没有有效样本时明确返回 0.0，避免 NaN。
    private double average(List<Double> values) {
        return values.isEmpty()
                ? 0.0
                : values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
    }

    private void validateK(int k) {
        if (k <= 0) {
            throw new IllegalArgumentException("k must be positive");
        }
    }

    private double log2(double value) {
        return Math.log(value) / Math.log(2.0);
    }
}
