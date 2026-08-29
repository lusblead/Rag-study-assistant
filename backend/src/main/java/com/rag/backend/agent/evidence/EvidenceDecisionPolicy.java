package com.rag.backend.agent.evidence;

/** 在调用生成模型前决定 ANSWER、CLARIFY 或 REFUSE。 */
public interface EvidenceDecisionPolicy {
    EvidenceDecisionResult decide(EvidenceDecisionInput input);
}
