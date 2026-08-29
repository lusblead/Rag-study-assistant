package com.rag.backend.ingestionlab.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.backend.ingestionlab.job.JobWakeupPort;
import com.rag.backend.observability.trace.TraceCarrier;
import com.rag.backend.observability.trace.TraceContextService;
import com.rag.backend.observability.trace.TraceSpan;
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
    private final ObjectMapper objectMapper;
    private final TraceContextService traces;
    private final String owner;
    private final Clock clock;

    @Autowired
    public OutboxDispatcher(OutboxClaimService claimService,
                            OutboxEventMapper mapper,
                            JobWakeupPort wakeup,
                            ObjectMapper objectMapper,
                            TraceContextService traces) {
        this(claimService, mapper, wakeup, objectMapper, traces,
                Clock.systemUTC());
    }

    OutboxDispatcher(OutboxClaimService claimService,
                     OutboxEventMapper mapper,
                     JobWakeupPort wakeup,
                     ObjectMapper objectMapper,
                     TraceContextService traces,
                     Clock clock) {
        this.claimService = claimService;
        this.mapper = mapper;
        this.wakeup = wakeup;
        this.objectMapper = objectMapper;
        this.traces = traces;
        this.clock = clock;
        this.owner = "dispatcher-" + System.currentTimeMillis();
    }

    @Scheduled(fixedDelay = 5_000)
    public void dispatch() {
        List<OutboxClaimService.Claim> claims = claimService.claimBatch(owner, 10);
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);

        for (OutboxClaimService.Claim claim : claims) {
            dispatchClaim(claim, now);
        }
    }

    private void dispatchClaim(
            OutboxClaimService.Claim claim,
            LocalDateTime now) {
        TraceCarrier carrier;
        try {
            carrier = carrierFor(claim);
        } catch (RuntimeException decodeError) {
            // 畸形 payload 不得逃出单事件边界，也不得把原始 payload 写入错误字段。
            TraceCarrier fallback = new TraceCarrier(null, claim.aggregateId());
            try (TraceSpan span = traces.continueOrStart(
                    fallback, "ingestion.outbox.dispatch", claim.aggregateId())) {
                markRetry(
                        claim, now, "OUTBOX_PAYLOAD_INVALID", decodeError, span);
            }
            return;
        }

        try (TraceSpan span = traces.continueOrStart(
                carrier, "ingestion.outbox.dispatch", claim.aggregateId())) {
            try {
                // 事务外唤醒，不占用数据库连接。
                wakeup.wakeup(claim.aggregateId());
                int changed = mapper.markPublished(
                        claim.eventId(), claim.owner(),
                        claim.stateVersion(), now);
                span.result(changed == 1 ? "success" : "claim_lost");
            } catch (RuntimeException error) {
                markRetry(claim, now, "DISPATCH_FAILED", error, span);
            }
        }
    }

    private void markRetry(
            OutboxClaimService.Claim claim,
            LocalDateTime now,
            String errorCode,
            RuntimeException failure,
            TraceSpan span) {
        boolean dead = claim.attempt() >= 5;
        try {
            int changed = mapper.markRetry(
                    claim.eventId(), claim.owner(),
                    claim.stateVersion(), now,
                    now.plus(Duration.ofSeconds(30)),
                    errorCode, dead);
            span.error(failure).result(changed != 1
                    ? "claim_lost"
                    : (dead ? "failed" : "retry"));
        } catch (RuntimeException persistenceError) {
            // Claim 会按租期重新开放；当前 batch 继续处理其他事件。
            span.error(persistenceError).result("claim_lost");
        }
    }

    private TraceCarrier carrierFor(OutboxClaimService.Claim claim) {
        if (!"INGEST_REQUESTED".equals(claim.eventType())) {
            return new TraceCarrier(null, claim.aggregateId());
        }
        return IngestRequestedEventPayload.decode(
                objectMapper, claim.payloadJson(), claim.aggregateId()).carrier();
    }
}
