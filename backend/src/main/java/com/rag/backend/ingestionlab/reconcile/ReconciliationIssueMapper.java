// Mapper 用唯一 issueKey upsert 本轮观察，并只在完整的向量清单扫描后按规则范围关闭旧 Issue。
package com.rag.backend.ingestionlab.reconcile;

import org.apache.ibatis.annotations.*;

@Mapper
// ReconciliationIssueMapper：数据访问契约，关键写入依赖唯一键或条件更新。
public interface ReconciliationIssueMapper {
    // issue_key 冲突时更新同一问题和 last_seen_run_id，避免每轮扫描重复告警。
    @Insert("""
        INSERT INTO reconciliation_issues
        (issue_id, issue_key, document_version_id, issue_type, subject_key,
         expected_value, actual_value, suggested_action, state,
         last_seen_run_id, first_seen_at, last_seen_at)
        VALUES
        (#{issueId}, #{issue.issueKey}, #{issue.documentVersionId},
         #{issue.issueType}, #{issue.subjectKey}, #{issue.expectedValue},
         #{issue.actualValue}, #{issue.suggestedAction}, 'OPEN',
         #{runId}, NOW(6), NOW(6))
        ON DUPLICATE KEY UPDATE
         expected_value=VALUES(expected_value),
         actual_value=VALUES(actual_value),
         suggested_action=VALUES(suggested_action),
         -- 扫描可以更新正在修复问题的观察时间，但不能夺走 Repair Worker 的所有权。
         state=IF(state='REPAIRING', 'REPAIRING', 'OPEN'),
         state_version=state_version
             + IF(state='REPAIRING', 0, 1),
         last_seen_run_id=VALUES(last_seen_run_id),
         last_seen_at=NOW(6), resolved_at=NULL, resolution_note=NULL
        """)
    int observe(@Param("issueId") String issueId,
                @Param("runId") String runId,
                @Param("issue") ConsistencyIssue issue);

    // 仅完整的“向量清单”扫描结束后调用。
    // issue_type 白名单就是本次实际执行的规则范围，避免误关 OCR、Artifact 等未扫描问题。
    @Update("""
        UPDATE reconciliation_issues
           SET state='RESOLVED', resolved_at=NOW(6),
               resolution_note='not observed in completed scan'
         WHERE document_version_id=#{versionId}
           AND issue_type IN (
               'MYSQL_CHUNK_COUNT_MISMATCH',
               'MYSQL_DONE_COUNT_MISMATCH',
               'MISSING_VECTOR',
               'ORPHAN_VECTOR'
           )
           AND state='OPEN'
           AND last_seen_run_id <> #{runId}
        """)
    int resolveVectorInventoryNotSeen(@Param("versionId") long versionId,
                                      @Param("runId") String runId);
}