package com.rag.backend.agent.evaluation;

import java.util.List;

// 汇总整批样本的平均检索指标和失败明细，供版本之间稳定比较。
public record RetrievalEvalReport(
        // k：本次指标采用的截断位置；Recall@K、Precision@K 和 NDCG@K 必须使用同一个 K。
        int k,
        // answerableCases：参与可回答指标聚合的样本数；它是宏平均分母，不能与全量样本数混用。
        int answerableCases,
        // unanswerableCases：不可回答样本数量，单独用于空召回和拒答能力统计。
        int unanswerableCases,
        // macroRecallAtK：各可回答样本 Recall@K 的宏平均，衡量必要证据被找回的比例。
        double macroRecallAtK,
        // macroPrecisionAtK：各可回答样本 Precision@K 的宏平均，衡量前 K 个候选中的证据密度。
        double macroPrecisionAtK,
        // meanReciprocalRank：每个样本首个相关证据排名倒数的平均值，越高表示关键证据越靠前。
        double meanReciprocalRank,
        // macroNdcgAtK：先按样本计算 NDCG@K 再取平均的排序质量指标，避免大样本支配结果。
        double macroNdcgAtK,
        // emptyRetrievalAccuracy：不可回答样本中检索器正确返回空结果的比例，用来衡量系统避免伪证据的能力。
        double emptyRetrievalAccuracy,
        List<CaseRetrievalMetrics> cases
) {}
