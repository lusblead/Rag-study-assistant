package com.rag.backend.practice;

import com.rag.backend.practice.model.PracticeRecord;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface PracticeMapper {

    @Insert("INSERT INTO practice_records (course_id, question_id, user_answer, is_correct) " +
            "VALUES (#{courseId}, #{questionId}, #{userAnswer}, #{isCorrect})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(PracticeRecord record);

    @Select("SELECT * FROM practice_records WHERE id = #{id}")
    PracticeRecord selectById(Long id);

    @Select("SELECT * FROM practice_records WHERE course_id = #{courseId} ORDER BY created_at DESC")
    List<PracticeRecord> selectListByCourseId(Long courseId);

    @Select("SELECT * FROM practice_records WHERE course_id = #{courseId} AND is_correct = FALSE ORDER BY created_at DESC")
    List<PracticeRecord> selectWrongByCourseId(Long courseId);
}
