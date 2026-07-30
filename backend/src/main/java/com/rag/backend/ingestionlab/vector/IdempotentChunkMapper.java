// chunk 协议：versionId 与 businessKey 唯一，重放回读同一行再继续向量写入。
package com.rag.backend.ingestionlab.vector;

import org.apache.ibatis.annotations.*;

@Mapper
// IdempotentChunkMapper：数据访问契约，关键写入依赖唯一键或条件更新。
public interface IdempotentChunkMapper {
    @Insert("""
        INSERT INTO knowledge_chunks
        (course_id, document_id, document_version_id, chunk_index, title,
         content, source_page, token_count, chunk_business_key, content_hash,
         embedding_status)
        VALUES
        (#{courseId}, #{documentId}, #{documentVersionId}, #{chunkIndex}, #{title},
         #{content}, #{sourcePage}, #{tokenCount}, #{businessKey}, #{contentHash},
         'PENDING')
        """)
    int insert(ChunkWriteRepository.ChunkDraft draft);

    @Select("""
        SELECT id, document_version_id, chunk_business_key, content_hash,
               embedding_status AS status,
               vector_business_id AS vector_id, embedding_model
          FROM knowledge_chunks
         WHERE document_version_id=#{versionId} AND chunk_business_key=#{businessKey}
        """)
    ChunkRowBean find(@Param("versionId") long versionId,
                      @Param("businessKey") String businessKey);

    @Update("""
        UPDATE knowledge_chunks
           SET vector_business_id=#{vectorId},
               milvus_vector_id=CAST(#{vectorId} AS CHAR),
               embedding_model=#{embeddingModel},
               embedding_dimension=#{dimension}
         WHERE id=#{chunkId}
           AND (vector_business_id IS NULL OR vector_business_id=#{vectorId})
           AND (embedding_model IS NULL OR embedding_model=#{embeddingModel})
           AND (embedding_dimension IS NULL OR embedding_dimension=#{dimension})
        """)
    int reserveVectorIdentity(@Param("chunkId") long chunkId,
                              @Param("vectorId") long vectorId,
                              @Param("embeddingModel") String embeddingModel,
                              @Param("dimension") int dimension);

    @Update("""
        UPDATE knowledge_chunks
           SET embedding_status='DONE', vector_business_id=#{vectorId},
               milvus_vector_id=CAST(#{vectorId} AS CHAR),
               embedding_model=#{embeddingModel},
               embedding_error_code=NULL
         WHERE id=#{chunkId}
           AND (embedding_status <> 'DONE' OR vector_business_id=#{vectorId})
        """)
    int markDone(@Param("chunkId") long chunkId,
                 @Param("vectorId") long vectorId,
                 @Param("embeddingModel") String embeddingModel);

    @Update("""
        UPDATE knowledge_chunks
           SET embedding_status='FAILED',
               embedding_error_code=#{errorCode}
         WHERE id=#{chunkId} AND embedding_status <> 'DONE'
        """)
    int markFailed(@Param("chunkId") long chunkId,
                   @Param("errorCode") String errorCode);

    // ChunkRowBean：数据库映射模型，保存可恢复协议的持久化事实。
    class ChunkRowBean {
        private Long id;
        private Long documentVersionId;
        private String chunkBusinessKey;
        private String contentHash;
        private String status;
        private Long vectorId;
        // 参与身份与审计，换模型不能复用旧向量。
        private String embeddingModel;
        public Long getId() { return id; }
        public void setId(Long value) { id = value; }
        public Long getDocumentVersionId() { return documentVersionId; }
        public void setDocumentVersionId(Long value) { documentVersionId = value; }
        public String getChunkBusinessKey() { return chunkBusinessKey; }
        public void setChunkBusinessKey(String value) { chunkBusinessKey = value; }
        public String getContentHash() { return contentHash; }
        public void setContentHash(String value) { contentHash = value; }
        public String getStatus() { return status; }
        public void setStatus(String value) { status = value; }
        public Long getVectorId() { return vectorId; }
        public void setVectorId(Long value) { vectorId = value; }
        public String getEmbeddingModel() { return embeddingModel; }
        public void setEmbeddingModel(String value) { embeddingModel = value; }
    }
}