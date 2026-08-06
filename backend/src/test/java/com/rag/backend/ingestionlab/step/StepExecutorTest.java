package com.rag.backend.ingestionlab.step;

import com.rag.backend.ingestionlab.job.JobLeaseService;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

// 证明 DONE 行不会再次调用 Stage，并且相同 job/step 的不同 inputDigest 被拒绝。
class StepExecutorTest {

    private static final JobLeaseService.Lease LEASE =
            new JobLeaseService.Lease("job", "owner",
                    LocalDateTime.now(ZoneOffset.UTC).plusSeconds(30), 1L);

    @Test
    void doneStepDoesNotInvokeActionAgain() {
        IngestStepMapper mapper = mock(IngestStepMapper.class);
        IngestStepRow row = new IngestStepRow();
        row.setState("DONE");
        row.setInputDigest("input");
        row.setOutputRef("9/parsed.json");
        row.setOutputDigest("output");
        row.setProcessedCount(1);
        when(mapper.find("job", "PARSE")).thenReturn(row);
        StepExecutor executor = new StepExecutor(mapper);
        AtomicInteger calls = new AtomicInteger();

        StepExecutor.StepResult result = executor.run(LEASE, "PARSE", "input", () -> {
            calls.incrementAndGet();
            return new StepExecutor.StepResult("new", "new", 1);
        });

        assertTrue(result.replayed());
        assertEquals(0, calls.get());
        verify(mapper, never()).markRunning(
                anyString(), anyString(), anyString(),
                anyString(), anyLong(), any());
    }

    @Test
    void changedInputCannotReuseOldStep() {
        IngestStepMapper mapper = mock(IngestStepMapper.class);
        IngestStepRow row = new IngestStepRow();
        row.setState("DONE");
        row.setInputDigest("old-input");
        when(mapper.find("job", "PARSE")).thenReturn(row);
        StepExecutor executor = new StepExecutor(mapper);

        assertThrows(IllegalStateException.class,
                () -> executor.run(LEASE, "PARSE", "new-input",
                        () -> new StepExecutor.StepResult("x", "y", 1)));
    }
}
