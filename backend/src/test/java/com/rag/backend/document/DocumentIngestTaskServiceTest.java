package com.rag.backend.document;

import com.rag.backend.common.BizException;
import com.rag.backend.document.model.CourseDocument;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class DocumentIngestTaskServiceTest {

    @Test
    void marksDocumentParsingAndRejectsDuplicateSubmission() {
        DocumentService documentService = mock(DocumentService.class);
        DocumentIngestWorker worker = mock(DocumentIngestWorker.class);
        CourseDocument document = document(7L, CourseDocument.STATUS_UPLOADED);
        CompletableFuture<Void> task = new CompletableFuture<>();
        when(documentService.getById(7L)).thenReturn(document);
        when(worker.ingest(7L)).thenReturn(task);
        DocumentIngestTaskService service = new DocumentIngestTaskService(documentService, worker);

        CourseDocument submitted = service.submit(7L);
        BizException duplicate = assertThrows(BizException.class, () -> service.submit(7L));

        assertEquals(document, submitted);
        assertEquals(409, duplicate.getCode());
        verify(documentService).updateParseStatus(7L, CourseDocument.STATUS_PARSING, null);
        verify(worker).ingest(7L);
    }

    @Test
    void marksDocumentFailedWhenTaskCannotBeScheduled() {
        DocumentService documentService = mock(DocumentService.class);
        DocumentIngestWorker worker = mock(DocumentIngestWorker.class);
        when(documentService.getById(7L)).thenReturn(document(7L, CourseDocument.STATUS_UPLOADED));
        when(worker.ingest(7L)).thenThrow(new IllegalStateException("executor stopped"));
        DocumentIngestTaskService service = new DocumentIngestTaskService(documentService, worker);

        BizException error = assertThrows(BizException.class, () -> service.submit(7L));

        assertEquals(503, error.getCode());
        verify(documentService).updateParseStatus(7L, CourseDocument.STATUS_PARSING, null);
        verify(documentService).updateParseStatus(7L, CourseDocument.STATUS_FAILED, null);
    }

    private static CourseDocument document(Long id, String status) {
        CourseDocument document = new CourseDocument();
        document.setId(id);
        document.setParseStatus(status);
        return document;
    }
}
