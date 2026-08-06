package com.rag.backend.agent.evaluation.model;

// 指向冻结规范化解析制品中的原文区间，使重新切块后的 Candidate 仍能与同一人工真值比较。
public record EvidenceSpan(
        String documentKey,
        String documentHash,
        String parseArtifactDigest,
        Integer pageNo,
        String sectionPath,
        int startOffset,
        int endOffset,
        String quoteHash
) {
    public EvidenceSpan {
        if (documentKey == null || documentKey.isBlank()) {
            throw new IllegalArgumentException("documentKey is required");
        }
        if (documentHash == null || documentHash.isBlank()
                || parseArtifactDigest == null || parseArtifactDigest.isBlank()) {
            throw new IllegalArgumentException("frozen document and parse digests are required");
        }
        // offset 采用页内规范化文本的左闭右开区间 [startOffset, endOffset)。
        if (startOffset < 0 || endOffset <= startOffset) {
            throw new IllegalArgumentException("evidence offsets must describe a non-empty range");
        }
        if (quoteHash == null || quoteHash.isBlank()) {
            throw new IllegalArgumentException("quoteHash is required");
        }
        sectionPath = sectionPath == null ? "" : sectionPath;
    }
}
