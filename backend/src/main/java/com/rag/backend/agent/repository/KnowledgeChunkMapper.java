package com.rag.backend.agent.repository;

import com.rag.backend.agent.model.KnowledgeChunk;
import com.rag.backend.agent.retrieval.LexicalCandidateRow;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
// 封装知识片段表的 MyBatis SQL 操作。
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

    @Select("""
            <script>
            SELECT kc.id AS chunk_id,
                   kc.document_id,
                   COALESCE(NULLIF(d.filename, ''), kc.title) AS document_name,
                   kc.title,
                   kc.content,
                   kc.source_page,
                   MATCH(kc.title, kc.content)
                     AGAINST (#{query} IN NATURAL LANGUAGE MODE) AS raw_score
              FROM knowledge_chunks kc
              LEFT JOIN documents d ON d.id = kc.document_id
             WHERE kc.course_id = #{courseId}
               AND kc.document_version_id IN
               <foreach collection="activeVersionIds" item="versionId"
                        open="(" separator="," close=")">
                 #{versionId}
               </foreach>
               AND MATCH(kc.title, kc.content)
                     AGAINST (#{query} IN NATURAL LANGUAGE MODE) &gt; 0
               AND MATCH(kc.title, kc.content)
                     AGAINST (#{query} IN NATURAL LANGUAGE MODE) &gt;= #{threshold}
             ORDER BY raw_score DESC, kc.id ASC
             LIMIT #{candidateK}
            </script>
            """)
    @Results(id = "lexicalCandidateRow", value = {
            @Result(property = "chunkId", column = "chunk_id"),
            @Result(property = "documentId", column = "document_id"),
            @Result(property = "documentName", column = "document_name"),
            @Result(property = "title", column = "title"),
            @Result(property = "content", column = "content"),
            @Result(property = "sourcePage", column = "source_page"),
            @Result(property = "rawScore", column = "raw_score")
    })
    List<LexicalCandidateRow> selectLexicalNatural(
            @Param("courseId") long courseId,
            @Param("activeVersionIds") List<Long> activeVersionIds,
            @Param("query") String query,
            @Param("threshold") double threshold,
            @Param("candidateK") int candidateK);

    @Select("""
            <script>
            SELECT kc.id AS chunk_id,
                   kc.document_id,
                   COALESCE(NULLIF(d.filename, ''), kc.title) AS document_name,
                   kc.title,
                   kc.content,
                   kc.source_page,
                   MATCH(kc.title, kc.content)
                     AGAINST (#{query} IN BOOLEAN MODE) AS raw_score
              FROM knowledge_chunks kc
              LEFT JOIN documents d ON d.id = kc.document_id
             WHERE kc.course_id = #{courseId}
               AND kc.document_version_id IN
               <foreach collection="activeVersionIds" item="versionId"
                        open="(" separator="," close=")">
                 #{versionId}
               </foreach>
               AND MATCH(kc.title, kc.content)
                     AGAINST (#{query} IN BOOLEAN MODE) &gt; 0
               AND MATCH(kc.title, kc.content)
                     AGAINST (#{query} IN BOOLEAN MODE) &gt;= #{threshold}
             ORDER BY raw_score DESC, kc.id ASC
             LIMIT #{candidateK}
            </script>
            """)
    @ResultMap("lexicalCandidateRow")
    List<LexicalCandidateRow> selectLexicalBooleanPhrase(
            @Param("courseId") long courseId,
            @Param("activeVersionIds") List<Long> activeVersionIds,
            @Param("query") String query,
            @Param("threshold") double threshold,
            @Param("candidateK") int candidateK);

    @Select("""
            SELECT * FROM knowledge_chunks
            WHERE course_id = #{courseId}
            ORDER BY created_at DESC, id DESC
            LIMIT #{limit}
            """)
    List<KnowledgeChunk> selectByCourseId(@Param("courseId") long courseId, @Param("limit") int limit);

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
