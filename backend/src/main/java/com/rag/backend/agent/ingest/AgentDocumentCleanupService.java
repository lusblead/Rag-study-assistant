package com.rag.backend.agent.ingest;

import com.rag.backend.agent.repository.KnowledgeChunkRepository;
import com.rag.backend.agent.vector.VectorStoreService;
import org.springframework.stereotype.Service;

@Service
public class AgentDocumentCleanupService {
    private final VectorStoreService vectorStoreService;
    private final KnowledgeChunkRepository chunkRepository;

    public AgentDocumentCleanupService(VectorStoreService vectorStoreService,
                                       KnowledgeChunkRepository chunkRepository) {
        this.vectorStoreService = vectorStoreService;
        this.chunkRepository = chunkRepository;
    }

    public void cleanupDocument(Long documentId) {
        vectorStoreService.deleteByDocumentId(documentId);
        chunkRepository.deleteByDocumentId(documentId);
    }
}