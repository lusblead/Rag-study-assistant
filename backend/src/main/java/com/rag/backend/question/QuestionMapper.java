package com.rag.backend.question;

import com.rag.backend.question.model.Question;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface QuestionMapper {

    @Insert("INSERT INTO questions (course_id, source_chunk_id, batch_id, type, stem, options, answer, explanation, difficulty, knowledge_point, chapter_tags, question_data, answer_schema, subject, grading_strategy) " +
            "VALUES (#{courseId}, #{sourceChunkId}, #{batchId}, #{type}, #{stem}, #{options}, #{answer}, #{explanation}, #{difficulty}, #{knowledgePoint}, #{chapterTags}, #{questionData}, #{answerSchema}, #{subject}, #{gradingStrategy})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Question question);

    @Update("UPDATE questions SET type=#{type},stem=#{stem},options=#{options},answer=#{answer},explanation=#{explanation},difficulty=#{difficulty},knowledge_point=#{knowledgePoint},chapter_tags=#{chapterTags},question_data=#{questionData},answer_schema=#{answerSchema},subject=#{subject},grading_strategy=#{gradingStrategy} WHERE id=#{id}")
    int update(Question question);

    @Select("SELECT q.*, kc.document_id AS source_document_id FROM questions q " +
            "LEFT JOIN knowledge_chunks kc ON kc.id = q.source_chunk_id WHERE q.id = #{id}")
    Question selectById(Long id);

    @Select("<script>" +
        "SELECT q.*, kc.document_id AS source_document_id FROM questions q" +
        " LEFT JOIN knowledge_chunks kc ON kc.id = q.source_chunk_id" +
        " WHERE q.course_id = #{courseId}" +
        "<if test='type != null and type != \"\"'>" +
        " AND q.type = #{type}" +
        "</if>" +
        "<if test='subject != null and subject != \"\"'>" +
        " AND q.subject = #{subject}" +
        "</if>" +
        "<if test='difficulty != null and difficulty != \"\"'>" +
        " AND q.difficulty = #{difficulty}" +
        "</if>" +
        " ORDER BY q.created_at DESC" +
        "</script>")
    List<Question> selectListByCourse(@Param("courseId") Long courseId,
                                      @Param("type") String type,
                                      @Param("difficulty") String difficulty,
                                      @Param("subject") String subject);

    @Delete("DELETE FROM questions WHERE id = #{id}")
    int deleteById(Long id);

    @Delete("DELETE FROM questions WHERE batch_id = #{batchId}")
    int deleteByBatchId(Long batchId);

    @Delete("DELETE FROM questions WHERE course_id = #{courseId}")
    int deleteByCourseId(Long courseId);
}
