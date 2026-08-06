// 精确注入崩溃，用断言证明可靠性不变量。
package com.rag.backend.ingestionlab.harness;

// 故障点：稳定标记副作用窗口，使故障矩阵可重复。
public enum FailurePoint {
    AFTER_CHUNK_INSERT,
    AFTER_VECTOR_UPSERT_BEFORE_MYSQL_DONE,
    BEFORE_VERIFY,
    BEFORE_ACTIVE_SWITCH,
    AFTER_TOMBSTONE_BEFORE_VECTOR_DELETE
}