package com.rag.backend.agent.evidence;

import com.rag.backend.agent.history.ChatMessage;
import com.rag.backend.agent.rerank.RerankExecutionResult;
import com.rag.backend.agent.retrieval.ChunkScores;
import com.rag.backend.agent.retrieval.RetrievalDiagnostics;
import com.rag.backend.agent.retrieval.RetrievedChunk;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuleBasedEvidenceDecisionPolicyTest {
    private final EvidenceDecisionProperties properties =
            new EvidenceDecisionProperties();
    private final RuleBasedEvidenceDecisionPolicy policy =
            new RuleBasedEvidenceDecisionPolicy(
                    properties, new EvidenceTextAnalyzer());

    @Test
    void emptySuccessfulRetrievalRefuses() {
        EvidenceDecisionResult result = decide(
                "补考成绩最高记多少分？",
                List.of(),
                diagnostics(RetrievalDiagnostics.EmptyReason
                        .NO_SOURCE_CANDIDATE,
                        RerankExecutionResult.Mode.LOCAL));

        assertEquals(AnswerabilityDecision.REFUSE, result.decision());
        assertEquals(EvidenceDecisionReason.NO_RETRIEVED_EVIDENCE,
                result.reasonCode());
        assertTrue(result.usableEvidenceIds().isEmpty());
    }

    @Test
    void disabledPolicyStillRefusesUntraceableMustCiteEvidence() {
        properties.setEnabled(false);
        RetrievedChunk untraceable = new RetrievedChunk(
                99L,
                null,
                null,
                null,
                "补考成绩最高记六十分。",
                null,
                0.9);

        EvidenceDecisionResult result = decide(
                "补考成绩最高记多少分？",
                List.of(untraceable),
                diagnostics(RetrievalDiagnostics.EmptyReason.NONE,
                        RerankExecutionResult.Mode.LOCAL));

        assertEquals(AnswerabilityDecision.REFUSE, result.decision());
        assertEquals(EvidenceDecisionReason.UNTRACEABLE_EVIDENCE,
                result.reasonCode());
        assertTrue(result.usableEvidenceIds().isEmpty());
    }

    @Test
    void nonEmptyDistractorRefusesEvenWhenFinalScoreIsOne() {
        RetrievedChunk distractor = chunk(
                1L, 11L, "考试安排.md", "考试日期为六月十日。", 1.0);

        EvidenceDecisionResult result = decide(
                "补考成绩最高记多少分？",
                List.of(distractor),
                diagnostics(RetrievalDiagnostics.EmptyReason.NONE,
                        RerankExecutionResult.Mode.LOCAL));

        assertEquals(AnswerabilityDecision.REFUSE, result.decision());
        assertEquals(EvidenceDecisionReason.EVIDENCE_NOT_RELEVANT,
                result.reasonCode());
        assertFalse(result.observedSignals().thresholdConfigured());
    }

    @Test
    void repeatingTheQuestionWithoutAnAnswerIsNotDirectSupport() {
        RetrievedChunk faqIndex = chunk(
                12L, 22L, "常见问题目录.md",
                "问题：补考成绩最高记多少分？", 1.0);

        EvidenceDecisionResult result = decide(
                "补考成绩最高记多少分？",
                List.of(faqIndex),
                diagnostics(RetrievalDiagnostics.EmptyReason.NONE,
                        RerankExecutionResult.Mode.LOCAL));

        assertEquals(AnswerabilityDecision.REFUSE, result.decision());
        assertEquals(EvidenceDecisionReason.INCOMPLETE_DIRECT_SUPPORT,
                result.reasonCode());
        assertEquals(1.0,
                result.observedSignals().directLexicalCoverage());
        assertFalse(result.observedSignals().directAnswerShapeObserved());
    }

    @Test
    void unrelatedDocumentsCannotBeJoinedIntoSyntheticDirectSupport() {
        RetrievedChunk questionTermsOnly = chunk(
                13L, 23L, "补考目录.md", "补考成绩规则。", 0.9);
        RetrievedChunk unrelatedScalar = chunk(
                14L, 24L, "成绩统计.md", "成绩最高记60分。", 0.88);

        EvidenceDecisionResult result = decide(
                "补考成绩最高记多少分？",
                List.of(questionTermsOnly, unrelatedScalar),
                diagnostics(RetrievalDiagnostics.EmptyReason.NONE,
                        RerankExecutionResult.Mode.LOCAL));

        assertEquals(AnswerabilityDecision.REFUSE, result.decision());
        assertTrue(result.observedSignals().directLexicalCoverage() < 1.0);
    }

    @Test
    void chunksFromTheSameTraceableDocumentMayJointlyProvideDirectSupport() {
        RetrievedChunk first = chunkWithPage(
                15L, 25L, "补考规则.md", "补考成绩规则。", 3, 0.9);
        RetrievedChunk second = chunkWithPage(
                16L, 25L, "补考规则.md", "成绩最高记60分。", 4, 0.88);

        EvidenceDecisionResult result = decide(
                "补考成绩最高记多少分？",
                List.of(first, second),
                diagnostics(RetrievalDiagnostics.EmptyReason.NONE,
                        RerankExecutionResult.Mode.LOCAL));

        assertEquals(AnswerabilityDecision.ANSWER, result.decision());
        assertEquals(List.of(15L, 16L), result.usableEvidenceIds());
    }

    @Test
    void directSingleEvidenceAnswersAndKeepsTraceableId() {
        RetrievedChunk evidence = chunk(
                2L, 12L, "补考规则.md",
                "补考成绩最高记六十分，系统按六十分记载。", 0.81);

        EvidenceDecisionResult result = decide(
                "补考成绩最高记多少分？",
                List.of(evidence),
                diagnostics(RetrievalDiagnostics.EmptyReason.NONE,
                        RerankExecutionResult.Mode.LOCAL));

        assertEquals(AnswerabilityDecision.ANSWER, result.decision());
        assertEquals(EvidenceDecisionReason.DIRECT_SUPPORT_OBSERVED,
                result.reasonCode());
        assertEquals(List.of(2L), result.usableEvidenceIds());
        assertEquals(1.0,
                result.observedSignals().directLexicalCoverage());
    }

    @Test
    void vagueQuestionWithoutResolvableHistoryClarifies() {
        EvidenceDecisionResult result = decide(
                "这个是什么意思？",
                List.of(chunk(3L, 13L, "事务.md",
                        "事务回滚会撤销本次修改。", 0.8)),
                diagnostics(RetrievalDiagnostics.EmptyReason.NONE,
                        RerankExecutionResult.Mode.LOCAL));

        assertEquals(AnswerabilityDecision.CLARIFY, result.decision());
        assertEquals(EvidenceDecisionReason.MISSING_QUESTION_SCOPE,
                result.reasonCode());
        assertTrue(result.missingInformation().contains("对象"));
    }

    @Test
    void historyCanResolveAHighPrecisionPronounQuestion() {
        ChatMessage previous = new ChatMessage();
        previous.setRole(ChatMessage.ROLE_USER);
        previous.setContent("事务回滚规则是什么？");
        RetrievedChunk evidence = chunk(
                4L, 14L, "事务.md", "事务回滚规则会撤销本次修改。", 0.8);

        EvidenceDecisionResult result = policy.decide(new EvidenceDecisionInput(
                "这个是什么意思？",
                List.of(previous),
                List.of(evidence),
                EvidenceConstraints.courseChat(),
                diagnostics(RetrievalDiagnostics.EmptyReason.NONE,
                        RerankExecutionResult.Mode.LOCAL)));

        assertEquals(AnswerabilityDecision.ANSWER, result.decision());
    }

    @Test
    void conflictAcrossSourcesClarifiesButSameSourceRefuses() {
        RetrievedChunk first = chunk(
                5L, 15L, "旧版规则.md", "补考成绩最高记60分。", 0.9);
        RetrievedChunk second = chunk(
                6L, 16L, "新版规则.md", "补考成绩最高记70分。", 0.88);

        EvidenceDecisionResult clarifies = decide(
                "补考成绩最高记多少分？",
                List.of(first, second),
                diagnostics(RetrievalDiagnostics.EmptyReason.NONE,
                        RerankExecutionResult.Mode.LOCAL));
        RetrievedChunk sameDocument = chunk(
                7L, 15L, "旧版规则.md", "补考成绩最高记70分。", 0.87);
        EvidenceDecisionResult refuses = decide(
                "补考成绩最高记多少分？",
                List.of(first, sameDocument),
                diagnostics(RetrievalDiagnostics.EmptyReason.NONE,
                        RerankExecutionResult.Mode.LOCAL));

        assertEquals(AnswerabilityDecision.CLARIFY, clarifies.decision());
        assertEquals(EvidenceDecisionReason.CONFLICTING_EVIDENCE,
                clarifies.reasonCode());
        assertEquals(AnswerabilityDecision.REFUSE, refuses.decision());
        assertEquals(EvidenceDecisionReason.CONFLICTING_EVIDENCE,
                refuses.reasonCode());
    }

    @Test
    void askingAboutTheObservedConflictAnswers() {
        RetrievedChunk first = chunk(
                8L, 18L, "旧版规则.md", "补考成绩最高记60分。", 0.9);
        RetrievedChunk second = chunk(
                9L, 19L, "新版规则.md", "补考成绩最高记70分。", 0.88);

        EvidenceDecisionResult result = decide(
                "两份补考成绩规则为什么不同？",
                List.of(first, second),
                diagnostics(RetrievalDiagnostics.EmptyReason.NONE,
                        RerankExecutionResult.Mode.LOCAL));

        assertEquals(AnswerabilityDecision.ANSWER, result.decision());
        assertEquals(EvidenceDecisionReason.CONFLICT_EXPLANATION_REQUESTED,
                result.reasonCode());
        assertEquals(List.of(8L, 9L), result.usableEvidenceIds());
    }

    @Test
    void calibratedThresholdUsesOnlyMatchingScoreProvenance() {
        EvidenceDecisionProperties.ScoreThreshold local =
                properties.getThresholds().getLocalRerank();
        local.setEnabled(true);
        local.setMinScore(0.7);
        local.setCalibrationId("dev-answerability-v1:sha256:test");
        RetrievedChunk below = rerankedChunk(
                10L, 20L, "补考规则.md",
                "补考成绩最高记六十分。", 0.99, 0.6);

        EvidenceDecisionResult result = decide(
                "补考成绩最高记多少分？",
                List.of(below),
                diagnostics(RetrievalDiagnostics.EmptyReason.NONE,
                        RerankExecutionResult.Mode.LOCAL));

        assertEquals(AnswerabilityDecision.REFUSE, result.decision());
        assertEquals(EvidenceDecisionReason
                        .ALL_EVIDENCE_BELOW_CALIBRATED_THRESHOLD,
                result.reasonCode());
        assertEquals("LOCAL_RERANK",
                result.observedSignals().thresholdScoreKind());
        assertEquals(0.7, result.observedSignals().appliedThreshold());
        assertEquals("dev-answerability-v1:sha256:test",
                result.observedSignals().thresholdCalibrationId());
    }

    @Test
    void explicitNegativeFactCanAnswer() {
        EvidenceDecisionResult result = decide(
                "课程是否允许补考？",
                List.of(chunk(11L, 21L, "课程规则.md",
                        "课程明确规定不允许补考。", 0.8)),
                diagnostics(RetrievalDiagnostics.EmptyReason.NONE,
                        RerankExecutionResult.Mode.LOCAL));

        assertEquals(AnswerabilityDecision.ANSWER, result.decision());
        assertNull(result.missingInformation());
    }

    private EvidenceDecisionResult decide(
            String question,
            List<RetrievedChunk> chunks,
            RetrievalDiagnostics diagnostics) {
        return policy.decide(new EvidenceDecisionInput(
                question,
                List.of(),
                chunks,
                EvidenceConstraints.courseChat(),
                diagnostics));
    }

    private RetrievedChunk chunk(
            Long chunkId,
            Long documentId,
            String documentName,
            String content,
            double score) {
        return new RetrievedChunk(
                chunkId, documentId, documentName, documentName,
                content, null, score);
    }

    private RetrievedChunk chunkWithPage(
            Long chunkId,
            Long documentId,
            String documentName,
            String content,
            int sourcePage,
            double score) {
        return new RetrievedChunk(
                chunkId, documentId, documentName, documentName,
                content, sourcePage, score);
    }

    private RetrievedChunk rerankedChunk(
            Long chunkId,
            Long documentId,
            String documentName,
            String content,
            double finalScore,
            double rerankScore) {
        return new RetrievedChunk(
                chunkId,
                documentId,
                documentName,
                documentName,
                content,
                null,
                finalScore,
                new ChunkScores(0.99, null, null,
                        rerankScore, finalScore));
    }

    private RetrievalDiagnostics diagnostics(
            RetrievalDiagnostics.EmptyReason emptyReason,
            RerankExecutionResult.Mode actual) {
        return new RetrievalDiagnostics(
                false,
                emptyReason,
                List.of(),
                new RetrievalDiagnostics.Rerank(
                        actual,
                        actual,
                        RerankExecutionResult.FallbackReason.NONE,
                        RerankExecutionResult.FailureType.NONE,
                        RerankExecutionResult.SemanticEmptyReason.NONE,
                        null,
                        false,
                        null,
                        1,
                        emptyReason == RetrievalDiagnostics.EmptyReason.NONE
                                ? 1
                                : 0,
                        0L));
    }
}
