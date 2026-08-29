package com.rag.backend.ingestionlab.job;

import com.rag.backend.ingestionlab.outbox.DocumentVersionMapper;
import com.rag.backend.ingestionlab.outbox.DocumentVersionRow;
import com.rag.backend.ingestionlab.vector.VectorVisibilityTimeoutException;
import com.rag.backend.observability.trace.TraceContextService;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 验证 Worker 结束任务时使用 Orchestrator 续租后的最新提交权。 */
class IngestJobWorkerTest {

    @Test
    void successUsesLatestLeaseFromSession() {
        List<TraceContextService.TraceEvent> events = new ArrayList<>();
        JobLeaseService leases = mock(JobLeaseService.class);
        IngestJobOrchestrator orchestrator = mock(IngestJobOrchestrator.class);
        IngestJobMapper jobs = mock(IngestJobMapper.class);
        DocumentVersionMapper versions = mock(DocumentVersionMapper.class);
        JobLeaseService.LeaseSession session =
                mock(JobLeaseService.LeaseSession.class);
        JobLeaseService.Lease initial = new JobLeaseService.Lease(
                "job-success", "owner",
                LocalDateTime.parse("2026-08-04T08:05:00"), 1L);
        JobLeaseService.Lease latest = new JobLeaseService.Lease(
                "job-success", "owner",
                LocalDateTime.parse("2026-08-04T08:10:00"), 5L);
        when(leases.claim(eq("job-success"), anyString())).thenReturn(initial);
        when(leases.openSession(initial)).thenReturn(session);
        when(session.current()).thenReturn(latest);

        new IngestJobWorker(
                leases, orchestrator, jobs, versions, traces(events))
                .run("job-success");

        verify(leases).succeed(latest);
        assertEquals("success", attemptEnd(events).result());
    }

    @Test
    void retryUsesLatestLeaseFromSession() {
        List<TraceContextService.TraceEvent> events = new ArrayList<>();
        JobLeaseService leases = mock(JobLeaseService.class);
        IngestJobOrchestrator orchestrator = mock(IngestJobOrchestrator.class);
        IngestJobMapper jobs = mock(IngestJobMapper.class);
        DocumentVersionMapper versions = mock(DocumentVersionMapper.class);
        JobLeaseService.LeaseSession session =
                mock(JobLeaseService.LeaseSession.class);
        JobLeaseService.Lease initial = new JobLeaseService.Lease(
                "job-1", "owner", LocalDateTime.parse("2026-08-04T08:05:00"), 1L);
        JobLeaseService.Lease latest = new JobLeaseService.Lease(
                "job-1", "owner", LocalDateTime.parse("2026-08-04T08:10:00"), 4L);
        when(leases.claim(eq("job-1"), anyString())).thenReturn(initial);
        when(leases.openSession(initial)).thenReturn(session);
        when(session.current()).thenReturn(latest);
        doThrow(new RuntimeException("temporary dependency failure"))
                .when(orchestrator).runOwned(session);

        new IngestJobWorker(
                leases, orchestrator, jobs, versions, traces(events))
                .run("job-1");

        verify(leases).retry(
                eq(latest), eq("DOWNSTREAM_UNAVAILABLE"),
                anyString(), eq(Duration.ofSeconds(30)));
        assertEquals("retry", attemptEnd(events).result());
    }

    @Test
    void visibilityTimeoutEntersRetryWaitWithoutFailingVersion() {
        List<TraceContextService.TraceEvent> events = new ArrayList<>();
        JobLeaseService leases = mock(JobLeaseService.class);
        IngestJobOrchestrator orchestrator = mock(IngestJobOrchestrator.class);
        IngestJobMapper jobs = mock(IngestJobMapper.class);
        DocumentVersionMapper versions = mock(DocumentVersionMapper.class);
        JobLeaseService.LeaseSession session =
                mock(JobLeaseService.LeaseSession.class);
        JobLeaseService.Lease lease = new JobLeaseService.Lease(
                "job-visibility", "owner",
                LocalDateTime.parse("2026-08-04T08:05:00"), 2L);
        when(leases.claim(eq("job-visibility"), anyString())).thenReturn(lease);
        when(leases.openSession(lease)).thenReturn(session);
        when(session.current()).thenReturn(lease);
        doThrow(new VectorVisibilityTimeoutException(
                71L, 3, 0, Duration.ofSeconds(30)))
                .when(orchestrator).runOwned(session);

        new IngestJobWorker(
                leases, orchestrator, jobs, versions, traces(events))
                .run("job-visibility");

        verify(leases).retry(
                eq(lease), eq("TIMEOUT"), anyString(),
                eq(Duration.ofSeconds(30)));
        verify(leases, never()).fail(any(), anyString(), anyString());
        verify(versions, never()).failIfJobOwned(
                anyLong(), anyString(), anyLong(), anyString(),
                anyString(), anyLong(), any());
        assertEquals("retry", attemptEnd(events).result());
    }

