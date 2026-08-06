// 对账流程：Verifier 产出集合差异，Reconciler 将其持久化为稳定 Issue。
package com.rag.backend.ingestionlab.reconcile;

import com.rag.backend.ingestionlab.verify.IndexVerifier;

import java.util.UUID;

// 对账服务：复用 Verifier，把差异变成 Issue 和指标。
public final class ReconciliationService {
    // 负责事实比较，对账不复制验证逻辑。
    private final IndexVerifier verifier;
    // 保存问题生命周期，避免重复告警。
    private final IssueRepository issues;
    // 只用有限标签，避免指标基数爆炸。
    private final IssueMetrics metrics;

    // Verifier 生成差异事实，IssueRepository 保存生命周期，Metrics 只记录低基数观测。
    public ReconciliationService(IndexVerifier verifier, IssueRepository issues,
                                 IssueMetrics metrics) {
        this.verifier = verifier;
        this.issues = issues;
        this.metrics = metrics;
    }

    // 先记录全部差异，完整结束后才关闭消失问题。
    public IndexVerifier.VerificationReport scan(long versionId,
                                                 int expectedChunkCount) {
        String runId = UUID.randomUUID().toString();
        IndexVerifier.VerificationReport report =
                verifier.verify(versionId, expectedChunkCount);

        if (report.mysqlTotal() != expectedChunkCount) {
            observe(runId, ConsistencyIssue.of(versionId,
                    "MYSQL_CHUNK_COUNT_MISMATCH", "version:" + versionId,
                    String.valueOf(expectedChunkCount),
                    String.valueOf(report.mysqlTotal()), "REBUILD_VERSION"));
        }
        if (report.mysqlDone() != expectedChunkCount) {
            observe(runId, ConsistencyIssue.of(versionId,
                    "MYSQL_DONE_COUNT_MISMATCH", "version:" + versionId,
                    String.valueOf(expectedChunkCount),
                    String.valueOf(report.mysqlDone()), "RETRY_OR_REBUILD_VERSION"));
        }
        for (Long id : report.missingVectorIds()) {
            observe(runId, ConsistencyIssue.of(versionId,
                    "MISSING_VECTOR", "vector:" + id,
                    "present", "absent", "UPSERT_MISSING_VECTOR"));
        }
        for (Long id : report.orphanVectorIds()) {
            observe(runId, ConsistencyIssue.of(versionId,
                    "ORPHAN_VECTOR", "vector:" + id,
                    "absent", "present", "REVIEW_OR_DELETE_ORPHAN"));
        }
        issues.resolveVectorInventoryNotSeen(versionId, runId);
        metrics.scanCompleted(report.passed());
        return report;
    }

    private void observe(String runId, ConsistencyIssue issue) {
        issues.observe(runId, issue);
        metrics.issueObserved(issue.issueType());
    }

    // IssueMetrics：对账指标端口，隔离 Micrometer 并便于测试验证调用。
    public interface IssueMetrics {
        void issueObserved(String type);
        void scanCompleted(boolean clean);
    }
}