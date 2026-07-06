package com.rag.backend.document;

import com.rag.backend.agent.ingest.AgentDocumentIngestService;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

@Component
public class DocumentIngestWorker {
    private final AgentDocumentIngestService ingestService;

    public DocumentIngestWorker(AgentDocumentIngestService ingestService) {
        this.ingestService = ingestService;
    }

    @Async("documentIngestExecutor")
    public CompletableFuture<Void> ingest(Long documentId) {
        ingestService.ingestDocument(documentId);
        return CompletableFuture.completedFuture(null);
    }
}
