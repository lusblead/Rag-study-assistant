package com.rag.backend.agent.ingest;

import com.rag.backend.document.DocumentService;
import com.rag.backend.document.model.CourseDocument;
import org.springframework.stereotype.Service;

import java.nio.file.Path;

@Service
// 协调文档状态更新和 Agent 入库流程。
public class AgentDocumentIngestService {
    private final DocumentService documentService;
    private final DocumentIngestService documentIngestService;
    private final AgentDocumentCleanupService cleanupService;

    public AgentDocumentIngestService(DocumentService documentService,
                                      DocumentIngestService documentIngestService,
                                      AgentDocumentCleanupService cleanupService) {
        this.documentService = documentService;
        this.documentIngestService = documentIngestService;
        this.cleanupService = cleanupService;
    }

    public int ingestDocument(Long documentId) {
        CourseDocument document = documentService.getById(documentId);
        documentService.updateParseStatus(documentId, CourseDocument.STATUS_PARSING, null);

        try {
            cleanupService.cleanupDocument(documentId);
            int chunkCount = documentIngestService.ingest(
                    document.getCourseId(),
                    document.getId(),
                    Path.of(document.getFilePath()),
                    document.getFileType()
            );
            documentService.updateParseStatus(documentId, CourseDocument.STATUS_PARSED, chunkCount);
            return chunkCount;
        } catch (Exception e) {
            documentService.updateParseStatus(documentId, CourseDocument.STATUS_FAILED, null);
            throw e;
        }
    }
}