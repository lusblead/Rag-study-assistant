package com.rag.backend.agent.evidence;

import com.rag.backend.agent.rerank.RerankExecutionResult;
import com.rag.backend.agent.retrieval.ChunkScores;
import com.rag.backend.agent.retrieval.RetrievalDiagnostics;
import com.rag.backend.agent.retrieval.RetrievedChunk;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Comparator;

/**
 * 可复现的生成前保守规则。数值分数只在对应来源的 Dev 门槛显式开启后参与决策。
 */
@Component
public class RuleBasedEvidenceDecisionPolicy
        implements EvidenceDecisionPolicy {
    public static final String POLICY_VERSION =
            "evidence-sufficiency-rule-v1";
    private static final String MISSING_SCOPE =
            "请说明你所指的对象、版本或时间范围。";
    private static final String MISSING_CONFLICT_SCOPE =
            "请说明应以哪个版本、时间范围或资料来源为准。";

    private final EvidenceDecisionProperties properties;
    private final EvidenceTextAnalyzer textAnalyzer;

    public RuleBasedEvidenceDecisionPolicy(
            EvidenceDecisionProperties properties,
            EvidenceTextAnalyzer textAnalyzer) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.textAnalyzer = Objects.requireNonNull(
                textAnalyzer, "textAnalyzer");
    }

    @Override
    public EvidenceDecisionResult decide(EvidenceDecisionInput input) {
        Objects.requireNonNull(input, "input");
        EvidenceTextAnalyzer.QuestionSignals question =
                textAnalyzer.questionSignals(
                        input.originalQuestion(), input.history());
        List<RetrievedChunk> deduplicated = deduplicate(input.chunks());
        int traceableCount = (int) deduplicated.stream()
                .filter(textAnalyzer::traceable)
                .count();

        if (deduplicated.isEmpty()) {
            return result(
                    AnswerabilityDecision.REFUSE,
                    EvidenceDecisionReason.NO_RETRIEVED_EVIDENCE,
                    List.of(),
                    null,
                    signals(input, question, 0, 0, 0, 0.0,
                            false, EvidenceScoreKind.NONE, null, false));
        }

        List<RetrievedChunk> citeable = input.constraints().mustCite()
                ? deduplicated.stream()
                        .filter(textAnalyzer::traceable)
                        .toList()
                : deduplicated;

        if (!properties.isEnabled()) {
            if (citeable.isEmpty()) {
                return result(
                        AnswerabilityDecision.REFUSE,
                        EvidenceDecisionReason.UNTRACEABLE_EVIDENCE,
                        List.of(),
                        null,
                        signals(input, question, deduplicated.size(),
                                traceableCount, 0, 0.0,
                                false, EvidenceScoreKind.NONE, null, false));
            }
            return result(
                    AnswerabilityDecision.ANSWER,
                    EvidenceDecisionReason.POLICY_DISABLED,
                    ids(citeable),
                    null,
                    signals(input, question, deduplicated.size(),
                            traceableCount, citeable.size(), 0.0,
                            false, EvidenceScoreKind.NONE, null, false));
        }

        if (question.ambiguous()) {
            return result(
                    AnswerabilityDecision.CLARIFY,
                    EvidenceDecisionReason.MISSING_QUESTION_SCOPE,
                    List.of(),
                    MISSING_SCOPE,
                    signals(input, question, deduplicated.size(),
                            traceableCount, 0, 0.0,
                            false, EvidenceScoreKind.NONE, null, false));
        }

        if (citeable.isEmpty()) {
            return result(
                    AnswerabilityDecision.REFUSE,
                    EvidenceDecisionReason.UNTRACEABLE_EVIDENCE,
                    List.of(),
                    null,
                    signals(input, question, deduplicated.size(),
                            traceableCount, 0, 0.0,
                            false, EvidenceScoreKind.NONE, null, false));
        }

        EvidenceScoreKind scoreKind = scoreKind(
                citeable, input.retrievalDiagnostics());
        EvidenceDecisionProperties.ScoreThreshold threshold =
                properties.threshold(scoreKind);
        boolean thresholdConfigured = threshold.isEnabled();
        List<RetrievedChunk> eligible = thresholdConfigured
                ? citeable.stream()
                        .filter(chunk -> {
                            Double score = score(chunk, scoreKind);
                            return score != null
                                    && score >= threshold.getMinScore();
                        })
                        .toList()
                : citeable;
        if (eligible.isEmpty()) {
            return result(
                    AnswerabilityDecision.REFUSE,
                    EvidenceDecisionReason
                            .ALL_EVIDENCE_BELOW_CALIBRATED_THRESHOLD,
                    List.of(),
                    null,
                    signals(input, question, deduplicated.size(),
                            traceableCount, 0, 0.0,
                            true, scoreKind, threshold.getMinScore(), false));
        }

        boolean conflict = textAnalyzer.conflictDetected(
                question.terms(), eligible);
        DirectSupport directSupport = strongestDirectSupport(
                question, eligible);
        double coverage = directSupport.coverage();
        boolean directAnswerShape = directSupport.answerShapeObserved();
        if (conflict && question.asksAboutConflict()) {
            return result(
                    AnswerabilityDecision.ANSWER,
                    EvidenceDecisionReason.CONFLICT_EXPLANATION_REQUESTED,
                    ids(eligible),
                    null,
                    signals(input, question, deduplicated.size(),
                            traceableCount, eligible.size(), coverage,
                            thresholdConfigured, scoreKind,
                            thresholdValue(threshold), true,
                            directAnswerShape));
        }
        if (conflict) {
            boolean clarifiable = textAnalyzer
                    .conflictCanBeClarified(eligible);
            return result(
                    clarifiable
                            ? AnswerabilityDecision.CLARIFY
                            : AnswerabilityDecision.REFUSE,
                    EvidenceDecisionReason.CONFLICTING_EVIDENCE,
                    List.of(),
                    clarifiable ? MISSING_CONFLICT_SCOPE : null,
                    signals(input, question, deduplicated.size(),
                            traceableCount, eligible.size(), coverage,
                            thresholdConfigured, scoreKind,
                            thresholdValue(threshold), true,
                            directAnswerShape));
        }

        if (!question.terms().isEmpty()
                && coverage >= 1.0
                && directAnswerShape) {
            return result(
                    AnswerabilityDecision.ANSWER,
                    EvidenceDecisionReason.DIRECT_SUPPORT_OBSERVED,
                    ids(directSupport.chunks()),
                    null,
                    signals(input, question, deduplicated.size(),
                            traceableCount, eligible.size(), coverage,
                            thresholdConfigured, scoreKind,
                            thresholdValue(threshold), false,
                            true));
        }

        EvidenceDecisionReason reason = coverage == 0.0
                ? EvidenceDecisionReason.EVIDENCE_NOT_RELEVANT
                : EvidenceDecisionReason.INCOMPLETE_DIRECT_SUPPORT;
        return result(
                AnswerabilityDecision.REFUSE,
                reason,
                List.of(),
                null,
                signals(input, question, deduplicated.size(),
                        traceableCount, eligible.size(), coverage,
                        thresholdConfigured, scoreKind,
                        thresholdValue(threshold), false,
                        directAnswerShape));
    }

    private EvidenceDecisionResult result(
            AnswerabilityDecision decision,
            EvidenceDecisionReason reason,
            List<Long> evidenceIds,
            String missingInformation,
            EvidenceObservedSignals signals) {
        return new EvidenceDecisionResult(
                decision,
                reason,
                evidenceIds,
                missingInformation,
                signals,
                POLICY_VERSION);
    }

    private EvidenceObservedSignals signals(
            EvidenceDecisionInput input,
            EvidenceTextAnalyzer.QuestionSignals question,
            int deduplicatedCount,
            int traceableCount,
            int eligibleCount,
            double coverage,
            boolean thresholdConfigured,
            EvidenceScoreKind scoreKind,
            Double threshold,
            boolean conflict) {
        return signals(
                input, question, deduplicatedCount, traceableCount,
                eligibleCount, coverage, thresholdConfigured, scoreKind,
                threshold, conflict, false);
    }

    private EvidenceObservedSignals signals(
            EvidenceDecisionInput input,
            EvidenceTextAnalyzer.QuestionSignals question,
            int deduplicatedCount,
            int traceableCount,
            int eligibleCount,
            double coverage,
            boolean thresholdConfigured,
            EvidenceScoreKind scoreKind,
            Double threshold,
            boolean conflict,
            boolean directAnswerShape) {
        return new EvidenceObservedSignals(
                input.chunks().size(),
                deduplicatedCount,
                traceableCount,
                eligibleCount,
                coverage,
                directAnswerShape,
                question.ambiguous(),
                question.asksAboutConflict(),
                conflict,
                thresholdConfigured,
                scoreKind == EvidenceScoreKind.NONE
                        ? null
                        : scoreKind.name(),
                thresholdConfigured
                        ? properties.threshold(scoreKind).getCalibrationId()
                        : null,
                threshold,
                input.retrievalDiagnostics().degraded(),
                input.retrievalDiagnostics().emptyReason());
    }

    private List<RetrievedChunk> deduplicate(List<RetrievedChunk> chunks) {
        Map<String, RetrievedChunk> unique = new LinkedHashMap<>();
        int nullKey = 0;
        for (RetrievedChunk chunk : chunks) {
            if (chunk == null) {
                continue;
            }
            String key = textAnalyzer.normalizedEvidenceKey(chunk);
            if (key.isBlank()) {
                key = "__empty__" + nullKey++;
            }
            unique.putIfAbsent(key, chunk);
        }
        return List.copyOf(unique.values());
    }

    /**
     * 只允许单一 Chunk，或同一文档相邻页的 Chunk，共同形成直接支持；不能把无关系的
     * 跨文档/跨页词项和答案形态拼接成一次 ANSWER。
     */
    private DirectSupport strongestDirectSupport(
            EvidenceTextAnalyzer.QuestionSignals question,
            List<RetrievedChunk> chunks) {
        List<List<RetrievedChunk>> sourceGroups = new ArrayList<>();
        Map<Long, List<RetrievedChunk>> chunksByDocument =
                new LinkedHashMap<>();
        for (RetrievedChunk chunk : chunks) {
            sourceGroups.add(List.of(chunk));
            if (chunk.documentId() != null && chunk.sourcePage() != null) {
                chunksByDocument.computeIfAbsent(
                        chunk.documentId(), ignored -> new ArrayList<>())
                        .add(chunk);
            }
        }
        for (List<RetrievedChunk> documentChunks :
                chunksByDocument.values()) {
            List<RetrievedChunk> ordered = documentChunks.stream()
                    .sorted(Comparator
                            .comparing(RetrievedChunk::sourcePage)
                            .thenComparing(
                                    RetrievedChunk::chunkId,
                                    Comparator.nullsLast(
                                            Comparator.naturalOrder())))
                    .toList();
            List<RetrievedChunk> cluster = new ArrayList<>();
            Integer previousPage = null;
            for (RetrievedChunk chunk : ordered) {
                if (previousPage != null
                        && chunk.sourcePage() > previousPage + 1) {
                    addCluster(sourceGroups, cluster);
                    cluster = new ArrayList<>();
                }
                cluster.add(chunk);
                previousPage = chunk.sourcePage();
            }
            addCluster(sourceGroups, cluster);
        }
        return sourceGroups.stream()
                .map(group -> new DirectSupport(
                        List.copyOf(group),
                        textAnalyzer.directLexicalCoverage(
                                question.terms(), group),
                        textAnalyzer.directAnswerShapeObserved(
                                question.effectiveQuestion(),
                                question.terms(),
                                group)))
                .max(Comparator
                        .comparing(DirectSupport::complete)
                        .thenComparingDouble(DirectSupport::coverage)
                        .thenComparing(DirectSupport::answerShapeObserved))
                .orElse(new DirectSupport(List.of(), 0.0, false));
    }

    private void addCluster(
            List<List<RetrievedChunk>> sourceGroups,
            List<RetrievedChunk> cluster) {
        if (cluster.size() > 1) {
            sourceGroups.add(List.copyOf(cluster));
        }
    }

    private List<Long> ids(List<RetrievedChunk> chunks) {
        List<Long> ids = new ArrayList<>();
        for (RetrievedChunk chunk : chunks) {
            if (chunk.chunkId() != null && !ids.contains(chunk.chunkId())) {
                ids.add(chunk.chunkId());
            }
        }
        return List.copyOf(ids);
    }

    private EvidenceScoreKind scoreKind(
            List<RetrievedChunk> chunks,
            RetrievalDiagnostics diagnostics) {
        boolean hasRerank = chunks.stream()
                .anyMatch(chunk -> chunk.scores().rerankScore() != null);
        RerankExecutionResult.Mode actual = diagnostics.rerank()
                .actualReranker();
        if (hasRerank && actual == RerankExecutionResult.Mode.REMOTE) {
            return EvidenceScoreKind.REMOTE_RERANK;
        }
        if (hasRerank && actual == RerankExecutionResult.Mode.LOCAL) {
            return EvidenceScoreKind.LOCAL_RERANK;
        }
        if (chunks.stream().anyMatch(
                chunk -> chunk.scores().fusionScore() != null)) {
            return EvidenceScoreKind.FUSION;
        }
        if (chunks.stream().anyMatch(
                chunk -> chunk.scores().denseScore() != null)) {
            return EvidenceScoreKind.DENSE;
        }
        if (chunks.stream().anyMatch(
                chunk -> chunk.scores().lexicalScore() != null)) {
            return EvidenceScoreKind.LEXICAL;
        }
        if (chunks.stream().anyMatch(chunk -> chunk.score() != null)) {
            return EvidenceScoreKind.LEGACY_FINAL;
        }
        return EvidenceScoreKind.NONE;
    }

    private Double score(RetrievedChunk chunk, EvidenceScoreKind kind) {
        ChunkScores scores = chunk.scores();
        return switch (kind) {
            case REMOTE_RERANK, LOCAL_RERANK -> scores.rerankScore();
            case FUSION -> scores.fusionScore();
            case DENSE -> scores.denseScore();
            case LEXICAL -> scores.lexicalScore();
            case LEGACY_FINAL -> chunk.score();
            case NONE -> null;
        };
    }

    private Double thresholdValue(
            EvidenceDecisionProperties.ScoreThreshold threshold) {
        return threshold.isEnabled() ? threshold.getMinScore() : null;
    }

    private record DirectSupport(
            List<RetrievedChunk> chunks,
            double coverage,
            boolean answerShapeObserved
    ) {
        private boolean complete() {
            return coverage >= 1.0 && answerShapeObserved;
        }
    }
}
