package com.rag.backend.ingestionlab.job;

import org.junit.jupiter.api.Test;

import java.time.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

// 用固定时钟和 Mapper 返回行数验证 Claim 成功、竞争失败及过期后的提交拒绝。
class JobLeaseServiceTest {

    @Test
        // 验证只有 CAS 成功者持有 lease，丢租约后不能提交。
    void onlySuccessfulConditionalUpdateOwnsTheLease() {
        IngestJobMapper mapper = mock(IngestJobMapper.class);
        IngestJob row = job("job-1", 7L, "QUEUED", 0L);
        when(mapper.selectById("job-1")).thenReturn(row);
        when(mapper.tryClaim(eq("job-1"), eq("worker-a"), any(), any(), eq(0L)))
                .thenReturn(1);
        when(mapper.tryClaim(eq("job-1"), eq("worker-b"), any(), any(), eq(0L)))
                .thenReturn(0);
        JobLeaseService service = new JobLeaseService(mapper,
                Clock.fixed(Instant.parse("2026-07-16T00:00:00Z"), ZoneOffset.UTC),
                Duration.ofSeconds(30));

        JobLeaseService.Lease owned = service.claim("job-1", "worker-a");
        assertEquals(1L, owned.stateVersion());
        assertThrows(JobLeaseService.LeaseNotAcquiredException.class,
                () -> service.claim("job-1", "worker-b"));
    }

    @Test
        // 验证只有 CAS 成功者持有 lease，丢租约后不能提交。
    void workerCannotCommitAfterLeaseWasLost() {
        IngestJobMapper mapper = mock(IngestJobMapper.class);
        IngestJob row = job("job-1", 7L, "QUEUED", 0L);
        when(mapper.selectById("job-1")).thenReturn(row);
        when(mapper.tryClaim(anyString(), anyString(), any(), any(), anyLong()))
                .thenReturn(1);
        when(mapper.finishOwned(anyString(), anyString(), anyLong(),
                anyString(), nullable(String.class), nullable(String.class), any(), any()))
                .thenReturn(0); // 模拟 lease 已过期且被另一实例接管
        JobLeaseService service = new JobLeaseService(mapper,
                Clock.fixed(Instant.parse("2026-07-16T00:00:00Z"), ZoneOffset.UTC),
                Duration.ofSeconds(30));

        JobLeaseService.Lease lease = service.claim("job-1", "worker-a");
        assertThrows(JobLeaseService.LeaseLostException.class,
                () -> service.succeed(lease));
    }

    private static IngestJob job(String id, Long versionId, String state, long stateVersion) {
        IngestJob job = new IngestJob();
        job.setJobId(id);
        job.setDocumentVersionId(versionId);
        job.setJobType("INGEST");
        job.setState(state);
        job.setAttempt(0);
        job.setMaxAttempts(5);
        job.setNextRunAt(LocalDateTime.ofInstant(
                Instant.parse("2026-07-15T00:00:00Z"), ZoneOffset.UTC));
        job.setStateVersion(stateVersion);
        return job;
    }
}