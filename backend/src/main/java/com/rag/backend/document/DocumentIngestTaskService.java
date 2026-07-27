package com.rag.backend.document;

import com.rag.backend.common.BizException;
import com.rag.backend.document.model.CourseDocument;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class DocumentIngestTaskService {
    private final DocumentService documentService;
    private final DocumentIngestWorker worker;
    private final Set<Long> runningDocumentIds = ConcurrentHashMap.newKeySet();

    public DocumentIngestTaskService(DocumentService documentService, DocumentIngestWorker worker) {
        this.documentService = documentService;
        this.worker = worker;
    }

    public CourseDocument submit(Long documentId) {
        CourseDocument document = documentService.getById(documentId);
        if (!runningDocumentIds.add(documentId)) {
            throw new BizException(409, "Document ingestion is already running: " + documentId);
        }

        documentService.updateParseStatus(documentId, CourseDocument.STATUS_PARSING, null);
        try {
            worker.ingest(documentId)
                    .whenComplete((ignored, error) -> runningDocumentIds.remove(documentId));
        } catch (RuntimeException e) {
            runningDocumentIds.remove(documentId);
            documentService.updateParseStatus(documentId, CourseDocument.STATUS_FAILED, null);
            throw new BizException(503, "Document ingestion queue is unavailable");
        }
        return documentService.getById(documentId);
    }
}
