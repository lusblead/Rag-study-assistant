// 唯一键负责去重，owner/stateVersion/期限条件负责并发提交权。
package com.rag.backend.ingestionlab.outbox;

import org.apache.ibatis.annotations.*;

@Mapper
// DocumentVersionMapper：数据访问契约，关键写入依赖唯一键或条件更新。
public interface DocumentVersionMapper {
    // 先锁 document 行，把查重、计算下一个 versionNo 和插入串行化在同一事务中。
    @Select("SELECT id FROM documents WHERE id = #{documentId} FOR UPDATE")
    Long lockDocument(Long documentId);

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
         state, state_version)
        VALUES
        (#{documentId}, #{versionNo}, #{contentHash}, #{pipelineFingerprint},
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
}