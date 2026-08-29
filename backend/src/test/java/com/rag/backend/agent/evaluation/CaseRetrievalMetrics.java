package com.rag.backend.agent.evaluation;

import java.util.List;

// 单个样本的确定性检索指标；所有正例指标在不可回答样例上都定义为 0.0。
public record CaseRetrievalMetrics(
        String caseId,
        // 同一索引快照内首次出现去重并截断 K 后的物理 chunk ID。
        List<Long> rankedChunkIds,
        // 严格 Recall@K：命中的 strict relevant chunk 数 / strict relevant 总数。
        double recallAtK,
        // Acceptable Recall@K：命中的 strict 或可替代 chunk 数 / acceptable 总数。
        double acceptableRecallAtK,
        // 严格 Precision@K：命中的 strict relevant chunk 数 / K。
        double precisionAtK,
        // 严格 MRR：首个 strict relevant chunk 排名的倒数。
        double reciprocalRank,
        // Acceptable MRR：首个 strict 或可替代 chunk 排名的倒数。
        double acceptableReciprocalRank,
        // 严格 nDCG@K：以 strict relevant 为二元相关真值的归一化 DCG。
        double ndcgAtK,
        // Acceptable nDCG@K：以 strict 与可替代 chunk 为二元相关真值的归一化 DCG。
        double acceptableNdcgAtK,
        // Source Coverage@K：TopK 覆盖的 required source 数 / required source 总数。
        double sourceCoverageAtK,
        // 必需证据组覆盖：TopK 至少命中组内一个可替代 chunk 的组比例。
        double requiredEvidenceGroupCoverageAtK,
        // 不可回答误召回：规范化 TopK 非空为 1.0，空为 0.0；可回答样例恒为 0.0。
        double unanswerableFalsePositive
) {
    public CaseRetrievalMetrics {
        rankedChunkIds = rankedChunkIds == null ? List.of() : List.copyOf(rankedChunkIds);
    }

    // 保留原有六参数构造方式的源码兼容性；新增指标在旧调用方中默认没有额外真值。
    public CaseRetrievalMetrics(String caseId,
                                List<Long> rankedChunkIds,
                                double recallAtK,
                                double precisionAtK,
                                double reciprocalRank,
                                double ndcgAtK) {
        this(caseId, rankedChunkIds,
                recallAtK, recallAtK, precisionAtK,
                reciprocalRank, reciprocalRank,
                ndcgAtK, ndcgAtK,
                0.0, 0.0,
                0.0);
    }
}
