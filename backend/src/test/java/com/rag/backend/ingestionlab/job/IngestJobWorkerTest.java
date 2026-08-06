package com.rag.backend.ingestionlab.job;

import com.rag.backend.ingestionlab.outbox.DocumentVersionMapper;
import com.rag.backend.ingestionlab.outbox.DocumentVersionRow;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 验证 Worker 结束任务时使用 Orchestrator 续租后的最新提交权。 */
class IngestJobWorkerTest {

    @Test
    void successUsesLatestLeaseFromSession() {
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
                leases, orchestrator, jobs, versions).run("job-success");

        verify(leases).succeed(latest);
    }

    @Test
    void retryUsesLatestLeaseFromSession() {
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
                leases, orchestrator, jobs, versions).run("job-1");

        verify(leases).retry(
                eq(latest), eq("DOWNSTREAM_UNAVAILABLE"),
                anyString(), eq(Duration.ofSeconds(30)));
    }

    @Test
    void staleWorkerCannotMarkVersionFailedAfterLeaseWasLost() {
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
                leases, orchestrator, jobs, versions).run("job-2");

        verify(leases, never()).fail(any(), anyString(), anyString());
        verify(leases, never()).retry(
                any(), anyString(), anyString(), any(Duration.class));
        verify(versions).failIfJobOwned(
                eq(71L), eq("PARSING"), eq(3L), eq("job-2"),
                eq("old-owner"), eq(7L), any());
    }

    @Test
    void permanentFailureMarksVersionAndJobOnlyWhileLeaseIsOwned() {
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
                leases, orchestrator, jobs, versions).run("job-3");

        verify(leases).fail(
                eq(lease), eq("SOURCE_IDENTITY_MISMATCH"), anyString());
    }
}
