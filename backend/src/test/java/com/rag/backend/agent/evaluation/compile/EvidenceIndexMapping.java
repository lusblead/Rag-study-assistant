package com.rag.backend.agent.evaluation.compile;

import com.rag.backend.agent.evaluation.model.EvidenceSpan;

import java.util.Set;

// “人工 evidenceId”到“某次冻结索引”的桥；应由第 03A 章冻结制品导出，不手填自增 ID。
public record EvidenceIndexMapping(
        String evidenceId,
        String courseKey,
        Set<Long> chunkIds,
        EvidenceSpan span
) {
    public EvidenceIndexMapping {
        if (evidenceId == null || evidenceId.isBlank()) {
            throw new IllegalArgumentException("evidenceId is required");
        }
        if (courseKey == null || courseKey.isBlank()) {
            throw new IllegalArgumentException("courseKey is required");
        }
        chunkIds = chunkIds == null ? Set.of() : Set.copyOf(chunkIds);
        if (chunkIds.isEmpty() || chunkIds.stream().anyMatch(id -> id == null || id <= 0)) {
            throw new IllegalArgumentException("chunkIds must contain positive IDs");
        }
        if (span == null) {
            throw new IllegalArgumentException("span is required");
        }
    }
}
