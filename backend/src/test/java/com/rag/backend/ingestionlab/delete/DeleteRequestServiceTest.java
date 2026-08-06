package com.rag.backend.ingestionlab.delete;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.backend.ingestionlab.job.IngestJobMapper;
import com.rag.backend.ingestionlab.outbox.OutboxEventMapper;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DeleteRequestServiceTest {

    @Test
    void tombstoneJobAndOutboxAreCreatedInThatOrder() {
        DeleteRequestMapper documents = mock(DeleteRequestMapper.class);
        IngestJobMapper jobs = mock(IngestJobMapper.class);
        OutboxEventMapper outbox = mock(OutboxEventMapper.class);
        DeleteRequestMapper.DeleteDocumentRow row =
                new DeleteRequestMapper.DeleteDocumentRow();
        row.setId(9L);
        row.setLifecycleStatus("ACTIVE");
        when(documents.lockDocument(9L)).thenReturn(row);
        when(documents.tombstone(9L)).thenReturn(1);
        DeleteRequestService service = new DeleteRequestService(
                documents, jobs, outbox, new ObjectMapper(),
                Clock.fixed(Instant.parse("2026-08-04T08:00:00Z"),
                        ZoneOffset.UTC));

        DeleteRequestService.Submission result = service.request(9L);

        assertNotNull(result.jobId());
        assertFalse(result.reused());
        InOrder order = inOrder(documents, jobs, outbox);
        order.verify(documents).lockDocument(9L);
        order.verify(documents).tombstone(9L);
        order.verify(documents).cancelIngestJobs(9L);
        order.verify(jobs).insert(any());
        order.verify(outbox).insert(any());
    }
}
