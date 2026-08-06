package com.rag.backend.agent.evaluation;

import com.rag.backend.agent.evaluation.model.EvidenceSpan;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

// 把检索输入、人工证据真值和回答约束放进同一版本化样本，Runner 与 Judge 共用这份契约。
public record GoldenRagCase(
        String schemaVersion,
        String caseId,
        DatasetSplit split,
        Severity severity,
        Long courseId,
        String question,
        List<GoldenHistoryMessage> history,
        Set<Long> relevantChunkIds,
        List<EvidenceSpan> relevantEvidenceSpans,
        List<String> referenceClaims,
        Answerability answerability,
        boolean mustCite,
        Set<String> tags
) {
    // 把所有集合规范化为不可变空集合或副本，防止 Runner 执行期间 fixture 被外部修改。
    public GoldenRagCase {
        history = history == null ? List.of() : List.copyOf(history);
        relevantChunkIds = relevantChunkIds == null
                ? Set.of()
                : Collections.unmodifiableSet(new TreeSet<>(relevantChunkIds));
        relevantEvidenceSpans = relevantEvidenceSpans == null
                ? List.of()
                : List.copyOf(relevantEvidenceSpans);
        referenceClaims = referenceClaims == null ? List.of() : List.copyOf(referenceClaims);
        tags = tags == null
                ? Set.of()
                : Collections.unmodifiableSet(new TreeSet<>(tags));
    }

    // 数学单测不需要重复构造完整证据区间；真实 JSONL 仍由 Loader 强制要求 v2 schema 和 Evidence Span。
    public GoldenRagCase(String caseId,
                         Long courseId,
                         String question,
                         List<String> history,
                         Set<Long> relevantChunkIds,
                         List<String> referenceClaims,
                         Answerability answerability,
                         boolean mustCite,
                         Set<String> tags) {
        this("rag-golden-v2", caseId, DatasetSplit.DEVELOPMENT, Severity.MEDIUM,
                courseId, question,
                history == null
                        ? List.of()
                        : history.stream()
                                .map(text -> new GoldenHistoryMessage("user", text))
                                .toList(),
                relevantChunkIds, List.of(),
                referenceClaims, answerability, mustCite, tags);
    }
}
