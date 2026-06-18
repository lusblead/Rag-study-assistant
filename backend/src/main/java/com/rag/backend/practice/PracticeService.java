package com.rag.backend.practice;

import com.rag.backend.practice.model.PracticeRecord;

import java.util.List;

public interface PracticeService {

    /**
     * 提交答案，判断对错并保存记录
     */
    PracticeRecord submit(Long courseId, Long questionId, String userAnswer);

    /**
     * 查询课程下的练习记录
     */
    List<PracticeRecord> listRecords(Long courseId);

    /**
     * 查询课程下的错题记录
     */
    List<PracticeRecord> listWrongQuestions(Long courseId);
}
