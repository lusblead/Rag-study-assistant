package com.rag.backend.rerank;

import com.rag.backend.agent.rerank.KnowledgeReranker;
import com.rag.backend.agent.rerank.LocalLexicalKnowledgeReranker;
import com.rag.backend.agent.rerank.RerankExecutionResult;
import com.rag.backend.agent.retrieval.RetrievedChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeoutException;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import static com.rag.backend.agent.rerank.RerankExecutionResult.Attempt;
import static com.rag.backend.agent.rerank.RerankExecutionResult.AttemptOutcome;
import static com.rag.backend.agent.rerank.RerankExecutionResult.FailureType;
import static com.rag.backend.agent.rerank.RerankExecutionResult.FallbackReason;
import static com.rag.backend.agent.rerank.RerankExecutionResult.Mode;
import static com.rag.backend.agent.rerank.RerankExecutionResult.SemanticEmptyReason;

@Primary
@Component
public class DynamicKnowledgeReranker implements KnowledgeReranker {
    private static final Logger log = LoggerFactory.getLogger(
            DynamicKnowledgeReranker.class);

    private final RerankSettingsService settingsService;
    private final LocalLexicalKnowledgeReranker local;
    private final RerankPolicy policy;
    private final LongSupplier nanoTime;

    @Autowired
    public DynamicKnowledgeReranker(
            RerankSettingsService settingsService,
            LocalLexicalKnowledgeReranker local,
            @Value("${rerank.threshold.remote.enabled:false}")
            boolean remoteThresholdEnabled,
            @Value("${rerank.threshold.remote.min-score:0.0}")
            double remoteThreshold,
            @Value("${rerank.threshold.local.enabled:false}")
            boolean localThresholdEnabled,
            @Value("${rerank.threshold.local.min-score:0.0}")
            double localThreshold,
            @Value("${rerank.composite.enabled:false}")
            boolean compositeEnabled,
            @Value("${rerank.composite.version:normalized-min-max-v1}")
            String compositeVersion,
            @Value("${rerank.composite.base-weight:0.5}")
            double compositeBaseWeight,
            @Value("${rerank.composite.rerank-weight:0.5}")
            double compositeRerankWeight) {
        this(settingsService, local,
                new RerankPolicy(
                        new RerankPolicy.Threshold(
                                remoteThresholdEnabled, remoteThreshold),
                        new RerankPolicy.Threshold(
                                localThresholdEnabled, localThreshold),
                        new RerankPolicy.Composite(
                                compositeEnabled, compositeVersion,
                                compositeBaseWeight, compositeRerankWeight)),
                System::nanoTime);
    }

    /** 保留旧手工装配入口；Spring 主链路使用上面的完整构造器。 */
    public DynamicKnowledgeReranker(RerankSettingsService settingsService) {
        this(settingsService, new LocalLexicalKnowledgeReranker(),
                RerankPolicy.defaults(), System::nanoTime);
    }

    DynamicKnowledgeReranker(
            RerankSettingsService settingsService,
            LocalLexicalKnowledgeReranker local,
            RerankPolicy policy) {
        this(settingsService, local, policy, System::nanoTime);
    }

    DynamicKnowledgeReranker(
            RerankSettingsService settingsService,
            LocalLexicalKnowledgeReranker local,
            RerankPolicy policy,
            LongSupplier nanoTime) {
        this.settingsService = Objects.requireNonNull(
                settingsService, "settingsService");
        this.local = Objects.requireNonNull(local, "local");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
    }

    @Override
    public List<RetrievedChunk> rerank(
            String query,
            List<RetrievedChunk> chunks,
            int topK) {
        return rerankWithResult(query, chunks, topK).chunks();
    }

