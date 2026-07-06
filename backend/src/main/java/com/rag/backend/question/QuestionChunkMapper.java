package com.rag.backend.question;

import com.rag.backend.agent.model.KnowledgeChunk;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface QuestionChunkMapper {
    @Select("""
            <script>
            SELECT * FROM knowledge_chunks
            WHERE course_id = #{courseId}
              AND content IS NOT NULL
              AND content &lt;&gt; ''
            <if test="documentIds != null and documentIds.size() > 0">
              AND document_id IN
              <foreach collection="documentIds" item="id" open="(" separator="," close=")">
                #{id}
              </foreach>
            </if>
            ORDER BY document_id, chunk_index, id
            </script>
            """)
    List<KnowledgeChunk> selectForGeneration(@Param("courseId") Long courseId,
                                             @Param("documentIds") List<Long> documentIds);
}
