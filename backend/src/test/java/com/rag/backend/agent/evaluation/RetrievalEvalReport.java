package com.rag.backend.agent.evaluation;

import java.util.List;

// 汇总整批样本的宏平均检索指标；正例指标只聚合可回答样例。
public record RetrievalEvalReport(
        // 本次所有 @K 指标共用的截断位置。
        int k,
        // 可回答样例数，也是全部正例宏平均的分母。
        int answerableCases,
        // 不可回答样例数，只作为空召回准确率和误召回率的分母。
        int unanswerableCases,
        double macroRecallAtK,
        double macroAcceptableRecallAtK,
        double macroPrecisionAtK,
        double meanReciprocalRank,
        double meanAcceptableReciprocalRank,
        double macroNdcgAtK,
        double macroAcceptableNdcgAtK,
        double macroSourceCoverageAtK,
        double macroRequiredEvidenceGroupCoverageAtK,
        // 不可回答样例中 TopK 为空的比例；有不可回答样例时与 unanswerableFalsePositiveRate 互补。
        double emptyRetrievalAccuracy,
        // 不可回答样例中 TopK 非空的比例；无不可回答样例时按约定返回 0.0。
        double unanswerableFalsePositiveRate,
        List<CaseRetrievalMetrics> cases
) {
    public RetrievalEvalReport {
        cases = cases == null ? List.of() : List.copyOf(cases);
    }

    // 保留原有九参数构造方式的源码兼容性。
    public RetrievalEvalReport(int k,
                               int answerableCases,
                               int unanswerableCases,
                               double macroRecallAtK,
                               double macroPrecisionAtK,
                               double meanReciprocalRank,
                               double macroNdcgAtK,
                               double emptyRetrievalAccuracy,
                               List<CaseRetrievalMetrics> cases) {
        this(k, answerableCases, unanswerableCases,
                macroRecallAtK, macroRecallAtK, macroPrecisionAtK,
                meanReciprocalRank, meanReciprocalRank,
                macroNdcgAtK, macroNdcgAtK,
                0.0, 0.0,
                emptyRetrievalAccuracy,
                unanswerableCases == 0 ? 0.0 : 1.0 - emptyRetrievalAccuracy,
                cases);
    }
}
