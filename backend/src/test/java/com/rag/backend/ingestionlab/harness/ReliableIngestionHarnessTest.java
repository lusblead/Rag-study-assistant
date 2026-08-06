package com.rag.backend.ingestionlab.harness;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

// 在激活、向量回写和删除墓碑窗口注入崩溃，验证可见性与确定性重放不变量。
class ReliableIngestionHarnessTest {

    @Test
    void failedRebuildBeforeSwitchKeepsOldVersionVisible() {
        ReliableIngestionHarness h = new ReliableIngestionHarness();
        h.createDocument(10L);
        long v1 = h.createVersion(10L, "content-v1", "pipeline-v1");
        h.resume(v1, List.of("a", "b"), FaultInjector.none());
        assertEquals(v1, h.activeVersion(10L));

        long v2 = h.createVersion(10L, "content-v2", "pipeline-v1");
        assertThrows(FaultInjector.InjectedCrash.class,
                () -> h.resume(v2, List.of("new-a", "new-b"),
                        FaultInjector.failOnce(FailurePoint.BEFORE_ACTIVE_SWITCH, -1)));

        assertTrue(h.isVisible(10L));
        assertEquals(v1, h.activeVersion(10L), "INV-3: 旧 active 不变");

        // 重启后 READY 版本只重放 activation，不重新生成 Chunk/Vector。
        h.resume(v2, List.of("new-a", "new-b"), FaultInjector.none());
        assertEquals(v2, h.activeVersion(10L));
        assertEquals(2, h.logicalChunkCount(v2));
        assertEquals(2, h.vectorCount(v2));
    }

    @Test
        // 验证重放读取制品而不重复昂贵调用。
    void vectorResponseLossThenReplayDoesNotDuplicate() {
        ReliableIngestionHarness h = new ReliableIngestionHarness();
        h.createDocument(10L);
        long v1 = h.createVersion(10L, "content", "pipeline");
        FaultInjector failOnce = FaultInjector.failOnce(
                FailurePoint.AFTER_VECTOR_UPSERT_BEFORE_MYSQL_DONE, 1);

        assertThrows(FaultInjector.InjectedCrash.class,
                () -> h.resume(v1, List.of("a", "b", "c"), failOnce));
        assertEquals(2, h.vectorCount(v1), "第 2 个远端已提交");

        h.resume(v1, List.of("a", "b", "c"), FaultInjector.none());
        assertEquals(3, h.logicalChunkCount(v1));
        assertEquals(3, h.vectorCount(v1), "INV-2: 重放无重复向量");
        assertTrue(h.verify(v1).passed());
    }

    @Test
    void duplicateResumeIsIdempotent() {
        ReliableIngestionHarness h = new ReliableIngestionHarness();
        h.createDocument(10L);
        long v1 = h.createVersion(10L, "same", "same-pipeline");
        h.resume(v1, List.of("a", "b"), FaultInjector.none());
        h.resume(v1, List.of("a", "b"), FaultInjector.none());

        assertEquals(2, h.logicalChunkCount(v1));
        assertEquals(2, h.vectorCount(v1));
    }

    @Test
        // 删除中断仍不可见，重试安全收敛。 故障窗口崩溃后重放，检查三条核心不变量。
    void deleteDependencyFailureStillMakesDocumentInvisible() {
        ReliableIngestionHarness h = new ReliableIngestionHarness();
        h.createDocument(10L);
        long v1 = h.createVersion(10L, "same", "pipeline");
        h.resume(v1, List.of("a"), FaultInjector.none());

        assertThrows(FaultInjector.InjectedCrash.class,
                () -> h.deleteDocument(10L, FaultInjector.failOnce(
                        FailurePoint.AFTER_TOMBSTONE_BEFORE_VECTOR_DELETE, -1)));
        assertFalse(h.isVisible(10L), "INV-1: tombstone 已生效");

        h.deleteDocument(10L, FaultInjector.none());
        assertEquals(0, h.vectorCount(v1));
        assertFalse(h.isVisible(10L));
    }

    @Test
        // 验证稳定身份对相同输入一致、对关键变化敏感。
    void sameContentAndPipelineReuseTheSameVersionIdentity() {
        ReliableIngestionHarness h = new ReliableIngestionHarness();
        h.createDocument(10L);
        long first = h.createVersion(10L, "hash", "pipeline");
        long duplicate = h.createVersion(10L, "hash", "pipeline");
        assertEquals(first, duplicate);
    }
}