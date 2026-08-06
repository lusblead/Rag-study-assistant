package com.rag.backend.ingestionlab.job;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * 定期扫描到期任务作为 Outbox 兜底。
 * 即使事件派发链坏了，Poller 也能让任务继续推进。
 */
@Service
@ConditionalOnProperty(
        name = "ingestion.scheduling.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class IngestJobPoller {
    private final IngestJobMapper mapper;
    private final JobWakeupPort wakeup;

    public IngestJobPoller(IngestJobMapper mapper, JobWakeupPort wakeup) {
        this.mapper = mapper;
        this.wakeup = wakeup;
    }

    @Scheduled(fixedDelay = 30_000)
    public void poll() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        // 先收口已经耗尽次数的等待任务，避免它们永远停在 RETRY_WAIT。
        // RUNNING 且租约未到期的任务不在此更新，仍由当前 owner 提交。
        mapper.failExhausted(now);
        List<String> due = mapper.findDueJobIds(now, 20);
        for (String jobId : due) {
            wakeup.wakeup(jobId);
        }
    }
}
