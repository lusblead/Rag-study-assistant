// 把白名单决策安全地映射为有限副作用，并在修复后重新验证。
package com.rag.backend.ingestionlab.reconcile;

// RepairExecutor 负责 Claim、执行、重验和审计；RepairPolicy 只负责决策。
public final class RepairExecutor {
    private final RepairPolicy policy;
    private final IssueLifecyclePort issues;
    private final RepairContextPort contexts;
    private final RepairActionPort actions;
    private final ReverificationPort reverification;

    // 构造器把状态存储、真实副作用和重新验证分开，测试可分别使用 Fake。
    public RepairExecutor(RepairPolicy policy,
                          IssueLifecyclePort issues,
                          RepairContextPort contexts,
                          RepairActionPort actions,
                          ReverificationPort reverification) {
        this.policy = policy;
        this.issues = issues;
        this.contexts = contexts;
        this.actions = actions;
        this.reverification = reverification;
    }

    // 调用前提：Repair Worker 已持有修复 Job Lease；Issue CAS 再阻止两个 Worker 修同一问题。
    public Result execute(ConsistencyIssue issue,
                          long expectedIssueVersion,
                          String repairOwner) {
        IssueClaim claim = issues.claim(
                issue.issueKey(), expectedIssueVersion, repairOwner);
        if (claim == null) {
            return new Result(false, false, "ISSUE_ALREADY_CLAIMED");
        }

        try {
            // Issue 只是发现时的快照；Claim 后必须重读执行时的文档/版本状态。
            // 这一步阻止扫描后开始删除的文档又被补写向量。
            RepairContext current =
                    contexts.loadCurrent(issue.documentVersionId());
            RepairPolicy.Decision decision =
                    policy.decide(issue.issueType(), current.versionState(),
                            current.documentLifecycle());
            if (!decision.automatic()) {
                issues.markNotAutomatic(claim, decision.action());
                return new Result(false, false, decision.action());
            }

            // 这里使用封闭 switch 映射动作；不能反射执行数据库里的任意字符串。
            switch (decision.action()) {
                case "UPSERT_MISSING_VECTOR" ->
                        actions.upsertMissingVector(issue);
                case "DELETE_ORPHAN_VECTOR" ->
                        actions.deleteOrphanVector(issue);
                default -> throw new IllegalStateException(
                        "Automatic action is not implemented: " + decision.action());
            }

            // 副作用返回成功不等于数据已收敛，必须重新读取两个存储核验。
            if (!reverification.isResolved(issue)) {
                issues.markRepairFailed(claim,
                        "REVERIFY_STILL_INCONSISTENT");
                return new Result(true, false, "REVERIFY_STILL_INCONSISTENT");
            }
            issues.markResolved(claim, decision.action());
            return new Result(true, true, decision.action());
        } catch (RuntimeException error) {
            issues.markRepairFailed(claim, "REPAIR_ACTION_FAILED");
            throw error;
        }
    }

    // 只管理 Issue 生命周期和乐观锁，不执行 Milvus 副作用。
    public interface IssueLifecyclePort {
        IssueClaim claim(String issueKey, long expectedIssueVersion,
                         String repairOwner);
        void markNotAutomatic(IssueClaim claim, String reasonCode);
        void markResolved(IssueClaim claim, String resolutionNote);
        void markRepairFailed(IssueClaim claim, String errorCode);
    }

    // Claim 返回递增后的 stateVersion；后续所有写入必须同时检查 issueKey、owner 和该版本。
    public record IssueClaim(String issueKey, String owner,
                             long stateVersion) { }

    // Claim 后从真实数据库读取当前生命周期和 Version 状态，不能复用调用方传入的旧字符串。
    public interface RepairContextPort {
        RepairContext loadCurrent(long documentVersionId);
    }

    public record RepairContext(String versionState,
                                String documentLifecycle) { }

    // 每个方法对应一个审核过的幂等动作，Adapter 再调用 Embedding/Milvus。
    public interface RepairActionPort {
        void upsertMissingVector(ConsistencyIssue issue);
        void deleteOrphanVector(ConsistencyIssue issue);
    }

    // 修复后重新读取真实存储，不复用执行器的内存结果。
    public interface ReverificationPort {
        boolean isResolved(ConsistencyIssue issue);
    }

    public record Result(boolean attempted, boolean resolved, String action) { }
}