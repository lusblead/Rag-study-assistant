package com.rag.backend.agent.retrieval;

import com.rag.backend.agent.embedding.EmbeddingClient;
import com.rag.backend.agent.retrieval.diversity.DiversitySelectionResult;
import com.rag.backend.agent.retrieval.diversity.DiversitySelector;
import com.rag.backend.agent.retrieval.fusion.RrfFusion;
import com.rag.backend.agent.rerank.KnowledgeReranker;
import com.rag.backend.agent.rerank.RerankExecutionResult;
import com.rag.backend.agent.repository.KnowledgeChunkRepository;
import com.rag.backend.agent.vector.VectorStoreService;
import com.rag.backend.document.DocumentMapper;
import com.rag.backend.ingestionlab.retrieval.ActiveVersionResolver;
import com.rag.backend.ingestionlab.retrieval.VersionedVectorHit;
import com.rag.backend.ingestionlab.retrieval.VersionedVectorSearch;
import com.rag.backend.observability.trace.TraceContextService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.HashMap;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

@Service
/**
 * 使用当前 ACTIVE 文档版本做向量检索和重排。
 *
 * 过滤执行两次：Milvus 查询只接收 activeVersionIds；MySQL 回表后再核对 Chunk 的
 * documentVersionId，防止查询期间发生 active 切换或远端元数据串版。
 */
public class MilvusKnowledgeRetriever implements KnowledgeRetriever {
    private static final Logger log = LoggerFactory.getLogger(
            MilvusKnowledgeRetriever.class);

    private final ActiveVersionResolver activeVersions;
    private final CandidateSource denseCandidateSource;
    private final DualCandidateSourceCollector hybridCollector;
    private final RrfFusion rrfFusion;
    private final KnowledgeReranker reranker;
    private final DiversitySelector diversitySelector;
    private final int denseSourceK;
    private final int lexicalSourceK;
    private final int candidateK;
    private final boolean hybridEnabled;
    private final TraceContextService traces;

    @Autowired
    public MilvusKnowledgeRetriever(
            ActiveVersionResolver activeVersions,
            DenseCandidateSource denseCandidateSource,
            MySqlLexicalCandidateSource lexicalCandidateSource,
            KnowledgeReranker reranker,
            DiversitySelector diversitySelector,
            @Value("${rag.source-k.dense:20}") int denseSourceK,
            @Value("${rag.source-k.lexical:20}") int lexicalSourceK,
            @Value("${rag.candidate-k:20}") int candidateK,
            @Value("${rag.hybrid.enabled:true}") boolean hybridEnabled,
            @Value("${rag.hybrid.rrf-k:60}") int rrfK,
            @Value("${rag.hybrid.dense-weight:1.0}") double denseWeight,
            @Value("${rag.hybrid.lexical-weight:1.0}") double lexicalWeight,
            TraceContextService traces) {
        this(activeVersions, (CandidateSource) denseCandidateSource,
                (CandidateSource) lexicalCandidateSource,
                reranker, denseSourceK, lexicalSourceK, candidateK,
                hybridEnabled, rrfK, denseWeight, lexicalWeight,
                diversitySelector, traces);
    }

    /** 保留现有测试与手工装配入口。 */
    public MilvusKnowledgeRetriever(
            ActiveVersionResolver activeVersions,
            DenseCandidateSource denseCandidateSource,
            MySqlLexicalCandidateSource lexicalCandidateSource,
            KnowledgeReranker reranker,
            DiversitySelector diversitySelector,
            int denseSourceK,
            int lexicalSourceK,
            int candidateK,
            boolean hybridEnabled,
            int rrfK,
            double denseWeight,
            double lexicalWeight) {
        this(activeVersions, denseCandidateSource, lexicalCandidateSource,
                reranker, diversitySelector, denseSourceK, lexicalSourceK,
                candidateK, hybridEnabled, rrfK, denseWeight, lexicalWeight,
                new TraceContextService());
    }

    /** 保留现有测试与手工装配使用的完整构造器。 */
    public MilvusKnowledgeRetriever(
            EmbeddingClient embeddingClient,
            ActiveVersionResolver activeVersions,
            VersionedVectorSearch vectorSearch,
            KnowledgeChunkRepository chunkRepository,
            DocumentMapper documentMapper,
            KnowledgeReranker reranker,
            double similarityThreshold,
            int candidateK) {
        this(activeVersions,
                new DenseCandidateSource(
                        embeddingClient, vectorSearch, chunkRepository,
                        documentMapper, similarityThreshold, true),
                reranker,
                DiversitySelector.disabled(),
                candidateK);
    }

