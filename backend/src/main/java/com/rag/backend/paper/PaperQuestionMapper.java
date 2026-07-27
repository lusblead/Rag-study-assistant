package com.rag.backend.paper;

import com.rag.backend.paper.model.PaperQuestion;
import org.apache.ibatis.annotations.*;
import java.util.List;

@Mapper
public interface PaperQuestionMapper {
    @Insert("INSERT INTO paper_questions(paper_id,question_id,section_key,section_title,section_instructions,question_order,score) VALUES(#{paperId},#{questionId},#{sectionKey},#{sectionTitle},#{sectionInstructions},#{questionOrder},#{score})") int insert(PaperQuestion item);
    @Select("SELECT * FROM paper_questions WHERE paper_id=#{paperId} ORDER BY question_order") List<PaperQuestion> selectByPaperId(Long paperId);
    @Select("SELECT COALESCE(MAX(question_order),0) FROM paper_questions WHERE paper_id=#{paperId}") int maxOrder(Long paperId);
    @Update("UPDATE paper_questions SET section_key=#{sectionKey},section_title=#{sectionTitle},section_instructions=#{sectionInstructions},question_order=#{questionOrder},score=#{score} WHERE paper_id=#{paperId} AND question_id=#{questionId}") int update(PaperQuestion item);
    @Delete("DELETE FROM paper_questions WHERE paper_id=#{paperId} AND question_id=#{questionId}") int deleteOne(@Param("paperId")Long paperId,@Param("questionId")Long questionId);
    @Delete("DELETE FROM paper_questions WHERE question_id=#{questionId}") int deleteByQuestionId(Long questionId);
    @Delete("DELETE FROM paper_questions WHERE paper_id=#{paperId}") int deleteByPaperId(Long paperId);
    @Delete("DELETE FROM paper_questions WHERE paper_id IN (SELECT id FROM papers WHERE course_id=#{courseId})") int deleteByCourseId(Long courseId);
}
