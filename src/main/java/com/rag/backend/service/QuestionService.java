package com.rag.backend.service;

import com.rag.backend.entity.Question;

import java.util.List;

public interface QuestionService {

    /**
     * 保存单个题目
     */
    Question save(Question question);

    /**
     * 批量保存题目（供同学C的AI出题模块调用）
     */
    List<Question> batchSave(List<Question> questions);

    /**
     * 根据课程ID查询题目列表，支持按题型和难度筛选
     * @param courseId   课程ID（必填）
     * @param type       题型筛选（可选）
     * @param difficulty 难度筛选（可选）
     */
    List<Question> listByCourse(Long courseId, String type, String difficulty);
}