    /**
     * 旧闭环单测兼容构造器。它仅用于原有内存 `DocumentIngestService` 的行为回归，
     * 不代表生产 active-version 检索；新测试应使用上面的完整构造器。
     */
    public MilvusKnowledgeRetriever(
            EmbeddingClient embeddingClient,
            VectorStoreService legacyVectorStore,
            KnowledgeChunkRepository chunkRepository,
            KnowledgeReranker reranker,
            double similarityThreshold,
            int candidateK) {
        this(
                ignored -> Set.of(0L),
                new DenseCandidateSource(
                        embeddingClient,
                        (courseId, ignored, queryVector, topK) ->
                                legacyVectorStore.search(
                                                courseId, queryVector, topK)
                                        .stream()
                                        .map(result -> new VersionedVectorHit(
                                                result.chunkId(), 0L,
                                                result.score() == null
                                                        ? 0.0
                                                        : result.score()))
                                        .toList(),
                        chunkRepository,
                        null,
                        similarityThreshold,
                        false),
                reranker,
                DiversitySelector.disabled(),
                candidateK);
    }

    private MilvusKnowledgeRetriever(
            ActiveVersionResolver activeVersions,
            CandidateSource denseCandidateSource,
            KnowledgeReranker reranker,
            DiversitySelector diversitySelector,
            int candidateK) {
        this(activeVersions, denseCandidateSource, null, reranker,
                candidateK, candidateK, candidateK,
                false, 60, 1.0, 1.0, diversitySelector);
    }

    MilvusKnowledgeRetriever(
            ActiveVersionResolver activeVersions,
            CandidateSource denseCandidateSource,
            CandidateSource lexicalCandidateSource,
            KnowledgeReranker reranker,
            int candidateK,
            boolean hybridEnabled,
            int rrfK,
            double denseWeight,
            double lexicalWeight) {
        this(activeVersions, denseCandidateSource, lexicalCandidateSource,
                reranker, candidateK, candidateK, candidateK,
                hybridEnabled, rrfK, denseWeight, lexicalWeight,
                DiversitySelector.disabled());
    }

    MilvusKnowledgeRetriever(
            ActiveVersionResolver activeVersions,
            CandidateSource denseCandidateSource,
            CandidateSource lexicalCandidateSource,
            KnowledgeReranker reranker,
            int denseSourceK,
            int lexicalSourceK,
            int candidateK,
            boolean hybridEnabled,
            int rrfK,
            double denseWeight,
            double lexicalWeight) {
        this(activeVersions, denseCandidateSource, lexicalCandidateSource,
                reranker, denseSourceK, lexicalSourceK, candidateK,
                hybridEnabled, rrfK, denseWeight, lexicalWeight,
                DiversitySelector.disabled());
    }

    MilvusKnowledgeRetriever(
            ActiveVersionResolver activeVersions,
            CandidateSource denseCandidateSource,
            CandidateSource lexicalCandidateSource,
            KnowledgeReranker reranker,
            int denseSourceK,
            int lexicalSourceK,
            int candidateK,
            boolean hybridEnabled,
            int rrfK,
            double denseWeight,
            double lexicalWeight,
            DiversitySelector diversitySelector) {
        this(activeVersions, denseCandidateSource, lexicalCandidateSource,
                reranker, denseSourceK, lexicalSourceK, candidateK,
                hybridEnabled, rrfK, denseWeight, lexicalWeight,
                diversitySelector, new TraceContextService());
    }

    MilvusKnowledgeRetriever(
            ActiveVersionResolver activeVersions,
            CandidateSource denseCandidateSource,
            CandidateSource lexicalCandidateSource,
            KnowledgeReranker reranker,
            int denseSourceK,
            int lexicalSourceK,
            int candidateK,
            boolean hybridEnabled,
            int rrfK,
            double denseWeight,
            double lexicalWeight,
            DiversitySelector diversitySelector,
            TraceContextService traces) {
        this.activeVersions = activeVersions;
        this.denseCandidateSource = denseCandidateSource;
        this.hybridCollector = lexicalCandidateSource == null
                ? null
                : new DualCandidateSourceCollector(
                        denseCandidateSource, lexicalCandidateSource);
        if (hybridEnabled && this.hybridCollector == null) {
            throw new IllegalArgumentException(
                    "Hybrid retrieval requires a lexical candidate source");
        }
        this.rrfFusion = new RrfFusion(rrfK, denseWeight, lexicalWeight);
        this.reranker = reranker;
        this.diversitySelector = diversitySelector;
        validateConfiguredLimits(denseSourceK, lexicalSourceK, candidateK);
        this.denseSourceK = denseSourceK;
        this.lexicalSourceK = lexicalSourceK;
        this.candidateK = candidateK;
        this.hybridEnabled = hybridEnabled;
        this.traces = traces;
    }

