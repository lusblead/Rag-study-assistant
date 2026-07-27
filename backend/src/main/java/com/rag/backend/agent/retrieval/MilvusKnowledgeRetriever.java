package com.rag.backend.agent.retrieval;

import com.rag.backend.agent.embedding.EmbeddingClient;
import com.rag.backend.agent.model.KnowledgeChunk;
import com.rag.backend.agent.model.VectorSearchResult;
import com.rag.backend.agent.rerank.KnowledgeReranker;
import com.rag.backend.agent.repository.KnowledgeChunkRepository;
import com.rag.backend.agent.vector.VectorStoreService;
import com.rag.backend.document.DocumentMapper;
import com.rag.backend.document.model.CourseDocument;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
// 使用向量库和重排序器检索课程知识片段。
public class MilvusKnowledgeRetriever implements KnowledgeRetriever {
    private final EmbeddingClient embeddingClient;
    private final VectorStoreService vectorStoreService;
    private final KnowledgeChunkRepository chunkRepository;
    private final DocumentMapper documentMapper;
    private final KnowledgeReranker reranker;
    private final double similarityThreshold;
    private final int candidateK;

    @Autowired
    public MilvusKnowledgeRetriever(EmbeddingClient embeddingClient,
                                    VectorStoreService vectorStoreService,
                                    KnowledgeChunkRepository chunkRepository,
                                    DocumentMapper documentMapper,
                                    KnowledgeReranker reranker,
                                    @Value("${rag.similarity-threshold:0.0}") double similarityThreshold,
                                    @Value("${rag.candidate-k:20}") int candidateK) {
        this.embeddingClient = embeddingClient;
        this.vectorStoreService = vectorStoreService;
        this.chunkRepository = chunkRepository;
        this.documentMapper = documentMapper;
        this.reranker = reranker;
        this.similarityThreshold = similarityThreshold;
        this.candidateK = candidateK;
    }

    public MilvusKnowledgeRetriever(EmbeddingClient embeddingClient,
                                    VectorStoreService vectorStoreService,
                                    KnowledgeChunkRepository chunkRepository,
                                    KnowledgeReranker reranker,
                                    double similarityThreshold,
                                    int candidateK) {
        this(embeddingClient, vectorStoreService, chunkRepository, null, reranker, similarityThreshold, candidateK);
    }

    @Override
    public List<RetrievedChunk> retrieve(Long courseId, String query, int topK) {
        List<Double> queryVector = embeddingClient.embed(query);
        int searchK = Math.max(topK, candidateK);
        List<VectorSearchResult> results = vectorStoreService.search(courseId, queryVector, searchK);
        Map<Long, String> documentNameCache = new HashMap<>();

        List<RetrievedChunk> candidates = new ArrayList<>();
        for (VectorSearchResult result : results) {
            if (result.score() != null && result.score() < similarityThreshold) {
                continue;
            }
            KnowledgeChunk chunk = chunkRepository.findById(result.chunkId());
            if (chunk == null) {
                continue;
            }
            candidates.add(toRetrievedChunk(chunk, result.score(), documentNameCache));
        }
        if (candidates.isEmpty()) {
            candidates = chunkRepository.findByCourseId(courseId, searchK).stream()
                    .map(chunk -> toRetrievedChunk(chunk, 0.0, documentNameCache))
                    .toList();
        }
        return reranker.rerank(query, candidates, topK);
    }

    private RetrievedChunk toRetrievedChunk(KnowledgeChunk chunk, Double score, Map<Long, String> documentNameCache) {
        return new RetrievedChunk(
                chunk.getId(),
                chunk.getDocumentId(),
                documentName(chunk, documentNameCache),
                chunk.getTitle(),
                chunk.getContent(),
                chunk.getSourcePage(),
                score
        );
    }

    private String documentName(KnowledgeChunk chunk, Map<Long, String> documentNameCache) {
        Long documentId = chunk.getDocumentId();
        if (documentId == null || documentMapper == null) {
            return chunk.getTitle();
        }
        return documentNameCache.computeIfAbsent(documentId, id -> {
            CourseDocument document = documentMapper.selectById(id);
            if (document != null && document.getFilename() != null && !document.getFilename().isBlank()) {
                return document.getFilename();
            }
            return chunk.getTitle();
        });
    }
}
