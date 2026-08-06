// Repository 端口让 Reconciler 只表达“观察问题”和“完成范围扫描”，不依赖 MyBatis 细节。
package com.rag.backend.ingestionlab.reconcile;

// IssueRepository 是 ReconciliationService 与 Issue 持久化之间的边界，测试 Fake 可记录 observe/resolve 调用顺序。
public interface IssueRepository {
    void observe(String runId, ConsistencyIssue issue);
    void resolveVectorInventoryNotSeen(long versionId, String completedRunId);
}