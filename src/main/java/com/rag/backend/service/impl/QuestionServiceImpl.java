package com.rag.backend.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.rag.backend.entity.Question;
import com.rag.backend.mapper.CourseMapper;
import com.rag.backend.mapper.QuestionMapper;
import com.rag.backend.service.QuestionService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

@Service
public class QuestionServiceImpl implements QuestionService {

    private final QuestionMapper questionMapper;
    private final CourseMapper courseMapper;

    public QuestionServiceImpl(QuestionMapper questionMapper, CourseMapper courseMapper) {
        this.questionMapper = questionMapper;
        this.courseMapper = courseMapper;
    }

    @Override
    public Question save(Question question) {
        if (question.getCourseId() == null) {
            throw new IllegalArgumentException("course_id 不能为空");
        }
        if (courseMapper.selectById(question.getCourseId()) == null) {
            throw new IllegalArgumentException("课程不存在: " + question.getCourseId());
        }
        if (!StringUtils.hasText(question.getStem())) {
            throw new IllegalArgumentException("题干不能为空");
        }
        if (!StringUtils.hasText(question.getType())) {
            throw new IllegalArgumentException("题型不能为空");
        }

        questionMapper.insert(question);
        return question;
    }

    /**
     * 批量保存题目 —— 供同学C的AI出题模块调用。
     *
     * AI 生成题目的 JSON 字段约定：
     *   type:     "single_choice" | "multi_choice" | "true_false" | "short_answer"
     *   stem:     题干文本
     *   options:  JSON 字符串，如 '["A.选项1","B.选项2","C.选项3","D.选项4"]'
     *             判断题可传 '["正确","错误"]' 或 null
     *   answer:   答案文本，如 "A" / "AB" / "正确" / "这是简答题答案"
     *   explanation: 解析文本
     *   difficulty:   "easy" | "medium" | "hard"
     *   knowledgePoint: 知识点名称
     *   sourceChunkId:  来源知识片段ID（可为 null）
     */
    @Override
    public List<Question> batchSave(List<Question> questions) {
        List<Question> saved = new ArrayList<>();
        for (Question q : questions) {
            saved.add(save(q));
        }
        return saved;
    }

    @Override
    public List<Question> listByCourse(Long courseId, String type, String difficulty) {
        LambdaQueryWrapper<Question> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Question::getCourseId, courseId);
        if (StringUtils.hasText(type)) {
            wrapper.eq(Question::getType, type);
        }
        if (StringUtils.hasText(difficulty)) {
            wrapper.eq(Question::getDifficulty, difficulty);
        }
        wrapper.orderByDesc(Question::getCreatedAt);
        return questionMapper.selectList(wrapper);
    }
}
