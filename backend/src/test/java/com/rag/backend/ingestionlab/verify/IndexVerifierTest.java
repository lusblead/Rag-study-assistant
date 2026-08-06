// 先做集合验证，再用短事务切换在线版本。
package com.rag.backend.ingestionlab.verify;

import com.rag.backend.ingestionlab.vector.ConsistentVectorStore;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

// 证明“数量相等但 ID 不同”仍失败，且完全相同的 DONE/向量集合才能通过。
class IndexVerifierTest {

    @Test
    void equalCountsButDifferentIdsMustFail() {
        ChunkInventory chunks = mock(ChunkInventory.class);
        ConsistentVectorStore vectors = mock(ConsistentVectorStore.class);
        when(chunks.totalChunks(9L)).thenReturn(2);
        when(chunks.doneChunks(9L)).thenReturn(2);
        when(chunks.expectedVectorIds(9L)).thenReturn(Set.of(100L, 200L));
        when(vectors.listIdsByVersion(9L)).thenReturn(Set.of(100L, 999L));

        var report = new IndexVerifier(chunks, vectors).verify(9L, 2);

        assertFalse(report.passed());
        assertEquals(Set.of(200L), report.missingVectorIds());
        assertEquals(Set.of(999L), report.orphanVectorIds());
        assertEquals(2, report.vectorCount());
    }

    @Test
    void exactSetAndDoneCountPass() {
        ChunkInventory chunks = mock(ChunkInventory.class);
        ConsistentVectorStore vectors = mock(ConsistentVectorStore.class);
        when(chunks.totalChunks(9L)).thenReturn(2);
        when(chunks.doneChunks(9L)).thenReturn(2);
        when(chunks.expectedVectorIds(9L)).thenReturn(Set.of(100L, 200L));
        when(vectors.listIdsByVersion(9L)).thenReturn(Set.of(200L, 100L));

        assertTrue(new IndexVerifier(chunks, vectors).verify(9L, 2).passed());
    }
}