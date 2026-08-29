package com.rag.backend.agent.grounding;

import java.util.List;
import java.util.Objects;

/** 单条原子主张的四态判断；不复制证据正文。 */
public record ClaimSupportAssessment(
        AtomicClaim claim,
        ClaimSupportStatus status,
        List<String> evidenceSourceIds,
        String reasonCode
) {
    public ClaimSupportAssessment {
        claim = Objects.requireNonNull(claim, "claim");
        status = Objects.requireNonNull(status, "status");
        evidenceSourceIds = List.copyOf(Objects.requireNonNull(
                evidenceSourceIds, "evidenceSourceIds"));
        if (reasonCode == null || reasonCode.isBlank()) {
            throw new IllegalArgumentException("reasonCode is required");
        }
    }
}
