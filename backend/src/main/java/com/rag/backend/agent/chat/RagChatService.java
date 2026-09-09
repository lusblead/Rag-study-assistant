package com.rag.backend.agent.chat;

import com.rag.backend.agent.evidence.AnswerabilityDecision;
import com.rag.backend.agent.evidence.EvidenceConstraints;
import com.rag.backend.agent.evidence.EvidenceDecisionInput;
import com.rag.backend.agent.evidence.EvidenceDecisionPolicy;
import com.rag.backend.agent.evidence.EvidenceDecisionProperties;
import com.rag.backend.agent.evidence.EvidenceDecisionRenderer;
import com.rag.backend.agent.evidence.EvidenceDecisionResult;
import com.rag.backend.agent.evidence.EvidenceTextAnalyzer;
import com.rag.backend.agent.evidence.RuleBasedEvidenceDecisionPolicy;
import com.rag.backend.agent.grounding.CitationCatalog;
import com.rag.backend.agent.grounding.GroundedAnswerGenerator;
import com.rag.backend.agent.grounding.GroundedAnswerResult;
import com.rag.backend.agent.grounding.GroundingDiagnostics;
import com.rag.backend.agent.history.ChatMessage;
import com.rag.backend.agent.materials.SessionMaterialService;
import com.rag.backend.agent.materials.MaterialStatus;
import com.rag.backend.agent.history.ChatHistoryService;
import com.rag.backend.agent.llm.ChatClient;
import com.rag.backend.agent.model.RagChatMetadata;
import com.rag.backend.agent.model.RagChatResponse;
import com.rag.backend.agent.model.RagChatStreamResponse;
import com.rag.backend.agent.model.RagPromptContext;
import com.rag.backend.agent.prompt.PromptTemplate;
import com.rag.backend.agent.retrieval.KnowledgeRetriever;
import com.rag.backend.agent.retrieval.RetrievalExecutionResult;
import com.rag.backend.agent.retrieval.RetrievedChunk;
import com.rag.backend.observability.trace.TraceCarrier;
import com.rag.backend.observability.trace.TraceContextService;
import com.rag.backend.observability.trace.TraceSpan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.SignalType;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

@Service
// 编排 RAG 问答、检索上下文、调用模型并保存会话历史。
public class RagChatService {
    private static final Logger log = LoggerFactory.getLogger(
            RagChatService.class);
    private final int topK;
    private final int historyLimit;
    private final KnowledgeRetriever knowledgeRetriever;
    private final PromptTemplate<RagPromptContext> promptTemplate;
    private final ChatClient chatClient;
    private final ChatHistoryService chatHistoryService;
    private final EvidenceDecisionPolicy evidenceDecisionPolicy;
    private final EvidenceDecisionRenderer evidenceDecisionRenderer;
    private final GroundedAnswerGenerator groundedAnswerGenerator;
    private final TraceContextService traces;
    private SessionMaterialService materials;

    @Autowired
    public void setMaterials(SessionMaterialService materials) {
        this.materials = Objects.requireNonNull(materials);
    }

    @Autowired
    public RagChatService(KnowledgeRetriever knowledgeRetriever,
                          PromptTemplate<RagPromptContext> promptTemplate,
                          ChatClient chatClient,
                          ChatHistoryService chatHistoryService,
                          EvidenceDecisionPolicy evidenceDecisionPolicy,
                          EvidenceDecisionRenderer evidenceDecisionRenderer,
                          GroundedAnswerGenerator groundedAnswerGenerator,
                          @Value("${rag.top-k:5}") int topK,
                          @Value("${rag.history-limit:8}") int historyLimit,
                          TraceContextService traces) {
        this.knowledgeRetriever = knowledgeRetriever;
        this.promptTemplate = promptTemplate;
        this.chatClient = chatClient;
        this.chatHistoryService = chatHistoryService;
        this.evidenceDecisionPolicy = evidenceDecisionPolicy;
        this.evidenceDecisionRenderer = evidenceDecisionRenderer;
        this.groundedAnswerGenerator = groundedAnswerGenerator;
        this.topK = topK;
        this.historyLimit = historyLimit;
        this.traces = Objects.requireNonNull(traces, "traces");
    }

