package com.rag.backend.ingestionlab.delete;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/** 文档删除短事务和 Delete Saga 所需的最小 SQL 边界。 */
@Mapper
public interface DeleteRequestMapper {

    @Select("""
        SELECT id, file_path, lifecycle_status
          FROM documents
         WHERE id = #{documentId}
         FOR UPDATE
        """)
    DeleteDocumentRow lockDocument(long documentId);

    @Update("""
        UPDATE documents
           SET lifecycle_status = 'DELETING',
               active_version_id = NULL,
               parse_status = 'DELETING'
         WHERE id = #{documentId}
           AND lifecycle_status = 'ACTIVE'
        """)
    int tombstone(long documentId);

    /**
     * 墓碑事务内同时使所有未结束 INGEST Job 的 Lease token 失效。
     * state_version 递增后，旧 Worker 的续租、Step 回写和 Version 失败回写都会失败。
     */
    @Update("""
        UPDATE ingest_jobs
           SET state = 'CANCELLED',
               lease_owner = NULL,
               lease_until = NULL,
               error_code = 'DOCUMENT_DELETING',
               finished_at = NOW(6),
               state_version = state_version + 1
         WHERE document_id = #{documentId}
           AND job_type = 'INGEST'
           AND state IN ('QUEUED', 'RUNNING', 'RETRY_WAIT')
        """)
    int cancelIngestJobs(long documentId);

    @Select("""
        SELECT id FROM document_versions
         WHERE document_id = #{documentId}
         ORDER BY id
        """)
    List<Long> selectVersionIds(long documentId);

    @Delete("DELETE FROM knowledge_chunks WHERE document_id = #{documentId}")
    int deleteChunks(long documentId);

    @Update("""
        UPDATE documents
           SET lifecycle_status = 'DELETED',
               active_version_id = NULL,
               parse_status = 'DELETED',
               deleted_at = NOW(6)
         WHERE id = #{documentId}
           AND lifecycle_status = 'DELETING'
        """)
    int markDeleted(long documentId);

    class DeleteDocumentRow {
        private Long id;
        private String filePath;
        private String lifecycleStatus;

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public String getFilePath() { return filePath; }
        public void setFilePath(String filePath) { this.filePath = filePath; }
        public String getLifecycleStatus() { return lifecycleStatus; }
        public void setLifecycleStatus(String lifecycleStatus) {
            this.lifecycleStatus = lifecycleStatus;
        }
    }
}
