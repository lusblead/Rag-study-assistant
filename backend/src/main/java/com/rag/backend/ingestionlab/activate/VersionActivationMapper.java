// 激活事务：锁 Document，校验 READY，切 active 指针并把旧版改为 SUPERSEDED。
package com.rag.backend.ingestionlab.activate;

import org.apache.ibatis.annotations.*;

@Mapper
// VersionActivationMapper 提供激活短事务所需的 Document 行锁、READY 条件更新、指针切换和旧版降级。
public interface VersionActivationMapper {
    @Select("SELECT id FROM documents WHERE id=#{documentId} FOR UPDATE")
    Long lockDocument(long documentId);

    @Select("SELECT active_version_id FROM documents WHERE id=#{documentId}")
    Long currentActive(long documentId);

    @Update("""
        UPDATE document_versions
           SET state='ACTIVE', activated_at=NOW(6), state_version=state_version+1
         WHERE id=#{versionId} AND document_id=#{documentId} AND state='READY'
        """)
    int activateNew(@Param("documentId") long documentId,
                    @Param("versionId") long versionId);

    @Update("""
        UPDATE documents SET active_version_id=#{versionId}
         WHERE id=#{documentId} AND lifecycle_status='ACTIVE'
        """)
    int pointDocumentTo(@Param("documentId") long documentId,
                        @Param("versionId") long versionId);

    @Update("""
        UPDATE document_versions
           SET state='SUPERSEDED', superseded_at=NOW(6), state_version=state_version+1
         WHERE id=#{oldVersionId} AND state='ACTIVE'
        """)
    int supersede(long oldVersionId);
}