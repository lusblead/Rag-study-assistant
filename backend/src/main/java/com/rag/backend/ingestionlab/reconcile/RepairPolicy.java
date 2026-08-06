// RepairPolicy 只把问题事实映射为有限动作和自动化许可，不在决策过程中访问任何存储。
package com.rag.backend.ingestionlab.reconcile;

// 修复白名单：未知动作默认人工，避免误删在线数据。
public final class RepairPolicy {
    // 封闭白名单决策，未知类型默认人工。
    public Decision decide(String issueType, String versionState,
                           String documentLifecycle) {
        // 封闭分支只接受稳定机器码，default 采用保守策略。
        return switch (issueType) {
            case "MISSING_VECTOR" -> {
                // 删除态的正确收敛方向是继续删除；补向量会与 Delete Saga 对抗并复活数据。
                boolean indexableDocument =
                        !"DELETING".equals(documentLifecycle)
                                && !"DELETED".equals(documentLifecycle);
                boolean repairableVersion =
                        "BUILDING".equals(versionState)
                                || "VERIFYING".equals(versionState)
                                || "READY".equals(versionState)
                                || "ACTIVE".equals(versionState);
                yield indexableDocument && repairableVersion
                        ? new Decision(true, "UPSERT_MISSING_VECTOR")
                        : new Decision(false, "DOCUMENT_NOT_INDEXABLE");
            }
            case "ORPHAN_VECTOR" -> {
                boolean safeToDelete = "SUPERSEDED".equals(versionState)
                        || "DELETING".equals(documentLifecycle)
                        || "DELETED".equals(documentLifecycle);
                yield safeToDelete
                        ? new Decision(true, "DELETE_ORPHAN_VECTOR")
                        : new Decision(false, "MANUAL_REVIEW");
            }
            case "MYSQL_CHUNK_COUNT_MISMATCH" ->
                    new Decision(false, "REBUILD_VERSION");
            case "MYSQL_DONE_COUNT_MISMATCH" ->
                    new Decision(false, "RETRY_OR_REBUILD_VERSION");
            case "VECTOR_SCHEMA_MISMATCH" ->
                    new Decision(false, "CREATE_NEW_INDEX_VERSION");
            case "VECTOR_MODEL_MISMATCH" ->
                    new Decision(false, "REBUILD_VERSION");
            default -> new Decision(false, "MANUAL_REVIEW");
        };
    }

    // Decision 同时给出“能否自动执行”和白名单动作；RepairExecutor 必须再次核对前置条件。
    public record Decision(boolean automatic, String action) { }
}