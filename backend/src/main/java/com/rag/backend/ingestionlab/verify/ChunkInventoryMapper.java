package com.rag.backend.ingestionlab.verify;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Set;

/**
 * 只负责读取某个文档版本在 MySQL 中的 Chunk 清单。
 * IndexVerifier 不直接依赖 MyBatis，所以中间仍保留 ChunkInventory 端口。
 */
@Mapper
public interface ChunkInventoryMapper {

    /**
     * 统计该版本已经落库的全部 Chunk。
     * 这里不能按 courseId 或 documentId 统计，否则重建时会把旧版本一起算进去。
     */
    @Select("""
        SELECT COUNT(*)
          FROM knowledge_chunks
         WHERE document_version_id = #{versionId}
        """)
    int countAll(@Param("versionId") long versionId);

    /**
     * 只统计向量写入已经完成（DONE）的 Chunk。
     * 行存在不等于向量已经成功写入并回写状态。
     */
    @Select("""
        SELECT COUNT(*)
          FROM knowledge_chunks
         WHERE document_version_id = #{versionId}
           AND embedding_status = 'DONE'
        """)
    int countDone(@Param("versionId") long versionId);

    /**
     * 返回 MySQL 为该版本预留的稳定 vectorId 集合。
     * 使用 Set 是为了让重复 ID 在集合大小检查中暴露，而不是被总行数掩盖。
     */
    @Select("""
        SELECT vector_business_id
          FROM knowledge_chunks
         WHERE document_version_id = #{versionId}
           AND vector_business_id IS NOT NULL
        """)
    Set<Long> findExpectedVectorIds(@Param("versionId") long versionId);
}