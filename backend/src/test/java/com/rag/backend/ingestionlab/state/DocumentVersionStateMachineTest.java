// 证明正常路径逐门禁推进，未验证版本不能 ACTIVE。
package com.rag.backend.ingestionlab.state;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

// 该测试只验证 Version 状态契约；Job Lease 和数据库 CAS 由后续测试负责。
class DocumentVersionStateMachineTest {

    @Test
        // 验证状态门禁，未验证/失败版本不可直接可见。
    void happyPathMustPassEveryGate() {
        DocumentVersionState[] path = {
                DocumentVersionState.UPLOADED, DocumentVersionState.BUILDING,
                DocumentVersionState.PARSING, DocumentVersionState.CHUNKING,
                DocumentVersionState.EMBEDDING, DocumentVersionState.INDEXING,
                DocumentVersionState.VERIFYING, DocumentVersionState.READY,
                DocumentVersionState.ACTIVE
        };
        for (int i = 0; i < path.length - 1; i++) {
            assertTrue(DocumentVersionStateMachine.canTransition(
                    path[i], path[i + 1]));
        }
    }

    @Test
        // 验证状态门禁，未验证/失败版本不可直接可见。
    void cannotPublishAnUnverifiedVersion() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> DocumentVersionStateMachine.requireAllowed(
                        DocumentVersionState.INDEXING, DocumentVersionState.ACTIVE));
        assertEquals("Illegal ingest transition: INDEXING -> ACTIVE", error.getMessage());
    }

    @Test
        // 验证状态门禁，未验证/失败版本不可直接可见。
    void failedVersionIsNeverReadable() {
        assertFalse(DocumentVersionState.FAILED.isVersionReadable());
        assertFalse(DocumentVersionState.READY.isVersionReadable(),
                "READY 只表示验证完成，尚未成为当前 active version");
        assertTrue(DocumentVersionState.ACTIVE.isVersionReadable());
    }

    @Test
        // 验证状态门禁，未验证/失败版本不可直接可见。
    void inconsistentVersionMustBeRepairedAndReverified() {
        assertTrue(DocumentVersionStateMachine.canTransition(
                DocumentVersionState.INCONSISTENT, DocumentVersionState.REPAIRING));
        assertTrue(DocumentVersionStateMachine.canTransition(
                DocumentVersionState.REPAIRING, DocumentVersionState.VERIFYING));
        assertFalse(DocumentVersionStateMachine.canTransition(
                DocumentVersionState.INCONSISTENT, DocumentVersionState.ACTIVE));
    }
}