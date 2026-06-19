package com.rag.backend.agent.ingest;

import com.rag.backend.common.BizException;
import com.rag.backend.document.DocumentService;
import com.rag.backend.document.model.CourseDocument;
import org.springframework.stereotype.Service;

import java.nio.file.Path;

@Service
public class AgentDocumentIngestService {
    private final DocumentService documentService;
    private final DocumentIngestService documentIngestService;

    public AgentDocumentIngestService(DocumentService documentService,
                                      DocumentIngestService documentIngestService) {
        this.documentService = documentService;
        this.documentIngestService = documentIngestService;
    }

    public int ingestDocument(Long documentId) {
        CourseDocument document = documentService.getById(documentId);
        if (document == null) {
            throw new BizException(400, "文档不存在，documentId=" + documentId);
        }

        documentService.updateParseStatus(documentId, CourseDocument.STATUS_PARSING, null);

        try {
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