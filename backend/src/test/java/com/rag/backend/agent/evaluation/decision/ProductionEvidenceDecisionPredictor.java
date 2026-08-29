package com.rag.backend.agent.evaluation.decision;

import com.rag.backend.agent.evidence.EvidenceConstraints;
import com.rag.backend.agent.evidence.EvidenceDecisionInput;
import com.rag.backend.agent.evidence.EvidenceDecisionProperties;
import com.rag.backend.agent.evidence.EvidenceDecisionResult;
import com.rag.backend.agent.evidence.EvidenceScoreKind;
import com.rag.backend.agent.evidence.EvidenceTextAnalyzer;
import com.rag.backend.agent.evidence.RuleBasedEvidenceDecisionPolicy;
import com.rag.backend.agent.history.ChatMessage;
import com.rag.backend.agent.rerank.RerankExecutionResult;
import com.rag.backend.agent.retrieval.ChunkScores;
import com.rag.backend.agent.retrieval.RetrievalDiagnostics;
import com.rag.backend.agent.retrieval.RetrievedChunk;

import java.util.List;

/**
 * Test-scope adapter that replays frozen policy-visible inputs through the
 * production Step 3.2 rule set. It has no access to truth or review fields.
 */
public final class ProductionEvidenceDecisionPredictor
        implements ThresholdDecisionPredictor {
    public static final String PREDICTOR_ID =
            "production-evidence-decision-policy-adapter-v1/"
                    + RuleBasedEvidenceDecisionPolicy.POLICY_VERSION;

    @Override
    public DecisionPrediction predict(
            DecisionEvaluationInput input,
            double answerThreshold) {
        if (!Double.isFinite(answerThreshold)) {
            throw new IllegalArgumentException("answerThreshold must be finite");
        }
        FrozenDecisionSnapshot snapshot = input.retrievalSnapshot();
        EvidenceScoreKind scoreKind = EvidenceScoreKind.valueOf(
                snapshot.scoreKind());
        if (scoreKind == EvidenceScoreKind.NONE) {
            throw new IllegalArgumentException(
                    "threshold sweep cannot use EvidenceScoreKind.NONE");
        }

        EvidenceDecisionProperties properties = new EvidenceDecisionProperties();
        EvidenceDecisionProperties.ScoreThreshold threshold =
                properties.threshold(scoreKind);
        threshold.setMinScore(answerThreshold);
        threshold.setCalibrationId(
                "DEV_SWEEP_ONLY:" + snapshot.pipelineFingerprint());
        threshold.setEnabled(true);
        RuleBasedEvidenceDecisionPolicy policy =
                new RuleBasedEvidenceDecisionPolicy(
                        properties, new EvidenceTextAnalyzer());
        EvidenceDecisionResult result = policy.decide(
                productionInput(input, snapshot, scoreKind));
        return new DecisionPrediction(
                input.caseId(),
                DecisionOutcome.valueOf(result.decision().name()),
                null,
                result.reasonCode().name(),
                result.policyVersion());
    }

    @Override
    public String predictorId() {
        return PREDICTOR_ID;
    }

    private EvidenceDecisionInput productionInput(
            DecisionEvaluationInput input,
            FrozenDecisionSnapshot snapshot,
            EvidenceScoreKind scoreKind) {
        List<ChatMessage> history = input.history().stream()
                .map(this::chatMessage)
                .toList();
        List<RetrievedChunk> chunks = snapshot.candidates().stream()
                .limit(snapshot.finalK())
                .map(candidate -> retrievedChunk(candidate, scoreKind))
                .toList();
        return new EvidenceDecisionInput(
                input.originalQuestion(),
                history,
                chunks,
                new EvidenceConstraints(input.constraints().mustCite()),
                diagnostics(snapshot, chunks.size()));
    }

    private ChatMessage chatMessage(
            AnswerabilityDecisionCase.HistoryMessage source) {
        ChatMessage target = new ChatMessage();
        target.setRole(source.role());
        target.setContent(source.content());
        return target;
    }

    private RetrievedChunk retrievedChunk(
            FrozenDecisionSnapshot.SnapshotCandidate candidate,
            EvidenceScoreKind scoreKind) {
        ChunkScores scores = switch (scoreKind) {
            case REMOTE_RERANK, LOCAL_RERANK ->
                    ChunkScores.empty().withRerankScore(
                            candidate.decisionScore());
            case FUSION -> ChunkScores.empty().withFusionScore(
                    candidate.decisionScore());
            case DENSE -> ChunkScores.empty().withDenseScore(
                    candidate.decisionScore());
            case LEXICAL -> ChunkScores.empty().withLexicalScore(
                    candidate.decisionScore());
            case LEGACY_FINAL -> ChunkScores.empty().withFinalScore(
                    candidate.decisionScore());
            case NONE -> throw new IllegalArgumentException(
                    "threshold sweep cannot use EvidenceScoreKind.NONE");
        };
        return new RetrievedChunk(
                candidate.chunkId(),
                candidate.documentId(),
                candidate.documentName(),
                candidate.title(),
                candidate.content(),
                candidate.sourcePage(),
                scores.finalScore(),
                scores);
    }

    private RetrievalDiagnostics diagnostics(
            FrozenDecisionSnapshot snapshot,
            int outputCandidateCount) {
        RerankExecutionResult.Mode actual =
                RerankExecutionResult.Mode.valueOf(snapshot.actualReranker());
        return new RetrievalDiagnostics(
                false,
                outputCandidateCount == 0
                        ? RetrievalDiagnostics.EmptyReason.NO_SOURCE_CANDIDATE
                        : RetrievalDiagnostics.EmptyReason.NONE,
                List.of(),
                new RetrievalDiagnostics.Rerank(
                        actual,
                        actual,
                        RerankExecutionResult.FallbackReason.NONE,
                        RerankExecutionResult.FailureType.NONE,
                        outputCandidateCount == 0
                                ? RerankExecutionResult.SemanticEmptyReason.INPUT_EMPTY
                                : RerankExecutionResult.SemanticEmptyReason.NONE,
                        null,
                        false,
                        null,
                        outputCandidateCount,
                        outputCandidateCount,
                        0L));
    }
}
