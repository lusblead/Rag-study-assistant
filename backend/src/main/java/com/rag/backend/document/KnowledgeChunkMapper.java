package com.rag.backend.document;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/**
 * 知识片段 Mapper —— 供文档模块做级联删除等操作。
 * Agent 模块有自己的 KnowledgeChunkRepository 接口，
 * 此处仅暴漏文档模块所需的最小方法集。
 */
@Mapper
public interface KnowledgeChunkMapper {

    /** 统计某个文档下的知识片段数量 */
    @Select("SELECT COUNT(*) FROM knowledge_chunks WHERE document_id = #{documentId}")
    int countByDocumentId(Long documentId);

    /** 删除某个文档下的所有知识片段 */
    @Delete("DELETE FROM knowledge_chunks WHERE document_id = #{documentId}")
    int deleteByDocumentId(Long documentId);
}
