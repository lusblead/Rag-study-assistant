package com.rag.backend.agent.retrieval;

import com.rag.backend.agent.embedding.EmbeddingClient;
import com.rag.backend.agent.model.KnowledgeChunk;
import com.rag.backend.agent.repository.KnowledgeChunkRepository;
import com.rag.backend.document.DocumentMapper;
import com.rag.backend.document.model.CourseDocument;
import com.rag.backend.ingestionlab.retrieval.VersionedVectorHit;
import com.rag.backend.ingestionlab.retrieval.VersionedVectorSearch;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 保持原 Milvus 候选召回、threshold、回表和版本复核语义的 Dense 来源。 */
@Component
public class DenseCandidateSource implements CandidateSource {
    private final EmbeddingClient embeddingClient;
    private final VersionedVectorSearch vectorSearch;
    private final KnowledgeChunkRepository chunkRepository;
    private final DocumentMapper documentMapper;
    private final double similarityThreshold;
    private final boolean enforceVersionGate;

    @Autowired
    public DenseCandidateSource(
            EmbeddingClient embeddingClient,
            VersionedVectorSearch vectorSearch,
            KnowledgeChunkRepository chunkRepository,
            DocumentMapper documentMapper,
            @Value("${rag.similarity-threshold:0.0}") double similarityThreshold) {
        this(embeddingClient, vectorSearch, chunkRepository, documentMapper,
                similarityThreshold, true);
    }

    DenseCandidateSource(
            EmbeddingClient embeddingClient,
            VersionedVectorSearch vectorSearch,
            KnowledgeChunkRepository chunkRepository,
            DocumentMapper documentMapper,
            double similarityThreshold,
            boolean enforceVersionGate) {
        this.embeddingClient = embeddingClient;
        this.vectorSearch = vectorSearch;
        this.chunkRepository = chunkRepository;
        this.documentMapper = documentMapper;
        this.similarityThreshold = similarityThreshold;
        this.enforceVersionGate = enforceVersionGate;
    }

    @Override
    public CandidateSourceType type() {
        return CandidateSourceType.DENSE;
    }

    @Override
    public CandidateBatch retrieve(
            RetrievalScope scope,
            String query,
            int candidateK) {
        validate(scope, query, candidateK);
        if (scope.activeVersionIds().isEmpty()) {
            return CandidateBatch.empty(type());
        }

        List<Double> queryVector = embeddingClient.embed(query);
        List<VersionedVectorHit> hits = vectorSearch.search(
                scope.courseId(), scope.activeVersionIds(), queryVector,
                candidateK);
        Map<Long, String> documentNameCache = new HashMap<>();
        List<RetrievalCandidate> candidates = new ArrayList<>();

        long stableOrder = 0;
        for (VersionedVectorHit hit : hits) {
            long currentOrder = stableOrder++;
            if (hit.score() < similarityThreshold) {
                continue;
            }
            KnowledgeChunk chunk = chunkRepository.findById(hit.mysqlChunkId());
            if (chunk == null) {
                continue;
            }
            if (enforceVersionGate && !isInsideScope(scope, hit, chunk)) {
                continue;
            }
            RetrievedChunk retrieved = toRetrievedChunk(
                    chunk, hit.score(), documentNameCache);
            candidates.add(new RetrievalCandidate(
                    retrieved, hit.score(), currentOrder));
            if (candidates.size() == candidateK) {
                break;
            }
        }
        return new CandidateBatch(type(), candidates);
    }

    private boolean isInsideScope(
            RetrievalScope scope,
            VersionedVectorHit hit,
            KnowledgeChunk chunk) {
        Long versionId = chunk.getDocumentVersionId();
        return versionId != null
                && versionId == hit.documentVersionId()
                && scope.activeVersionIds().contains(versionId);
    }

    private RetrievedChunk toRetrievedChunk(
            KnowledgeChunk chunk,
            double score,
            Map<Long, String> documentNameCache) {
        return new RetrievedChunk(
                chunk.getId(),
                chunk.getDocumentId(),
                documentName(chunk, documentNameCache),
                chunk.getTitle(),
                chunk.getContent(),
                chunk.getSourcePage(),
                score).withDenseScore(score);
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

    private void validate(
            RetrievalScope scope,
            String query,
            int candidateK) {
        if (scope == null) {
            throw new IllegalArgumentException("scope must not be null");
        }
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("query must not be blank");
        }
        if (candidateK <= 0) {
            throw new IllegalArgumentException("candidateK must be > 0");
        }
    }
}
