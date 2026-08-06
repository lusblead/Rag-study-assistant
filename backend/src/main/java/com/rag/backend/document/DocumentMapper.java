package com.rag.backend.document;

import com.rag.backend.document.model.CourseDocument;
import org.apache.ibatis.annotations.*;

import java.util.List;
import java.util.Set;

@Mapper
public interface DocumentMapper {

    @Insert("INSERT INTO documents (course_id, filename, file_type, file_path, parse_status, chunk_count) " +
            "VALUES (#{courseId}, #{filename}, #{fileType}, #{filePath}, #{parseStatus}, #{chunkCount})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(CourseDocument doc);

    @Select("SELECT * FROM documents WHERE id = #{id}")
    CourseDocument selectById(Long id);

    @Select("""
            SELECT * FROM documents
            WHERE course_id = #{courseId}
              AND lifecycle_status <> 'DELETED'
            ORDER BY created_at DESC
            """)
    List<CourseDocument> selectListByCourseId(Long courseId);

    /**
     * 只返回文档指针与 Version 状态一致的在线版本。
     * 这条查询是 Retriever 的第一道可见性门禁。
     */
    @Select("""
            SELECT d.active_version_id
              FROM documents d
              JOIN document_versions v ON v.id = d.active_version_id
             WHERE d.course_id = #{courseId}
               AND d.lifecycle_status = 'ACTIVE'
               AND v.state = 'ACTIVE'
            """)
    Set<Long> selectActiveVersionIdsByCourseId(long courseId);

    @Update("""
            UPDATE documents
               SET parse_status=#{parseStatus}, chunk_count=#{chunkCount}
             WHERE id=#{id}
               AND lifecycle_status='ACTIVE'
            """)
    int updateParseStatus(CourseDocument doc);

    @Delete("DELETE FROM knowledge_chunks WHERE document_id = #{documentId}")
    int deleteKnowledgeChunksByDocumentId(Long documentId);

    @Delete("DELETE FROM documents WHERE id = #{id}")
    int deleteById(Long id);

    @Delete("DELETE FROM documents WHERE course_id = #{courseId}")
    int deleteByCourseId(Long courseId);
}