    @Override
    public List<RetrievedChunk> retrieve(Long courseId, String query, int topK) {
        return retrieveWithResult(courseId, query, topK).chunks();
    }

    @Override
    public RetrievalExecutionResult retrieveWithResult(
            Long courseId,
            String query,
            int topK) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("query must not be blank");
        }
        validateLimits(topK);
        Set<Long> activeVersionIds = traces.inSpan(
                "chat.retrieval.scope",
                () -> activeVersions.forCourse(courseId));
        if (activeVersionIds.isEmpty()) {
            DiversitySelectionResult emptySelection = traces.inSpan(
                    "chat.diversity",
                    () -> diversitySelector.select(List.of(), topK));
            return new RetrievalExecutionResult(
                    emptySelection.chunks(),
                    new RetrievalDiagnostics(
                            false,
                            RetrievalDiagnostics.EmptyReason.NO_ACTIVE_VERSION,
                            List.of(),
                            RetrievalDiagnostics.Rerank.unobserved(0),
                            emptySelection.diagnostics()));
        }

        RetrievalScope scope = new RetrievalScope(courseId, activeVersionIds);
        if (hybridEnabled) {
            return retrieveHybrid(scope, query, topK);
        }

        long denseStarted = System.nanoTime();
        CandidateBatch denseBatch = traces.inSpan(
                "chat.retrieval.dense",
                () -> denseCandidateSource.retrieve(
                        scope, query, denseSourceK));
        long denseLatency = Math.max(0L, System.nanoTime() - denseStarted);
        if (denseBatch.source() != CandidateSourceType.DENSE) {
            throw new IllegalStateException("Dense source returned a non-dense batch");
        }
        List<RetrievedChunk> candidates = denseBatch.candidates().stream()
                .map(candidate -> candidate.chunk()
                        .withDenseScore(candidate.rawScore()))
                .limit(candidateK)
                .toList();

        // 不再回退到按 courseId 查询全部 Chunk；空候选是真实、可观察的空结果。
        RetrievalDiagnostics.Source source = new RetrievalDiagnostics.Source(
                CandidateSourceType.DENSE,
                true,
                null,
                denseBatch.candidates().size(),
                denseLatency);
        return applyRerank(query, candidates, topK, List.of(source));
    }

    private RetrievalExecutionResult retrieveHybrid(
            RetrievalScope scope,
            String query,
            int topK) {
        CandidateCollectionResult collected;
        try {
            collected = traces.inSpan(
                    "chat.retrieval.sources",
                    () -> hybridCollector.collect(
                            scope, query, denseSourceK, lexicalSourceK));
        } catch (CandidateCollectionException failure) {
            logDegradation(failure.diagnostics(), true);
            throw failure;
        }
        if (collected.degraded()) {
            logDegradation(collected.diagnostics(), false);
        }

        List<RetrievalCandidate> fused = traces.inSpan(
                "chat.fusion", () -> rrfFusion.fuse(collected.batches()));
        List<RetrievedChunk> candidates = fused
                .stream()
                .map(RetrievalCandidate::chunk)
                .limit(candidateK)
                .toList();
        List<RetrievalDiagnostics.Source> sources = collected.diagnostics()
                .stream()
                .map(RetrievalDiagnostics.Source::from)
                .toList();
        return applyRerank(query, candidates, topK, sources);
    }

    private RetrievalExecutionResult applyRerank(
            String query,
            List<RetrievedChunk> frozenCandidates,
            int topK,
            List<RetrievalDiagnostics.Source> sources) {
        int rerankLimit = diversitySelector.enabled()
                && !frozenCandidates.isEmpty()
                ? frozenCandidates.size()
                : topK;
        RerankExecutionResult execution = traces.inSpan(
                "chat.rerank", () -> reranker.rerankWithResult(
                        query, frozenCandidates, rerankLimit));
        validateRerankSubset(frozenCandidates, execution.chunks());
        DiversitySelectionResult selection = traces.inSpan(
                "chat.diversity", () -> diversitySelector.select(
                        execution.chunks(), topK));
        validateRerankSubset(execution.chunks(), selection.chunks());
        List<RetrievedChunk> chunks = selection.chunks();
        boolean sourceDegraded = sources.stream()
                .anyMatch(source -> !source.succeeded());
        RetrievalDiagnostics.EmptyReason emptyReason = emptyReason(
                frozenCandidates, chunks, execution.semanticEmptyReason());
        return new RetrievalExecutionResult(
                chunks,
                new RetrievalDiagnostics(
                        sourceDegraded || execution.degraded(),
                        emptyReason,
                        sources,
                        RetrievalDiagnostics.Rerank.from(execution),
                        selection.diagnostics()));
    }

    private RetrievalDiagnostics.EmptyReason emptyReason(
            List<RetrievedChunk> input,
            List<RetrievedChunk> output,
            RerankExecutionResult.SemanticEmptyReason rerankEmptyReason) {
        if (!output.isEmpty()) {
            return RetrievalDiagnostics.EmptyReason.NONE;
        }
        if (input.isEmpty()
                || rerankEmptyReason
                == RerankExecutionResult.SemanticEmptyReason.INPUT_EMPTY) {
            return RetrievalDiagnostics.EmptyReason.NO_SOURCE_CANDIDATE;
        }
        if (rerankEmptyReason
                == RerankExecutionResult.SemanticEmptyReason.ALL_BELOW_THRESHOLD) {
            return RetrievalDiagnostics.EmptyReason
                    .ALL_BELOW_RERANK_THRESHOLD;
        }
        return RetrievalDiagnostics.EmptyReason.UNKNOWN;
    }

    private void validateRerankSubset(
            List<RetrievedChunk> input,
            List<RetrievedChunk> output) {
        Map<Long, Integer> remaining = new HashMap<>();
        for (RetrievedChunk chunk : input) {
            if (chunk == null || chunk.chunkId() == null) {
                throw new IllegalStateException(
                        "Rerank input contains a candidate without chunkId");
            }
            remaining.merge(chunk.chunkId(), 1, Integer::sum);
        }
        for (RetrievedChunk chunk : output) {
            if (chunk == null || chunk.chunkId() == null) {
                throw new IllegalStateException(
                        "Rerank output contains a candidate without chunkId");
            }
            int count = remaining.getOrDefault(chunk.chunkId(), 0);
            if (count <= 0) {
                throw new IllegalStateException(
                        "Rerank cannot add candidates outside CandidateK");
            }
            if (count == 1) {
                remaining.remove(chunk.chunkId());
            } else {
                remaining.put(chunk.chunkId(), count - 1);
            }
        }
    }

    private void validateLimits(int topK) {
        if (topK <= 0) {
            throw new IllegalArgumentException("FinalK must be > 0");
        }
        if (candidateK < topK) {
            throw new IllegalArgumentException(
                    "CandidateK must be >= FinalK");
        }
    }

    private static void validateConfiguredLimits(
            int denseSourceK,
            int lexicalSourceK,
            int candidateK) {
        if (denseSourceK <= 0 || lexicalSourceK <= 0 || candidateK <= 0) {
            throw new IllegalArgumentException(
                    "SourceK and CandidateK must be > 0");
        }
    }

    private void logDegradation(
            List<CandidateSourceDiagnostic> diagnostics,
            boolean allSourcesFailed) {
        List<CandidateSourceType> failedSources = diagnostics.stream()
                .filter(diagnostic -> !diagnostic.succeeded())
                .map(CandidateSourceDiagnostic::failedSource)
                .toList();
        Map<CandidateSourceType, CandidateSourceFailureType> failureTypes =
                new EnumMap<>(CandidateSourceType.class);
        Map<CandidateSourceType, Long> sourceLatencyNanos =
                new EnumMap<>(CandidateSourceType.class);
        Map<CandidateSourceType, Integer> candidateCount =
                new EnumMap<>(CandidateSourceType.class);
        for (CandidateSourceDiagnostic diagnostic : diagnostics) {
            sourceLatencyNanos.put(
                    diagnostic.source(), diagnostic.sourceLatencyNanos());
            candidateCount.put(
                    diagnostic.source(), diagnostic.candidateCount());
            if (!diagnostic.succeeded()) {
                failureTypes.put(
                        diagnostic.failedSource(), diagnostic.failureType());
            }
        }
        log.warn("Hybrid retrieval degraded: degraded=true, "
                        + "allSourcesFailed={}, failedSources={}, "
                        + "failureTypes={}, sourceLatencyNanos={}, "
                        + "candidateCount={}",
                allSourcesFailed, failedSources, failureTypes,
                sourceLatencyNanos, candidateCount);
    }
}
