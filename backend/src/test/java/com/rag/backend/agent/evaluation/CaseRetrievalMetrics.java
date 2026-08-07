package com.rag.backend.agent.evaluation;

import java.util.List;

// 计算 Recall、Precision、MRR、NDCG 等确定性检索指标。
public record CaseRetrievalMetrics(
        String caseId,
        // rankedChunkIds：同一索引快照内按最终顺序返回的物理 chunk ID，不能跨切块 profile 当稳定证据身份。
        List<Long> rankedChunkIds,
        // recallAtK：当前样本所需证据在前 K 个候选中被找回的比例。
        double recallAtK,
        // precisionAtK：当前样本前 K 个候选中相关证据所占比例。
        double precisionAtK,
        // reciprocalRank：第一个相关结果排名的倒数。
        double reciprocalRank,
        // ndcgAtK：当前样本前 K 个结果的归一化折损累计增益，兼顾相关性与排序位置。
        double ndcgAtK
) {}