    /** 保留现有测试与手工装配入口。 */
    public RagChatService(KnowledgeRetriever knowledgeRetriever,
                          PromptTemplate<RagPromptContext> promptTemplate,
                          ChatClient chatClient,
                          ChatHistoryService chatHistoryService,
                          EvidenceDecisionPolicy evidenceDecisionPolicy,
                          EvidenceDecisionRenderer evidenceDecisionRenderer,
                          GroundedAnswerGenerator groundedAnswerGenerator,
                          int topK,
                          int historyLimit) {
        this(knowledgeRetriever, promptTemplate, chatClient,
                chatHistoryService, evidenceDecisionPolicy,
                evidenceDecisionRenderer, groundedAnswerGenerator,
                topK, historyLimit, new TraceContextService());
    }

    /** 保留已有内存测试和手工装配入口；使用与生产相同的默认保守策略。 */
    public RagChatService(KnowledgeRetriever knowledgeRetriever,
                          PromptTemplate<RagPromptContext> promptTemplate,
                          ChatClient chatClient,
                          ChatHistoryService chatHistoryService,
                          int topK,
                          int historyLimit) {
        this(
                knowledgeRetriever,
                promptTemplate,
                chatClient,
                chatHistoryService,
                new RuleBasedEvidenceDecisionPolicy(
                        new EvidenceDecisionProperties(),
                        new EvidenceTextAnalyzer()),
                new EvidenceDecisionRenderer(),
                GroundedAnswerGenerator.disabled(),
                topK,
                historyLimit);
    }

    /** 保留 Step 3.2 测试装配入口；默认不启用未经校准的生成后校验。 */
    public RagChatService(KnowledgeRetriever knowledgeRetriever,
                          PromptTemplate<RagPromptContext> promptTemplate,
                          ChatClient chatClient,
                          ChatHistoryService chatHistoryService,
                          EvidenceDecisionPolicy evidenceDecisionPolicy,
                          EvidenceDecisionRenderer evidenceDecisionRenderer,
                          int topK,
                          int historyLimit) {
        this(
                knowledgeRetriever,
                promptTemplate,
                chatClient,
                chatHistoryService,
                evidenceDecisionPolicy,
                evidenceDecisionRenderer,
                GroundedAnswerGenerator.disabled(),
                topK,
                historyLimit);
    }

    public RagChatResponse chat(Long courseId, String question) {
        return chat(courseId, null, question);
    }

    public RagChatResponse chat(Long courseId, Long sessionId, String question) {
        try (TraceSpan span = traces.startSpan("chat.turn")) {
            try {
                RagChatResponse response = chatWithinTrace(
                        courseId, sessionId, question);
                span.result(response.metadata().evidenceDecision()
                        .decision().name().toLowerCase());
                return response;
            } catch (RuntimeException | Error error) {
                span.error(error);
                throw error;
            }
        }
    }

    private RagChatResponse chatWithinTrace(
            Long courseId, Long sessionId, String question) {
        PreparedTurn turn = prepare(courseId, sessionId, question);
        String answer;
        RagChatMetadata metadata = turn.metadata();
        if (turn.decision().decision() == AnswerabilityDecision.ANSWER) {
            CitationCatalog catalog = CitationCatalog.from(
                    turn.usableChunks());
            RagPromptContext context = new RagPromptContext(
                    question, catalog, turn.history());
            String prompt = promptTemplate.render(context);
            GroundedAnswerResult generated = groundedAnswerGenerator.generate(
                    prompt, catalog, chatClient::call);
            answer = generated.answer();
            metadata = metadata.withGrounding(generated.diagnostics());
            logGrounding(generated.diagnostics());
        } else {
            answer = evidenceDecisionRenderer.render(turn.decision());
        }
        completeTurn(courseId, turn, question, answer, metadata);
        return new RagChatResponse(
                turn.sessionId(), answer, turn.usableChunks(), metadata);
    }