    @Override
    public RerankExecutionResult rerankWithResult(
            String query,
            List<RetrievedChunk> chunks,
            int topK) {
        Objects.requireNonNull(chunks, "chunks");
        if (topK <= 0) {
            throw new IllegalArgumentException("topK must be > 0");
        }

        List<RetrievedChunk> original = List.copyOf(chunks);
        long executionStarted = nanoTime.getAsLong();
        RerankSettings settings = settingsService.current();
        Mode requested = requestedMode(settings.getProvider());
        if (original.isEmpty()) {
            RerankExecutionResult result = result(
                    List.of(), requested, Mode.NONE,
                    List.of(new Attempt(requested, AttemptOutcome.BYPASSED,
                            FailureType.NONE, 0L)),
                    FallbackReason.NONE, FailureType.NONE,
                    null, SemanticEmptyReason.INPUT_EMPTY,
                    false, null, 0, executionStarted);
            logExecution(result);
            return result;
        }

        List<Attempt> attempts = new ArrayList<>();
        boolean failOpen = Boolean.TRUE.equals(settings.getFailOpen());
        Mode actual;
        FallbackReason fallbackReason = FallbackReason.NONE;
        List<RetrievedChunk> ranked;

        if (requested == Mode.NONE) {
            attempts.add(new Attempt(Mode.NONE, AttemptOutcome.BYPASSED,
                    FailureType.NONE, 0L));
            actual = Mode.NONE;
            ranked = original;
        } else if (requested == Mode.LOCAL) {
            AttemptResult localResult = execute(
                    Mode.LOCAL,
                    () -> local.rerank(query, original, original.size()),
                    original);
            attempts.add(localResult.attempt());
            if (localResult.succeeded()) {
                actual = Mode.LOCAL;
                ranked = localResult.chunks();
            } else if (failOpen) {
                actual = Mode.ORIGINAL;
                ranked = original;
                fallbackReason = FallbackReason.LOCAL_TECHNICAL_FAILURE;
            } else {
                throw failClosed(
                        requested, attempts, localResult.failure(),
                        original.size(), executionStarted);
            }
        } else {
            AttemptResult remoteResult = execute(
                    Mode.REMOTE,
                    () -> settingsService.rerankRemote(
                            query, original, original.size(), settings),
                    original);
            attempts.add(remoteResult.attempt());
            if (remoteResult.succeeded()) {
                actual = Mode.REMOTE;
                ranked = remoteResult.chunks();
            } else if (!failOpen) {
                throw failClosed(
                        requested, attempts, remoteResult.failure(),
                        original.size(), executionStarted);
            } else {
                AttemptResult localResult = execute(
                        Mode.LOCAL,
                        () -> local.rerank(
                                query, original, original.size()),
                        original);
                attempts.add(localResult.attempt());
                if (localResult.succeeded()) {
                    actual = Mode.LOCAL;
                    ranked = localResult.chunks();
                    fallbackReason =
                            FallbackReason.REMOTE_TECHNICAL_FAILURE;
                } else {
                    actual = Mode.ORIGINAL;
                    ranked = original;
                    fallbackReason = FallbackReason
                            .REMOTE_AND_LOCAL_TECHNICAL_FAILURE;
                }
            }
        }

        ProcessedRanking processed = postProcess(actual, ranked, topK);
        RerankExecutionResult result = result(
                processed.chunks(), requested, actual, attempts,
                fallbackReason, FailureType.NONE,
                processed.appliedThreshold(),
                processed.semanticEmptyReason(),
                processed.compositeEnabled(),
                processed.compositeVersion(),
                original.size(), executionStarted);
        logExecution(result);
        return result;
    }

    private AttemptResult execute(
            Mode mode,
            Supplier<List<RetrievedChunk>> action,
            List<RetrievedChunk> original) {
        long started = nanoTime.getAsLong();
        try {
            List<RetrievedChunk> output = Objects.requireNonNull(
                    action.get(), "reranker output");
            validateCompletePermutation(original, output);
            return new AttemptResult(
                    List.copyOf(output),
                    new Attempt(mode, AttemptOutcome.SUCCESS,
                            FailureType.NONE, elapsed(started)),
                    null);
        } catch (RuntimeException failure) {
            FailureType failureType = classify(failure);
            return new AttemptResult(
                    List.of(),
                    new Attempt(mode, AttemptOutcome.TECHNICAL_FAILURE,
                            failureType, elapsed(started)),
                    failure);
        }
    }

    private void validateCompletePermutation(
            List<RetrievedChunk> original,
            List<RetrievedChunk> output) {
        if (output.size() != original.size()
                || !chunkIdCounts(original).equals(chunkIdCounts(output))) {
            throw new RerankExecutionResult.TechnicalFailure(
                    FailureType.INVALID_RESPONSE,
                    "Reranker returned an invalid candidate permutation");
        }
    }

