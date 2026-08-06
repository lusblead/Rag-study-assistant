package com.rag.backend.ingestionlab.outbox;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * 只在短事务里锁定和领取事件。
 * 真正唤醒 Job 的动作由第 10 章 OutboxDispatcher 在事务提交后执行。
 */
@Service
public class OutboxClaimService {
    private final OutboxEventMapper mapper;
    private final Clock clock;
    private final Duration claimDuration = Duration.ofSeconds(30);

    @Autowired
    public OutboxClaimService(OutboxEventMapper mapper) {
        this(mapper, Clock.systemUTC());
    }

    OutboxClaimService(OutboxEventMapper mapper, Clock clock) {
        this.mapper = mapper;
        this.clock = clock;
    }

    @Transactional
    public List<Claim> claimBatch(String owner, int limit) {
        LocalDateTime now = LocalDateTime.ofInstant(
                clock.instant(), ZoneOffset.UTC);
        LocalDateTime until = now.plus(claimDuration);
        List<Claim> claims = new ArrayList<>();

        for (OutboxEvent event : mapper.lockBatch(now, limit)) {
            long before = event.getStateVersion();
            if (mapper.claim(
                    event.getEventId(),
                    owner,
                    until,
                    before) == 1) {
                claims.add(new Claim(
                        event.getEventId(),
                        event.getAggregateId(),
                        event.getEventType(),
                        event.getPayloadJson(),
                        owner,
                        until,
                        before + 1,
                        event.getAttempts() + 1));
            }
        }
        return List.copyOf(claims);
    }

    /**
     * Claim 是一次领取的不可变提交权。
     * Dispatcher 的成功和失败回调都必须原样携带它。
     */
    public record Claim(
            String eventId,
            String aggregateId,
            String eventType,
            String payloadJson,
            String owner,
            LocalDateTime claimUntil,
            long stateVersion,
            int attempt) {
    }
}
