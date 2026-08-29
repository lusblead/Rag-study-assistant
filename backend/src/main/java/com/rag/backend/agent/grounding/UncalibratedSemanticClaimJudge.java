package com.rag.backend.agent.grounding;

import org.springframework.stereotype.Component;

import java.util.List;

/** 默认保守实现：没有人工校准证据时不做语义放行。 */
@Component
public class UncalibratedSemanticClaimJudge implements SemanticClaimJudge {
    public static final String CALIBRATION_ID = "NOT_CALIBRATED";

    @Override
    public boolean calibrated() {
        return false;
    }

    @Override
    public String calibrationId() {
        return CALIBRATION_ID;
    }

    @Override
    public SemanticJudgeDecision judge(
            AtomicClaim claim, List<CitationSource> evidence) {
        return new SemanticJudgeDecision(
                ClaimSupportStatus.UNCERTAIN,
                "SEMANTIC_JUDGE_NOT_CALIBRATED");
    }
}
