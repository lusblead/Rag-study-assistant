// 唯一键负责去重，owner/stateVersion/期限条件负责并发提交权。
package com.rag.backend.ingestionlab.outbox;

import org.apache.ibatis.annotations.*;

import java.time.LocalDateTime;

@Mapper
// DocumentVersionMapper：数据访问契约，关键写入依赖唯一键或条件更新。
public interface DocumentVersionMapper {
    // 先锁 document 行，把查重、计算下一个 versionNo 和插入串行化在同一事务中。
    @Select("""
        SELECT id, lifecycle_status
          FROM documents
         WHERE id = #{documentId}
         FOR UPDATE
        """)
    LockedDocumentRow lockDocument(Long documentId);

    // 用 documentId、contentHash 和 pipelineFingerprint 查同一逻辑版本，重复提交直接复用。
    @Select("""
        SELECT * FROM document_versions
         WHERE document_id = #{documentId}
           AND content_hash = #{contentHash}
           AND pipeline_fingerprint = #{fingerprint}
        """)
    DocumentVersionRow findIdentity(@Param("documentId") long documentId,
                                    @Param("contentHash") String contentHash,
                                    @Param("fingerprint") String fingerprint);

    // 在 document 行锁保护下计算递增版本号；唯一约束仍承担最终并发防线。
    @Select("""
        SELECT COALESCE(MAX(version_no), 0) + 1
          FROM document_versions WHERE document_id = #{documentId}
        """)
    int nextVersionNo(long documentId);

    // 新版本先写为 UPLOADED；Job 与 Outbox 也成功后才通过 CAS 转为 BUILDING。
    // QUEUED 属于 IngestJobState，不能写进 DocumentVersionState。
    @Insert("""
        INSERT INTO document_versions
        (document_id, version_no, content_hash, pipeline_fingerprint,
         source_ref, pipeline_manifest, manifest_schema_version,
         state, state_version)
        VALUES
        (#{documentId}, #{versionNo}, #{contentHash}, #{pipelineFingerprint},
         #{sourceRef}, CAST(#{pipelineManifest} AS JSON), #{manifestSchemaVersion},
         'UPLOADED', 0)
        """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(DocumentVersionRow row);

    // 状态迁移检查来源状态与 stateVersion，禁止旧 Worker 跳状态或覆盖新进度。
    @Update("""
        UPDATE document_versions
           SET state = #{to}, state_version = state_version + 1
         WHERE id = #{id} AND state = #{from} AND state_version = #{expectedVersion}
        """)
    int transition(@Param("id") long id, @Param("from") String from,
                   @Param("to") String to, @Param("expectedVersion") long expectedVersion);

    /**
     * 只有仍持有对应 INGEST Job Lease 的 Worker 才能把 Version 标为 FAILED。
     * 这条联表条件更新把 Job 所有权和 Version CAS 放进同一条 SQL，避免旧 Worker
     * 在租约过期、另一实例已接管后覆盖新版状态。
     */
    @Update("""
        UPDATE document_versions v
        JOIN ingest_jobs j
          ON j.document_version_id = v.id
         AND j.job_id = #{jobId}
         AND j.job_type = 'INGEST'
           SET v.state = 'FAILED',
               v.state_version = v.state_version + 1
         WHERE v.id = #{versionId}
           AND v.state = #{fromState}
           AND v.state_version = #{versionStateVersion}
           AND j.state = 'RUNNING'
           AND j.lease_owner = #{leaseOwner}
           AND j.lease_until >= #{now}
           AND j.state_version = #{jobStateVersion}
        """)
    int failIfJobOwned(
            @Param("versionId") long versionId,
            @Param("fromState") String fromState,
            @Param("versionStateVersion") long versionStateVersion,
            @Param("jobId") String jobId,
            @Param("leaseOwner") String leaseOwner,
            @Param("jobStateVersion") long jobStateVersion,
            @Param("now") LocalDateTime now);


    /**
     * 读取状态、stateVersion 和 expectedChunkCount。
     * VersionTransitionService 和 VerificationService 都从同一份持久化事实做判断。
     */
    @Select("""
    SELECT id, document_id, version_no, content_hash, pipeline_fingerprint,
           source_ref, pipeline_manifest, manifest_schema_version,
           state, state_version, expected_chunk_count
      FROM document_versions
     WHERE id = #{versionId}
    """)
    DocumentVersionRow findById(@Param("versionId") long versionId);

    /**
     * 在同一个 MySQL 条件更新中保存核验报告并结束 VERIFYING。
     *
     * expectedStateVersion 防止旧 Worker 用过期报告覆盖新一轮核验；
     * targetState 只能由 Service 在状态机校验后传入 READY 或 INCONSISTENT。
     */
    @Update("""
    UPDATE document_versions
       SET state = #{targetState},
           verification_digest = #{digest},
           verification_report = CAST(#{reportJson} AS JSON),
           ready_at = CASE WHEN #{targetState} = 'READY' THEN NOW(6) ELSE ready_at END,
           state_version = state_version + 1
     WHERE id = #{versionId}
       AND state = 'VERIFYING'
       AND state_version = #{expectedStateVersion}
    """)
    int completeVerification(
            @Param("versionId") long versionId,
            @Param("expectedStateVersion") long expectedStateVersion,
            @Param("targetState") String targetState,
            @Param("digest") String digest,
            @Param("reportJson") String reportJson);

    /**
     * 切块完成后保存预期 Chunk 数，带 stateVersion CAS。
     */
    @Update("""
        UPDATE document_versions
           SET expected_chunk_count = #{count},
               state_version = state_version + 1
         WHERE id = #{versionId}
           AND state_version = #{expectedVersion}
        """)
    int updateExpectedChunkCount(
            @Param("versionId") long versionId,
            @Param("count") int count,
            @Param("expectedVersion") long expectedVersion);

    class LockedDocumentRow {
        private Long id;
        private String lifecycleStatus;

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public String getLifecycleStatus() { return lifecycleStatus; }
        public void setLifecycleStatus(String lifecycleStatus) {
            this.lifecycleStatus = lifecycleStatus;
        }
    }
}
