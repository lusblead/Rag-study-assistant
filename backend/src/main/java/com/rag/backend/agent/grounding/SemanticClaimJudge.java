package com.rag.backend.agent.grounding;

import java.util.List;

/** 复杂语义判断扩展点；只有人工复核集校准后的实现才可返回 SUPPORTED。 */
public interface SemanticClaimJudge {
    boolean calibrated();

    String calibrationId();

    SemanticJudgeDecision judge(
            AtomicClaim claim, List<CitationSource> evidence);
}
