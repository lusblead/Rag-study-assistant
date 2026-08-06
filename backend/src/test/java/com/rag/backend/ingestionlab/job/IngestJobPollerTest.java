package com.rag.backend.ingestionlab.job;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Poller 既负责唤醒到期任务，也负责收口已耗尽重试次数的等待任务。 */
class IngestJobPollerTest {

    @Test
    void exhaustedJobsAreFailedBeforeDueJobsAreWoken() {
        IngestJobMapper mapper = mock(IngestJobMapper.class);
        JobWakeupPort wakeup = mock(JobWakeupPort.class);
        when(mapper.findDueJobIds(any(LocalDateTime.class), eq(20)))
                .thenReturn(List.of("job-due"));

        new IngestJobPoller(mapper, wakeup).poll();

        var order = inOrder(mapper, wakeup);
        order.verify(mapper).failExhausted(any(LocalDateTime.class));
        order.verify(mapper).findDueJobIds(any(LocalDateTime.class), eq(20));
        order.verify(wakeup).wakeup("job-due");
    }
}
