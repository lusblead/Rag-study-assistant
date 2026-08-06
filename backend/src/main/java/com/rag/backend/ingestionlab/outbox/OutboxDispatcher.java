package com.rag.backend.ingestionlab.outbox;

import com.rag.backend.ingestionlab.job.JobWakeupPort;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * 短事务领取 Outbox 事件，事务外唤醒 Job，再用 Claim token 确认或退避。
 */
@Service
@ConditionalOnProperty(
        name = "ingestion.scheduling.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class OutboxDispatcher {
    private final OutboxClaimService claimService;
    private final OutboxEventMapper mapper;
    private final JobWakeupPort wakeup;
    private final String owner;
    private final Clock clock;

    @Autowired
    public OutboxDispatcher(OutboxClaimService claimService,
                            OutboxEventMapper mapper,
                            JobWakeupPort wakeup) {
        this(claimService, mapper, wakeup, Clock.systemUTC());
    }

    OutboxDispatcher(OutboxClaimService claimService,
                     OutboxEventMapper mapper,
                     JobWakeupPort wakeup,
                     Clock clock) {
        this.claimService = claimService;
        this.mapper = mapper;
        this.wakeup = wakeup;
        this.clock = clock;
        this.owner = "dispatcher-" + System.currentTimeMillis();
    }

    @Scheduled(fixedDelay = 5_000)
    public void dispatch() {
        List<OutboxClaimService.Claim> claims = claimService.claimBatch(owner, 10);
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);

        for (OutboxClaimService.Claim claim : claims) {
            try {
                // 事务外唤醒，不占用数据库连接。
                wakeup.wakeup(claim.aggregateId());
                mapper.markPublished(
                        claim.eventId(), claim.owner(),
                        claim.stateVersion(), now);
            } catch (RuntimeException error) {
                boolean dead = claim.attempt() >= 5;
                mapper.markRetry(
                        claim.eventId(), claim.owner(),
                        claim.stateVersion(), now,
                        now.plus(Duration.ofSeconds(30)),
                        "DISPATCH_FAILED", dead);
            }
        }
    }
}