    public Flux<String> stream(Long courseId, String question) {
        return stream(courseId, null, question).stream();
    }

    public RagChatStreamResponse stream(Long courseId, Long sessionId, String question) {
        try (TraceSpan span = traces.startSpan("chat.prepare")) {
            try {
                RagChatStreamResponse response = streamWithinTrace(
                        courseId, sessionId, question);
                span.result(response.metadata().evidenceDecision()
                        .decision().name().toLowerCase());
                return response;
            } catch (RuntimeException | Error error) {
                span.error(error);
                throw error;
            }
        }
    }

    private RagChatStreamResponse streamWithinTrace(
            Long courseId, Long sessionId, String question) {
        PreparedTurn turn = prepare(courseId, sessionId, question);
        Flux<String> source;
        RagChatMetadata metadata = turn.metadata();
        if (turn.decision().decision() == AnswerabilityDecision.ANSWER) {
            CitationCatalog catalog = CitationCatalog.from(
                    turn.usableChunks());
            RagPromptContext context = new RagPromptContext(
                    question, catalog, turn.history());
            String prompt = promptTemplate.render(context);
            if (groundedAnswerGenerator.enabled()) {
                GroundedAnswerResult generated = groundedAnswerGenerator.generate(
                        prompt,
                        catalog,
                        repairPrompt -> collectStream(
                                chatClient.stream(repairPrompt)));
                source = Flux.just(generated.answer());
                metadata = metadata.withGrounding(generated.diagnostics());
                logGrounding(generated.diagnostics());
            } else {
                TraceCarrier llmCarrier;
                try (TraceSpan llm = traces.startSpan("chat.llm")) {
                    try {
                        source = chatClient.stream(prompt);
                        llmCarrier = llm.carrier();
                        llm.result("dispatched");
                    } catch (RuntimeException | Error error) {
                        llm.error(error);
                        throw error;
                    }
                }
                source = observeStreamTerminal(source, llmCarrier);
                GroundingDiagnostics disabled = GroundingDiagnostics.disabled(
                        catalog);
                metadata = metadata.withGrounding(disabled);
                logGrounding(disabled);
            }
        } else {
            source = Flux.just(evidenceDecisionRenderer.render(
                    turn.decision()));
        }
        if (materials != null) {
            RagChatMetadata finalMetadata = metadata;
            TraceCarrier completionCarrier = traces.capture(null);
            // No unvalidated token can escape after withdrawal/retention expiry during generation.
            Flux<String> validated = source.collectList().flatMapMany(parts -> {
                String completeAnswer = String.join("", parts);
                completeTurn(courseId, turn, question, completeAnswer, finalMetadata, completionCarrier);
                return Flux.just(completeAnswer);
            });
            return new RagChatStreamResponse(turn.sessionId(), turn.usableChunks(), metadata, validated);
        }
        StringBuilder answer = new StringBuilder();
        TraceCarrier completionCarrier = traces.capture(null);
        Flux<String> stream = source
                .doOnNext(answer::append)
                .doOnComplete(() -> appendCompletedTurn(
                        completionCarrier, turn.sessionId(), question,
                        answer.toString()));
        return new RagChatStreamResponse(
                turn.sessionId(),
                turn.usableChunks(),
                metadata,
                stream);
    }

    private String collectStream(Flux<String> stream) {
        List<String> chunks = stream.collectList().block();
        if (chunks == null) {
            throw new IllegalStateException(
                    "LLM stream completed without a result container");
        }
        return String.join("", chunks);
    }

