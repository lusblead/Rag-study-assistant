package com.rag.backend.question;

import com.rag.backend.question.model.Question;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface QuestionMapper {

    @Insert("INSERT INTO questions (course_id, source_chunk_id, type, stem, options, answer, explanation, difficulty, knowledge_point) " +
            "VALUES (#{courseId}, #{sourceChunkId}, #{type}, #{stem}, #{options}, #{answer}, #{explanation}, #{difficulty}, #{knowledgePoint})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Question question);

    @Select("SELECT * FROM questions WHERE id = #{id}")
    Question selectById(Long id);

    @Select("<script>" +
        "SELECT * FROM questions" +
        " WHERE course_id = #{courseId}" +
        "<if test='type != null and type != \"\"'>" +
        " AND type = #{type}" +
        "</if>" +
        "<if test='difficulty != null and difficulty != \"\"'>" +
        " AND difficulty = #{difficulty}" +
        "</if>" +
        " ORDER BY created_at DESC" +
        "</script>")
    List<Question> selectListByCourse(@Param("courseId") Long courseId,
                                      @Param("type") String type,
                                      @Param("difficulty") String difficulty);

    @Delete("DELETE FROM questions WHERE id = #{id}")
    int deleteById(Long id);
}