    @Test
    void staleWorkerCannotMarkVersionFailedAfterLeaseWasLost() {
        List<TraceContextService.TraceEvent> events = new ArrayList<>();
        JobLeaseService leases = mock(JobLeaseService.class);
        IngestJobOrchestrator orchestrator = mock(IngestJobOrchestrator.class);
        IngestJobMapper jobs = mock(IngestJobMapper.class);
        DocumentVersionMapper versions = mock(DocumentVersionMapper.class);
        JobLeaseService.LeaseSession session =
                mock(JobLeaseService.LeaseSession.class);
        JobLeaseService.Lease lease = new JobLeaseService.Lease(
                "job-2", "old-owner",
                LocalDateTime.parse("2026-08-04T08:05:00"), 7L);
        when(leases.claim(eq("job-2"), anyString())).thenReturn(lease);
        when(leases.openSession(lease)).thenReturn(session);
        when(session.current()).thenReturn(lease);
        doThrow(new IngestJobOrchestrator.SourceIdentityMismatchException(71L))
                .when(orchestrator).runOwned(session);

        IngestJob job = new IngestJob();
        job.setJobId("job-2");
        job.setDocumentId(9L);
        job.setDocumentVersionId(71L);
        job.setJobType("INGEST");
        when(jobs.selectById("job-2")).thenReturn(job);
        DocumentVersionRow version = new DocumentVersionRow();
        version.setId(71L);
        version.setState("PARSING");
        version.setStateVersion(3L);
        when(versions.findById(71L)).thenReturn(version);
        // 0 行表示此 Lease 已被新 Worker 的 stateVersion 取代。
        when(versions.failIfJobOwned(
                eq(71L), eq("PARSING"), eq(3L), eq("job-2"),
                eq("old-owner"), eq(7L), any())).thenReturn(0);

        new IngestJobWorker(
                leases, orchestrator, jobs, versions, traces(events))
                .run("job-2");

        verify(leases, never()).fail(any(), anyString(), anyString());
        verify(leases, never()).retry(
                any(), anyString(), anyString(), any(Duration.class));
        verify(versions).failIfJobOwned(
                eq(71L), eq("PARSING"), eq(3L), eq("job-2"),
                eq("old-owner"), eq(7L), any());
        assertEquals("lease_lost", attemptEnd(events).result());
    }

    @Test
    void permanentFailureMarksVersionAndJobOnlyWhileLeaseIsOwned() {
        List<TraceContextService.TraceEvent> events = new ArrayList<>();
        JobLeaseService leases = mock(JobLeaseService.class);
        IngestJobOrchestrator orchestrator = mock(IngestJobOrchestrator.class);
        IngestJobMapper jobs = mock(IngestJobMapper.class);
        DocumentVersionMapper versions = mock(DocumentVersionMapper.class);
        JobLeaseService.LeaseSession session =
                mock(JobLeaseService.LeaseSession.class);
        JobLeaseService.Lease lease = new JobLeaseService.Lease(
                "job-3", "owner",
                LocalDateTime.parse("2026-08-04T08:05:00"), 8L);
        when(leases.claim(eq("job-3"), anyString())).thenReturn(lease);
        when(leases.openSession(lease)).thenReturn(session);
        when(session.current()).thenReturn(lease);
        doThrow(new IngestJobOrchestrator.SourceIdentityMismatchException(72L))
                .when(orchestrator).runOwned(session);

        IngestJob job = new IngestJob();
        job.setJobId("job-3");
        job.setDocumentId(10L);
        job.setDocumentVersionId(72L);
        job.setJobType("INGEST");
        when(jobs.selectById("job-3")).thenReturn(job);
        DocumentVersionRow version = new DocumentVersionRow();
        version.setId(72L);
        version.setState("CHUNKING");
        version.setStateVersion(4L);
        when(versions.findById(72L)).thenReturn(version);
        when(versions.failIfJobOwned(
                eq(72L), eq("CHUNKING"), eq(4L), eq("job-3"),
                eq("owner"), eq(8L), any())).thenReturn(1);

        new IngestJobWorker(
                leases, orchestrator, jobs, versions, traces(events))
                .run("job-3");

        verify(leases).fail(
                eq(lease), eq("SOURCE_IDENTITY_MISMATCH"), anyString());
        TraceContextService.TraceEvent end = attemptEnd(events);
        assertEquals("failed", end.result());
        assertEquals("sourceidentitymismatchexception", end.detail());
    }