    private PreparedTurn prepare(
            Long courseId,
            Long sessionId,
            String question) {
        Long effectiveSessionId = chatHistoryService.resolveSession(
                sessionId, courseId, question);
        List<ChatMessage> history = chatHistoryService.recentMessages(
                effectiveSessionId, historyLimit);
        MaterialStatus materialStatus = null;
        RetrievalExecutionResult retrieval;
        if (materials != null) {
            try (SessionMaterialService.ReadHandle read = materials.acquire(effectiveSessionId, courseId)) {
                materialStatus = read.status();
                retrieval = traces.inSpan("chat.retrieval", () -> knowledgeRetriever.retrieveInScope(
                        read.scope(), retrievalQuery(question, history), topK));
                read.validate();
            }
        } else {
            retrieval = traces.inSpan(
                "chat.retrieval", () -> knowledgeRetriever.retrieveWithResult(
                        courseId,
                        retrievalQuery(question, history),
                        topK));
        }
        RetrievalExecutionResult frozenRetrieval = retrieval;
        EvidenceDecisionResult decision = traces.inSpan(
                "chat.policy", () -> evidenceDecisionPolicy.decide(
                        new EvidenceDecisionInput(
                        question,
                        history,
                        frozenRetrieval.chunks(),
                        EvidenceConstraints.courseChat(),
                        frozenRetrieval.diagnostics())));
        List<RetrievedChunk> usableChunks = usableChunks(
                retrieval.chunks(), decision);
        RagChatMetadata metadata = new RagChatMetadata(
                decision, retrieval.diagnostics()).withMaterials(materialStatus);
        logDecision(decision, retrieval);
        return new PreparedTurn(
                effectiveSessionId,
                history,
                usableChunks,
                decision,
                metadata);
    }

    private List<RetrievedChunk> usableChunks(
            List<RetrievedChunk> chunks,
            EvidenceDecisionResult decision) {
        if (decision.decision() != AnswerabilityDecision.ANSWER) {
            return List.of();
        }
        Map<Long, RetrievedChunk> chunksById = new HashMap<>();
        for (RetrievedChunk chunk : chunks) {
            if (chunk != null && chunk.chunkId() != null) {
                RetrievedChunk previous = chunksById.putIfAbsent(
                        chunk.chunkId(), chunk);
                if (previous != null) {
                    throw new IllegalStateException(
                            "Retrieved evidence contains duplicate chunk IDs");
                }
            }
        }
        List<RetrievedChunk> usable = decision.usableEvidenceIds().stream()
                .map(chunksById::get)
                .toList();
        if (usable.isEmpty() || usable.stream().anyMatch(Objects::isNull)) {
            throw new IllegalStateException(
                    "Evidence policy returned IDs outside retrieved evidence");
        }
        return usable;
    }

    private void completeTurn(Long courseId, PreparedTurn turn, String question, String answer,
            RagChatMetadata metadata) {
        completeTurn(courseId, turn, question, answer, metadata, traces.capture(null));
    }

    private void completeTurn(Long courseId, PreparedTurn turn, String question, String answer,
            RagChatMetadata metadata, TraceCarrier carrier) {
        if (materials == null) {
            appendCompletedTurn(turn.sessionId(), question, answer);
            return;
        }
        try (TraceSpan span = traces.continueOrStart(carrier, "chat.history.write", carrier.correlationId())) {
            try {
                materials.complete(turn.sessionId(), courseId, () ->
                        chatHistoryService.appendTurn(turn.sessionId(), question, answer, turn.usableChunks(), metadata));
                span.result("success");
            } catch (RuntimeException | Error error) {
                span.error(error);
                throw error;
            }
        }
    }

    private void appendCompletedTurn(
            Long sessionId,
            String question,
            String answer) {
        appendCompletedTurn(
                traces.capture(null), sessionId, question, answer);
    }

