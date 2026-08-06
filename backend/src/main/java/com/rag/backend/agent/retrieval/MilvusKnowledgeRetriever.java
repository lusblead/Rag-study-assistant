package com.rag.backend.agent.retrieval;

import com.rag.backend.agent.embedding.EmbeddingClient;
import com.rag.backend.agent.model.KnowledgeChunk;
import com.rag.backend.agent.rerank.KnowledgeReranker;
import com.rag.backend.agent.repository.KnowledgeChunkRepository;
import com.rag.backend.agent.vector.VectorStoreService;
import com.rag.backend.document.DocumentMapper;
import com.rag.backend.document.model.CourseDocument;
import com.rag.backend.ingestionlab.retrieval.ActiveVersionResolver;
import com.rag.backend.ingestionlab.retrieval.VersionedVectorHit;
import com.rag.backend.ingestionlab.retrieval.VersionedVectorSearch;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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
    private final EmbeddingClient embeddingClient;
    private final ActiveVersionResolver activeVersions;
    private final VersionedVectorSearch vectorSearch;
    private final KnowledgeChunkRepository chunkRepository;
    private final DocumentMapper documentMapper;
    private final KnowledgeReranker reranker;
    private final double similarityThreshold;
    private final int candidateK;
    private final boolean enforceVersionGate;

    @Autowired
    public MilvusKnowledgeRetriever(
            EmbeddingClient embeddingClient,
            ActiveVersionResolver activeVersions,
            VersionedVectorSearch vectorSearch,
            KnowledgeChunkRepository chunkRepository,
            DocumentMapper documentMapper,
            KnowledgeReranker reranker,
            @Value("${rag.similarity-threshold:0.0}") double similarityThreshold,
            @Value("${rag.candidate-k:20}") int candidateK) {
        this(embeddingClient, activeVersions, vectorSearch, chunkRepository,
                documentMapper, reranker, similarityThreshold, candidateK, true);
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
                embeddingClient,
                ignored -> Set.of(0L),
                (courseId, ignored, queryVector, topK) ->
                        legacyVectorStore.search(courseId, queryVector, topK).stream()
                                .map(result -> new VersionedVectorHit(
                                        result.chunkId(), 0L,
                                        result.score() == null ? 0.0 : result.score()))
                                .toList(),
                chunkRepository,
                null,
                reranker,
                similarityThreshold,
                candidateK,
                false);
    }

    private MilvusKnowledgeRetriever(
            EmbeddingClient embeddingClient,
            ActiveVersionResolver activeVersions,
            VersionedVectorSearch vectorSearch,
            KnowledgeChunkRepository chunkRepository,
            DocumentMapper documentMapper,
            KnowledgeReranker reranker,
            double similarityThreshold,
            int candidateK,
            boolean enforceVersionGate) {
        this.embeddingClient = embeddingClient;
        this.activeVersions = activeVersions;
        this.vectorSearch = vectorSearch;
        this.chunkRepository = chunkRepository;
        this.documentMapper = documentMapper;
        this.reranker = reranker;
        this.similarityThreshold = similarityThreshold;
        this.candidateK = candidateK;
        this.enforceVersionGate = enforceVersionGate;
    }

    @Override
    public List<RetrievedChunk> retrieve(Long courseId, String query, int topK) {
        Set<Long> activeVersionIds = activeVersions.forCourse(courseId);
        if (activeVersionIds.isEmpty()) {
            return List.of();
        }

        List<Double> queryVector = embeddingClient.embed(query);
        int searchK = Math.max(topK, candidateK);
        List<VersionedVectorHit> results = vectorSearch.search(
                courseId, activeVersionIds, queryVector, searchK);
        Map<Long, String> documentNameCache = new HashMap<>();

        List<RetrievedChunk> candidates = new ArrayList<>();
        for (VersionedVectorHit result : results) {
            if (result.score() < similarityThreshold) {
                continue;
            }
            KnowledgeChunk chunk = chunkRepository.findById(result.mysqlChunkId());
            if (chunk == null) {
                continue;
            }
            if (enforceVersionGate
                    && (chunk.getDocumentVersionId() == null
                    || chunk.getDocumentVersionId() != result.documentVersionId()
                    || !activeVersionIds.contains(chunk.getDocumentVersionId()))) {
                continue;
            }
            candidates.add(toRetrievedChunk(
                    chunk, result.score(), documentNameCache));
        }

        // 不再回退到按 courseId 查询全部 Chunk；空候选是真实、可观察的空结果。
        return reranker.rerank(query, candidates, topK);
    }

    private RetrievedChunk toRetrievedChunk(
            KnowledgeChunk chunk,
            Double score,
            Map<Long, String> documentNameCache) {
        return new RetrievedChunk(
                chunk.getId(),
                chunk.getDocumentId(),
                documentName(chunk, documentNameCache),
                chunk.getTitle(),
                chunk.getContent(),
                chunk.getSourcePage(),
                score);
    }

    private String documentName(
            KnowledgeChunk chunk,
            Map<Long, String> documentNameCache) {
        Long documentId = chunk.getDocumentId();
        if (documentId == null || documentMapper == null) {
            return chunk.getTitle();
        }
        return documentNameCache.computeIfAbsent(documentId, id -> {
            CourseDocument document = documentMapper.selectById(id);
            if (document != null && document.getFilename() != null
                    && !document.getFilename().isBlank()) {
                return document.getFilename();
            }
            return chunk.getTitle();
        });
    }
}