    @Test
    void claimNotAcquiredHasExplicitNonSuccessTerminal() {
        List<TraceContextService.TraceEvent> events = new ArrayList<>();
        JobLeaseService leases = mock(JobLeaseService.class);
        IngestJobOrchestrator orchestrator = mock(IngestJobOrchestrator.class);
        IngestJobMapper jobs = mock(IngestJobMapper.class);
        DocumentVersionMapper versions = mock(DocumentVersionMapper.class);
        when(leases.claim(eq("job-contended"), anyString())).thenThrow(
                new JobLeaseService.LeaseNotAcquiredException("job-contended"));

        new IngestJobWorker(
                leases, orchestrator, jobs, versions, traces(events))
                .run("job-contended");

        assertEquals("lease_not_acquired", attemptEnd(events).result());
        verifyNoInteractions(orchestrator);
    }

    @Test
    void finalRetryAttemptIsTracedAsRetryExhausted() {
        List<TraceContextService.TraceEvent> events = new ArrayList<>();
        JobLeaseService leases = mock(JobLeaseService.class);
        IngestJobOrchestrator orchestrator = mock(IngestJobOrchestrator.class);
        IngestJobMapper jobs = mock(IngestJobMapper.class);
        DocumentVersionMapper versions = mock(DocumentVersionMapper.class);
        JobLeaseService.LeaseSession session =
                mock(JobLeaseService.LeaseSession.class);
        JobLeaseService.Lease lease = new JobLeaseService.Lease(
                "job-exhausted", "owner",
                LocalDateTime.parse("2026-08-04T08:05:00"), 3L);
        when(leases.claim(eq("job-exhausted"), anyString()))
                .thenReturn(lease);
        when(leases.openSession(lease)).thenReturn(session);
        when(session.current()).thenReturn(lease);
        doThrow(new RuntimeException("temporary dependency failure"))
                .when(orchestrator).runOwned(session);
        when(leases.retry(
                eq(lease), eq("DOWNSTREAM_UNAVAILABLE"),
                anyString(), eq(Duration.ofSeconds(30))))
                .thenReturn(JobLeaseService.RetryOutcome.RETRY_EXHAUSTED);

        new IngestJobWorker(
                leases, orchestrator, jobs, versions, traces(events))
                .run("job-exhausted");

        verify(leases).retry(
                eq(lease), eq("DOWNSTREAM_UNAVAILABLE"),
                anyString(), eq(Duration.ofSeconds(30)));
        assertEquals("retry_exhausted", attemptEnd(events).result());
    }

    private TraceContextService.TraceEvent attemptEnd(
            List<TraceContextService.TraceEvent> events) {
        return events.stream()
                .filter(event -> event.type()
                        == TraceContextService.EventType.END)
                .filter(event -> "ingestion.worker.attempt"
                        .equals(event.operation()))
                .findFirst()
                .orElseThrow();
    }

    private TraceContextService traces(
            List<TraceContextService.TraceEvent> events) {
        AtomicInteger spans = new AtomicInteger();
        return TraceContextService.forTesting(
                new TraceContextService.IdGenerator() {
                    @Override
                    public String nextTraceId() {
                        return "1".repeat(32);
                    }

                    @Override
                    public String nextSpanId() {
                        return "%016x".formatted(spans.incrementAndGet());
                    }
                },
                events::add);
    }
}