    private void appendCompletedTurn(
            TraceCarrier carrier,
            Long sessionId,
            String question,
            String answer) {
        try (TraceSpan span = traces.continueOrStart(
                carrier, "chat.history.write", carrier.correlationId())) {
            try {
                chatHistoryService.appendMessage(
                        sessionId, ChatMessage.ROLE_USER, question);
                chatHistoryService.appendMessage(
                        sessionId, ChatMessage.ROLE_ASSISTANT, answer);
                span.result("success");
            } catch (RuntimeException | Error error) {
                span.error(error);
                throw error;
            }
        }
    }

    private Flux<String> observeStreamTerminal(
            Flux<String> source,
            TraceCarrier carrier) {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        return source
                .doOnError(failure::set)
                .doFinally(signal -> recordStreamTerminal(
                        carrier, signal, failure.get()));
    }

    private void recordStreamTerminal(
            TraceCarrier carrier,
            SignalType signal,
            Throwable failure) {
        try (TraceSpan span = traces.continueOrStart(
                carrier, "chat.llm.stream", carrier.correlationId())) {
            if (signal == SignalType.ON_ERROR) {
                span.error(failure);
            } else if (signal == SignalType.CANCEL) {
                span.result("cancelled");
            } else if (signal == SignalType.ON_COMPLETE) {
                span.result("completed");
            } else {
                span.result(signal.name().toLowerCase());
            }
        }
    }

    private void logDecision(
            EvidenceDecisionResult decision,
            RetrievalExecutionResult retrieval) {
        log.info("Evidence decision: decision={}, reasonCode={}, "
                        + "policyVersion={}, retrievedCount={}, eligibleCount={}, "
                        + "thresholdConfigured={}, thresholdScoreKind={}, "
                        + "retrievalDegraded={}, retrievalEmptyReason={}, "
                        + "requestedReranker={}, actualReranker={}, "
                        + "rerankFallbackReason={}",
                decision.decision(),
                decision.reasonCode(),
                decision.policyVersion(),
                decision.observedSignals().retrievedCount(),
                decision.observedSignals().eligibleCount(),
                decision.observedSignals().thresholdConfigured(),
                decision.observedSignals().thresholdScoreKind(),
                retrieval.diagnostics().degraded(),
                retrieval.diagnostics().emptyReason(),
                retrieval.diagnostics().rerank().requestedReranker(),
                retrieval.diagnostics().rerank().actualReranker(),
                retrieval.diagnostics().rerank().fallbackReason());
    }

    private void logGrounding(GroundingDiagnostics diagnostics) {
        log.info("Grounding execution: status={}, attempts={}, "
                        + "citationValid={}, citationCoverage={}, "
                        + "supportedClaims={}, unsupportedClaims={}, "
                        + "contradictedClaims={}, uncertainClaims={}, "
                        + "failureReason={}, validatorVersion={}, "
                        + "semanticJudgeCalibrationId={}",
                diagnostics.status(),
                diagnostics.generationAttempts(),
                diagnostics.citationValid(),
                diagnostics.citationCoverage(),
                diagnostics.supportedClaims(),
                diagnostics.unsupportedClaims(),
                diagnostics.contradictedClaims(),
                diagnostics.uncertainClaims(),
                diagnostics.failureReason(),
                diagnostics.validatorVersion(),
                diagnostics.semanticJudgeCalibrationId());
    }

    private String retrievalQuery(String question, List<ChatMessage> history) {
        if (history == null || history.isEmpty()) {
            return question;
        }
        String recentUserContext = history.stream()
                .filter(message -> ChatMessage.ROLE_USER.equals(message.getRole()))
                .map(ChatMessage::getContent)
                .reduce((previous, current) -> previous + "\n" + current)
                .orElse("");
        if (recentUserContext.isBlank()) {
            return question;
        }
        return recentUserContext + "\n" + question;
    }

    private record PreparedTurn(
            Long sessionId,
            List<ChatMessage> history,
            List<RetrievedChunk> usableChunks,
            EvidenceDecisionResult decision,
            RagChatMetadata metadata
    ) {
    }
}