    private Map<Long, Integer> chunkIdCounts(List<RetrievedChunk> chunks) {
        Map<Long, Integer> counts = new HashMap<>();
        for (RetrievedChunk chunk : chunks) {
            if (chunk == null || chunk.chunkId() == null) {
                throw new RerankExecutionResult.TechnicalFailure(
                        FailureType.INVALID_RESPONSE,
                        "Reranker returned a candidate without chunkId");
            }
            counts.merge(chunk.chunkId(), 1, Integer::sum);
        }
        return counts;
    }

    private ProcessedRanking postProcess(
            Mode actual,
            List<RetrievedChunk> ranked,
            int topK) {
        boolean reranked = actual == Mode.REMOTE || actual == Mode.LOCAL;
        List<RetrievedChunk> scored = reranked && policy.composite().enabled()
                ? applyNormalizedComposite(ranked)
                : ranked;
        RerankPolicy.Threshold threshold = thresholdFor(actual);
        Double appliedThreshold = threshold != null && threshold.enabled()
                ? threshold.minimumScore()
                : null;
        List<RetrievedChunk> filtered = appliedThreshold == null
                ? scored
                : scored.stream()
                        .filter(chunk -> chunk.scores().rerankScore() != null
                                && chunk.scores().rerankScore()
                                >= appliedThreshold)
                        .toList();
        SemanticEmptyReason semanticEmpty = appliedThreshold != null
                && !ranked.isEmpty() && filtered.isEmpty()
                ? SemanticEmptyReason.ALL_BELOW_THRESHOLD
                : SemanticEmptyReason.NONE;
        return new ProcessedRanking(
                filtered.stream().limit(topK).toList(),
                appliedThreshold,
                semanticEmpty,
                reranked && policy.composite().enabled(),
                reranked && policy.composite().enabled()
                        ? policy.composite().version()
                        : null);
    }

