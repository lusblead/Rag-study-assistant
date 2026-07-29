// 持久化执行权，用 owner、期限和版本阻止并发误提交。
package com.rag.backend.ingestionlab.job;

import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Service
// 租约服务：只有 owner、期限、版本 CAS 成功者有提交权。
public class JobLeaseService {
    // 所有 Claim、续租和完成都经同一 Mapper 的条件更新，Service 必须检查其影响行数。
    private final IngestJobMapper mapper;
    // 注入时钟使租约过期可确定性测试。
    private final Clock clock;
    // 提交权有效期，过短易丢、过长拖慢接管。
    private final Duration leaseDuration;

    @org.springframework.beans.factory.annotation.Autowired
    // Spring 生产构造器使用 UTC 时钟和 45 秒默认 Lease；测试构造器可传入固定时钟与短期限。
    public JobLeaseService(IngestJobMapper mapper) {
        this(mapper, Clock.systemUTC(), Duration.ofSeconds(45));
    }

    JobLeaseService(IngestJobMapper mapper, Clock clock, Duration leaseDuration) {
        this.mapper = mapper;
        this.clock = clock;
        this.leaseDuration = leaseDuration;
    }

    // 读取 stateVersion 后 CAS 领取，影响行数不为 1 就无所有权。
    public Lease claim(String jobId, String owner) {
        IngestJob current = requireJob(jobId);
        LocalDateTime now = now();
        LocalDateTime until = now.plus(leaseDuration);
        int changed = mapper.tryClaim(jobId, owner, now, until, current.getStateVersion());
        // CAS 为 0 说明状态、owner、期限或版本变化，当前 Worker 无提交权。
        if (changed != 1) {
            throw new LeaseNotAcquiredException(jobId);
        }
        return new Lease(jobId, owner, until, current.getStateVersion() + 1);
    }

    // 校验 owner、未过期和版本，成功后返回新 Lease。
    public Lease renew(Lease lease) {
        LocalDateTime now = now();
        LocalDateTime until = now.plus(leaseDuration);
        int changed = mapper.renew(lease.jobId(), lease.owner(), now, until,
                lease.stateVersion());
        // CAS 为 0 说明状态、owner、期限或版本变化，当前 Worker 无提交权。
        if (changed != 1) {
            throw new LeaseLostException(lease.jobId());
        }
        return new Lease(lease.jobId(), lease.owner(), until,
                lease.stateVersion() + 1);
    }

    // 完成和重试都走 owner CAS，旧 Worker 不可覆盖。
    public void succeed(Lease lease) {
        finish(lease, "SUCCEEDED", null, null, now());
    }

    // 完成和重试都走 owner CAS，旧 Worker 不可覆盖。
    public void retry(Lease lease, String errorCode, String safeDetail,
                      Duration backoff) {
        finish(lease, "RETRY_WAIT", errorCode, safeDetail, now().plus(backoff));
    }

    // 完成和重试都走 owner CAS，旧 Worker 不可覆盖。
    private void finish(Lease lease, String state, String errorCode,
                        String detail, LocalDateTime nextRunAt) {
        LocalDateTime now = now();
        int changed = mapper.finishOwned(lease.jobId(), lease.owner(),
                lease.stateVersion(), state, errorCode, detail, nextRunAt, now);
        // CAS 为 0 说明状态、owner、期限或版本变化，当前 Worker 无提交权。
        if (changed != 1) {
            throw new LeaseLostException(lease.jobId());
        }
    }

    private IngestJob requireJob(String jobId) {
        IngestJob job = mapper.selectById(jobId);
        if (job == null) throw new IllegalArgumentException("Unknown job: " + jobId);
        return job;
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    // Lease：不可变所有权快照，携带 jobId、owner、截止时间和 stateVersion。
    public record Lease(String jobId, String owner,
                        LocalDateTime leaseUntil, long stateVersion) { }

    // LeaseNotAcquiredException：稳定失败类型，上层不解析异常文案。
    public static final class LeaseNotAcquiredException extends RuntimeException {
        // 异常类型表示本次从未取得执行权，消息中的 jobId 只用于定位。
        public LeaseNotAcquiredException(String id) { super("Lease not acquired: " + id); }
    }
    // LeaseLostException：稳定失败类型，上层不解析异常文案。
    public static final class LeaseLostException extends RuntimeException {
        // 异常类型表示原 owner 已失去提交权，Worker 捕获后必须停止后续回写。
        public LeaseLostException(String id) { super("Lease lost: " + id); }
    }
}