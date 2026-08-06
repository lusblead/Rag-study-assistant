package com.rag.backend.ingestionlab.reconcile;

import com.rag.backend.ingestionlab.vector.ConsistentVectorStore;
import com.rag.backend.ingestionlab.verify.ChunkInventory;
import com.rag.backend.ingestionlab.verify.IndexVerifier;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

// 验证差异变成稳定 Issue、DONE 数不被总数掩盖，以及 ACTIVE 孤儿不会自动删除。
class ReconciliationServiceTest {
    @Test
    void missingAndOrphanBecomeStableIssues() {
        ChunkInventory chunks = mock(ChunkInventory.class);
        ConsistentVectorStore vectors = mock(ConsistentVectorStore.class);
        when(chunks.totalChunks(7L)).thenReturn(2);
        when(chunks.doneChunks(7L)).thenReturn(2);
        when(chunks.expectedVectorIds(7L)).thenReturn(Set.of(10L, 20L));
        when(vectors.listIdsByVersion(7L)).thenReturn(Set.of(10L, 99L));
        RecordingIssues issues = new RecordingIssues();
        ReconciliationService service = new ReconciliationService(
                new IndexVerifier(chunks, vectors), issues,
                new ReconciliationService.IssueMetrics() {
                    public void issueObserved(String type) { }
                    public void scanCompleted(boolean clean) { }
                });

        var report = service.scan(7L, 2);

        assertFalse(report.passed());
        assertEquals(Set.of("MISSING_VECTOR", "ORPHAN_VECTOR"),
                issues.observed.stream().map(ConsistencyIssue::issueType)
                        .collect(java.util.stream.Collectors.toSet()));
        assertTrue(issues.resolvedAfterCompleteScan);
    }

    @Test
        // 总行数正确但只有部分 Chunk 完成向量写入时，也必须生成独立 Issue。
    void doneCountMismatchMustNotBeHiddenByTotalCount() {
        ChunkInventory chunks = mock(ChunkInventory.class);
        ConsistentVectorStore vectors = mock(ConsistentVectorStore.class);
        when(chunks.totalChunks(7L)).thenReturn(2);
        when(chunks.doneChunks(7L)).thenReturn(1);
        when(chunks.expectedVectorIds(7L)).thenReturn(Set.of(10L));
        when(vectors.listIdsByVersion(7L)).thenReturn(Set.of(10L));
        RecordingIssues issues = new RecordingIssues();
        ReconciliationService service = new ReconciliationService(
                new IndexVerifier(chunks, vectors), issues,
                new ReconciliationService.IssueMetrics() {
                    public void issueObserved(String type) { }
                    public void scanCompleted(boolean clean) { }
                });

        service.scan(7L, 2);

        assertTrue(issues.observed.stream().anyMatch(issue ->
                "MYSQL_DONE_COUNT_MISMATCH".equals(issue.issueType())));
    }

    @Test
    void vectorInventoryFailureMustNotResolveOldIssues() {
        ChunkInventory chunks = mock(ChunkInventory.class);
        ConsistentVectorStore vectors = mock(ConsistentVectorStore.class);
        when(chunks.totalChunks(7L)).thenReturn(2);
        when(chunks.doneChunks(7L)).thenReturn(2);
        when(chunks.expectedVectorIds(7L)).thenReturn(Set.of(10L, 20L));
        when(vectors.listIdsByVersion(7L))
                .thenThrow(new RuntimeException("MILVUS_TIMEOUT"));
        RecordingIssues issues = new RecordingIssues();
        ReconciliationService service = new ReconciliationService(
                new IndexVerifier(chunks, vectors), issues,
                new ReconciliationService.IssueMetrics() {
                    public void issueObserved(String type) { }
                    public void scanCompleted(boolean clean) { }
                });

        assertThrows(RuntimeException.class, () -> service.scan(7L, 2));
        // 清单没有完整读取时绝不能把“没看见”解释成“问题已消失”。
        assertFalse(issues.resolvedAfterCompleteScan);
    }

    @Test
        // 删除中断仍不可见，重试安全收敛。
    void repairPolicyDoesNotAutoDeleteActiveOrphan() {
        RepairPolicy policy = new RepairPolicy();
        assertFalse(policy.decide(
                "ORPHAN_VECTOR", "ACTIVE", "ACTIVE").automatic());
        assertTrue(policy.decide(
                "ORPHAN_VECTOR", "SUPERSEDED", "ACTIVE").automatic());
        assertTrue(policy.decide(
                "ORPHAN_VECTOR", "ACTIVE", "DELETING").automatic());
    }

    @Test
    void repairPolicyNeverUpsertsMissingVectorForDeletingDocument() {
        RepairPolicy policy = new RepairPolicy();

        assertFalse(policy.decide(
                "MISSING_VECTOR", "ACTIVE", "DELETING").automatic());
        assertFalse(policy.decide(
                "MISSING_VECTOR", "ACTIVE", "DELETED").automatic());
    }

    // RecordingIssues 保存本轮 observe 调用和范围关闭时机，让测试直接检查 Reconciler 的持久化顺序。
    private static final class RecordingIssues implements IssueRepository {
        private final List<ConsistencyIssue> observed = new ArrayList<>();
        private boolean resolvedAfterCompleteScan;
        @Override public void observe(String runId, ConsistencyIssue issue) {
            observed.add(issue);
        }
        @Override public void resolveVectorInventoryNotSeen(
                long versionId, String runId) {
            resolvedAfterCompleteScan = true;
        }
    }
}