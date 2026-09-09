// 持久化执行权，用 owner、期限和版本阻止并发误提交。
package com.rag.backend.ingestionlab.job;

import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;

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
    // Spring 生产构造器使用 UTC 与配置期限；测试构造器可传入固定时钟与短期限。
    public JobLeaseService(
            IngestJobMapper mapper,
            @Value("${ingestion.job.lease-duration:PT5M}") Duration leaseDuration) {
        this(mapper, Clock.systemUTC(), leaseDuration);
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

    /**
     * 在一次编排期间保存最新 Lease token。
     * Orchestrator 每次续租都会更新同一个 Session，Worker 即使在后续步骤抛错，
     * 仍可用最新 stateVersion 提交 retry/fail，而不会误用最初的旧 token。
     */
    public LeaseSession openSession(Lease initialLease) {
        return new LeaseSession(initialLease);
    }

    // 完成和重试都走 owner CAS，旧 Worker 不可覆盖。
    public void succeed(Lease lease) {
        finish(lease, "SUCCEEDED", null, null, now());
    }

    public void deferDeleteForReaders(Lease lease) {
        LocalDateTime now = now();
        if (mapper.deferDeleteForReaders(lease.jobId(), lease.owner(), lease.stateVersion(),
                now, now.plusSeconds(30)) != 1) throw new LeaseLostException(lease.jobId());
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

    public final class LeaseSession {
        private Lease current;

        private LeaseSession(Lease initialLease) {
            this.current = initialLease;
        }

        public Lease current() {
            return current;
        }

        public Lease renew() {
            current = JobLeaseService.this.renew(current);
            return current;
        }
    }

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

    /**
     * 永久错误直接结束任务。
     * errorCode 使用稳定机器码；detail 只能放脱敏摘要，不能写文档原文或密钥。
     */
    public void fail(
            Lease lease,
            String errorCode,
            String safeDetail) {
        finish(lease, "FAILED", errorCode, safeDetail, now());
    }

    /**
     * 瞬时错误只有在剩余尝试次数内才进入 RETRY_WAIT。
     * attempt 在成功领取时已经加一，所以 current.attempt >= maxAttempts
     * 表示本次已是最后一次允许的执行。
     */
    public RetryOutcome retry(
            Lease lease,
            String errorCode,
            String safeDetail,
            Duration backoff) {
        IngestJob current = requireJob(lease.jobId());
        if (!"RUNNING".equals(current.getState())
                || !lease.owner().equals(current.getLeaseOwner())
                || current.getStateVersion() != lease.stateVersion()) {
            throw new LeaseLostException(lease.jobId());
        }

        if (current.getAttempt() >= current.getMaxAttempts()) {
            fail(lease, "RETRY_EXHAUSTED", safeDetail);
            return RetryOutcome.RETRY_EXHAUSTED;
        }
        finish(
                lease,
                "RETRY_WAIT",
                errorCode,
                safeDetail,
                now().plus(backoff));
        return RetryOutcome.RETRY_WAIT;
    }

    /** retry 的权威持久结果；调用方不得用第二次预读推测终态。 */
    public enum RetryOutcome {
        RETRY_WAIT,
        RETRY_EXHAUSTED
    }
}
