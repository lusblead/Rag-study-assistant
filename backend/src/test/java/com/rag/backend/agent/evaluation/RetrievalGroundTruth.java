package com.rag.backend.agent.evaluation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

// 通用检索真值：同时表达严格证据、可替代证据、证据组与来源覆盖要求。
public record RetrievalGroundTruth(
        String caseId,
        Answerability answerability,
        Set<Long> relevantChunkIds,
        Set<Long> acceptableChunkIds,
        List<Set<Long>> requiredEvidenceGroups,
        Map<Long, Long> chunkIdToSourceId,
        Set<Long> requiredSourceIds
) {
    public RetrievalGroundTruth {
        caseId = Objects.requireNonNull(caseId, "caseId");
        if (caseId.isBlank()) {
            throw new IllegalArgumentException("caseId must not be blank");
        }
        answerability = Objects.requireNonNull(answerability, "answerability");
        relevantChunkIds = immutableSortedLongSet(relevantChunkIds);

        TreeSet<Long> normalizedAcceptable = new TreeSet<>(
                immutableSortedLongSet(acceptableChunkIds));
        // 严格相关证据永远也是可接受证据，调用方无需重复维护这条不变量。
        normalizedAcceptable.addAll(relevantChunkIds);
        acceptableChunkIds = Collections.unmodifiableSet(normalizedAcceptable);

        requiredEvidenceGroups = immutableEvidenceGroups(requiredEvidenceGroups);
        chunkIdToSourceId = immutableSortedMapping(chunkIdToSourceId);
        requiredSourceIds = immutableSortedLongSet(requiredSourceIds);
    }

    // 旧 GoldenRagCase 不包含来源和证据组元数据，因此仅映射其严格 chunk 真值。
    public static RetrievalGroundTruth from(GoldenRagCase item) {
        Objects.requireNonNull(item, "item");
        return new RetrievalGroundTruth(
                item.caseId(),
                item.answerability(),
                item.relevantChunkIds(),
                item.relevantChunkIds(),
                List.of(),
                Map.of(),
                Set.of());
    }

    private static Set<Long> immutableSortedLongSet(Set<Long> values) {
        if (values == null || values.isEmpty()) {
            return Set.of();
        }
        TreeSet<Long> normalized = new TreeSet<>();
        values.stream().filter(Objects::nonNull).forEach(normalized::add);
        return Collections.unmodifiableSet(normalized);
    }

    private static List<Set<Long>> immutableEvidenceGroups(List<Set<Long>> groups) {
        if (groups == null || groups.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<Set<Long>> normalized = new LinkedHashSet<>();
        for (Set<Long> group : groups) {
            normalized.add(immutableSortedLongSet(group));
        }
        return List.copyOf(new ArrayList<>(normalized));
    }

    private static Map<Long, Long> immutableSortedMapping(Map<Long, Long> mapping) {
        if (mapping == null || mapping.isEmpty()) {
            return Map.of();
        }
        TreeMap<Long, Long> normalized = new TreeMap<>();
        mapping.forEach((chunkId, sourceId) -> {
            if (chunkId != null && sourceId != null) {
                normalized.put(chunkId, sourceId);
            }
        });
        return Collections.unmodifiableMap(normalized);
    }
}
