package com.rag.backend.practice;

import com.rag.backend.practice.model.PracticeRecord;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface PracticeMapper {

    @Insert("INSERT INTO practice_records (course_id, question_id, user_answer, is_correct, grading_mode, grading_feedback, answer_payload, score, max_score, grading_status) " +
            "VALUES (#{courseId}, #{questionId}, #{userAnswer}, #{isCorrect}, #{gradingMode}, #{gradingFeedback}, #{answerPayload}, #{score}, #{maxScore}, #{gradingStatus})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(PracticeRecord record);

    @Select("SELECT * FROM practice_records WHERE id = #{id}")
    PracticeRecord selectById(Long id);

    @Update("UPDATE practice_records SET score=#{score},max_score=#{maxScore},grading_feedback=#{gradingFeedback},grading_mode='manual',grading_status='graded',is_correct=#{isCorrect} WHERE id=#{id}")
    int updateManualGrade(PracticeRecord record);

    @Select("SELECT * FROM practice_records WHERE course_id = #{courseId} ORDER BY created_at DESC")
    List<PracticeRecord> selectListByCourseId(Long courseId);

    @Select("SELECT * FROM practice_records WHERE course_id = #{courseId} AND is_correct = FALSE ORDER BY created_at DESC")
    List<PracticeRecord> selectWrongByCourseId(Long courseId);

    @Delete("DELETE FROM practice_records WHERE course_id = #{courseId}")
    int deleteByCourseId(Long courseId);

    @Delete("DELETE FROM practice_records WHERE question_id = #{questionId}")
    int deleteByQuestionId(Long questionId);

    @Delete("DELETE FROM practice_records WHERE question_id IN (SELECT id FROM questions WHERE batch_id = #{batchId})")
    int deleteByQuestionBatchId(Long batchId);
}
