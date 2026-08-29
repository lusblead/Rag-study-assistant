package com.rag.backend.agent.grounding;

/** 不包含证据正文的引用校验失败明细。 */
public record CitationIntegrityFailure(
        CitationIntegrityReason reason,
        Integer claimIndex,
        String detail
) {
}
