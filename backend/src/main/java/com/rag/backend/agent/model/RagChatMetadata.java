package com.rag.backend.agent.model;

import com.rag.backend.agent.evidence.EvidenceDecisionResult;
import com.rag.backend.agent.grounding.GroundingDiagnostics;
import com.rag.backend.agent.retrieval.RetrievalDiagnostics;

import java.util.Objects;

/** 普通响应与 SSE 共用的生成前决策、检索及生成后校验脱敏元数据。 */
public record RagChatMetadata(
        EvidenceDecisionResult evidenceDecision,
        RetrievalDiagnostics retrieval,
        GroundingDiagnostics grounding,
        com.rag.backend.agent.materials.MaterialStatus materials
) {
    public RagChatMetadata(EvidenceDecisionResult evidenceDecision, RetrievalDiagnostics retrieval,
            GroundingDiagnostics grounding) {
        this(evidenceDecision, retrieval, grounding, null);
    }

    public RagChatMetadata withMaterials(com.rag.backend.agent.materials.MaterialStatus materials) {
        return new RagChatMetadata(evidenceDecision, retrieval, grounding, materials);
    }
    public RagChatMetadata {
        evidenceDecision = Objects.requireNonNull(
                evidenceDecision, "evidenceDecision");
        retrieval = Objects.requireNonNull(retrieval, "retrieval");
        grounding = Objects.requireNonNull(grounding, "grounding");
    }

    public RagChatMetadata(
            EvidenceDecisionResult evidenceDecision,
            RetrievalDiagnostics retrieval) {
        this(evidenceDecision, retrieval,
                GroundingDiagnostics.notApplicable());
    }

    public RagChatMetadata withGrounding(
            GroundingDiagnostics grounding) {
        return new RagChatMetadata(evidenceDecision, retrieval, grounding, materials);
    }
}
