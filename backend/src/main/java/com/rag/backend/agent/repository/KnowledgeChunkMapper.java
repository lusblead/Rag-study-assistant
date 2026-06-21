package com.rag.backend.agent.repository;

import com.rag.backend.agent.model.KnowledgeChunk;
import org.apache.ibatis.annotations.*;

@Mapper
public interface KnowledgeChunkMapper {
    @Insert("""
            INSERT INTO knowledge_chunks
            (course_id, document_id, chunk_index, title, content, source_page, token_count, embedding_status)
            VALUES
            (#{courseId}, #{documentId}, #{chunkIndex}, #{title}, #{content}, #{sourcePage}, #{tokenCount}, #{embeddingStatus})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(KnowledgeChunk chunk);

    @Select("SELECT * FROM knowledge_chunks WHERE id = #{id}")
    KnowledgeChunk selectById(Long id);

    @Delete("DELETE FROM knowledge_chunks WHERE document_id = #{documentId}")
    int deleteByDocumentId(long documentId);

    @Delete("DELETE FROM knowledge_chunks WHERE course_id = #{courseId}")
    int deleteByCourseId(long courseId);

    @Update("""
            UPDATE knowledge_chunks
            SET milvus_vector_id = #{milvusVectorId}, embedding_status = #{embeddingStatus}
            WHERE id = #{id}
            """)
    int updateVectorStatus(KnowledgeChunk chunk);
}