    private List<RetrievedChunk> applyNormalizedComposite(
            List<RetrievedChunk> ranked) {
        Map<Long, Integer> originalOrder = new LinkedHashMap<>();
        for (int index = 0; index < ranked.size(); index++) {
            originalOrder.putIfAbsent(ranked.get(index).chunkId(), index);
        }
        List<Double> baseScores = ranked.stream()
                .map(chunk -> requireScore(
                        "baseScore", chunk.scores().baseScore()))
                .toList();
        List<Double> rerankScores = ranked.stream()
                .map(chunk -> requireScore(
                        "rerankScore", chunk.scores().rerankScore()))
                .toList();
        List<Double> normalizedBase = normalize(baseScores);
        List<Double> normalizedRerank = normalize(rerankScores);
        double baseWeight = policy.composite().normalizedBaseWeight();
        double rerankWeight = policy.composite().normalizedRerankWeight();

        List<RetrievedChunk> composed = new ArrayList<>(ranked.size());
        for (int index = 0; index < ranked.size(); index++) {
            double finalScore = baseWeight * normalizedBase.get(index)
                    + rerankWeight * normalizedRerank.get(index);
            composed.add(ranked.get(index).withFinalScore(finalScore));
        }
        composed.sort(Comparator
                .comparing(RetrievedChunk::score,
                        Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(chunk -> originalOrder.get(chunk.chunkId()))
                .thenComparing(RetrievedChunk::chunkId));
        return List.copyOf(composed);
    }

    private List<Double> normalize(List<Double> scores) {
        double minimum = scores.stream()
                .mapToDouble(Double::doubleValue).min().orElse(0.0);
        double maximum = scores.stream()
                .mapToDouble(Double::doubleValue).max().orElse(0.0);
        if (Double.compare(minimum, maximum) == 0) {
            return scores.stream().map(ignored -> 1.0).toList();
        }
        double range = maximum - minimum;
        return scores.stream()
                .map(score -> (score - minimum) / range)
                .toList();
    }

    private Double requireScore(String name, Double score) {
        if (score == null || !Double.isFinite(score)) {
            throw new RerankExecutionResult.TechnicalFailure(
                    FailureType.INVALID_RESPONSE,
                    "Composite requires a finite " + name);
        }
        return score;
    }

    private RerankPolicy.Threshold thresholdFor(Mode actual) {
        return switch (actual) {
            case REMOTE -> policy.remoteThreshold();
            case LOCAL -> policy.localThreshold();
            default -> null;
        };
    }

    private RerankExecutionResult.ExecutionFailure failClosed(
            Mode requested,
            List<Attempt> attempts,
            RuntimeException failure,
            int inputCount,
            long executionStarted) {
        FailureType terminalFailure = attempts.get(attempts.size() - 1)
                .failureType();
        RerankExecutionResult result = result(
                List.of(), requested, Mode.UNKNOWN, attempts,
                FallbackReason.NONE, terminalFailure,
                null, SemanticEmptyReason.NONE,
                false, null, inputCount, executionStarted);
        logExecution(result);
        return new RerankExecutionResult.ExecutionFailure(
                "Rerank execution failed", result, failure);
    }

    private RerankExecutionResult result(
            List<RetrievedChunk> chunks,
            Mode requested,
            Mode actual,
            List<Attempt> attempts,
            FallbackReason fallbackReason,
            FailureType terminalFailure,
            Double appliedThreshold,
            SemanticEmptyReason semanticEmptyReason,
            boolean compositeEnabled,
            String compositeVersion,
            int inputCount,
            long executionStarted) {
        return new RerankExecutionResult(
                chunks, requested, actual, attempts, fallbackReason,
                terminalFailure, appliedThreshold, semanticEmptyReason,
                compositeEnabled, compositeVersion, inputCount,
                elapsed(executionStarted));
    }

    private Mode requestedMode(String provider) {
        if (provider == null) {
            throw new RerankExecutionResult.TechnicalFailure(
                    FailureType.CONFIGURATION,
                    "Rerank provider is not configured");
        }
        return switch (provider.trim().toLowerCase()) {
            case "none" -> Mode.NONE;
            case "local" -> Mode.LOCAL;
            case "siliconflow", "remote" -> Mode.REMOTE;
            default -> throw new RerankExecutionResult.TechnicalFailure(
                    FailureType.CONFIGURATION,
                    "Unsupported rerank provider");
        };
    }

    private FailureType classify(RuntimeException failure) {
        if (failure instanceof RerankExecutionResult.TechnicalFailure typed) {
            return typed.failureType();
        }
        Throwable current = failure;
        while (current != null) {
            if (current instanceof HttpTimeoutException
                    || current instanceof SocketTimeoutException
                    || current instanceof TimeoutException) {
                return FailureType.TIMEOUT;
            }
            current = current.getCause();
        }
        return FailureType.EXECUTION_ERROR;
    }

    private long elapsed(long started) {
        return Math.max(0L, nanoTime.getAsLong() - started);
    }

    private void logExecution(RerankExecutionResult result) {
        String message = "Rerank execution: requested={}, actual={}, "
                + "degraded={}, fallbackReason={}, terminalFailure={}, "
                + "attempts={}, inputCount={}, outputCount={}, "
                + "thresholdApplied={}, semanticEmpty={}, "
                + "compositeEnabled={}, compositeVersion={}, latencyNanos={}";
        if (result.terminalFailureType() != FailureType.NONE
                || result.degraded()) {
            log.warn(message,
                    result.requestedReranker(), result.actualReranker(),
                    result.degraded(), result.fallbackReason(),
                    result.terminalFailureType(), result.attempts(),
                    result.inputCandidateCount(),
                    result.outputCandidateCount(), result.thresholdApplied(),
                    result.semanticEmptyReason(), result.compositeEnabled(),
                    result.compositeVersion(), result.latencyNanos());
        } else {
            log.info(message,
                    result.requestedReranker(), result.actualReranker(),
                    false, result.fallbackReason(),
                    result.terminalFailureType(), result.attempts(),
                    result.inputCandidateCount(),
                    result.outputCandidateCount(), result.thresholdApplied(),
                    result.semanticEmptyReason(), result.compositeEnabled(),
                    result.compositeVersion(), result.latencyNanos());
        }
    }

    private record AttemptResult(
            List<RetrievedChunk> chunks,
            Attempt attempt,
            RuntimeException failure
    ) {
        private boolean succeeded() {
            return failure == null;
        }
    }

    private record ProcessedRanking(
            List<RetrievedChunk> chunks,
            Double appliedThreshold,
            SemanticEmptyReason semanticEmptyReason,
            boolean compositeEnabled,
            String compositeVersion
    ) {
    }
}
