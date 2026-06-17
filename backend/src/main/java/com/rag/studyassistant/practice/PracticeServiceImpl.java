package com.rag.studyassistant.practice;

import com.rag.studyassistant.course.CourseMapper;
import com.rag.studyassistant.practice.model.PracticeRecord;
import com.rag.studyassistant.question.QuestionMapper;
import com.rag.studyassistant.question.model.Question;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class PracticeServiceImpl implements PracticeService {

    private final PracticeMapper practiceMapper;
    private final CourseMapper courseMapper;
    private final QuestionMapper questionMapper;

    public PracticeServiceImpl(PracticeMapper practiceMapper,
                               CourseMapper courseMapper,
                               QuestionMapper questionMapper) {
        this.practiceMapper = practiceMapper;
        this.courseMapper = courseMapper;
        this.questionMapper = questionMapper;
    }

    @Override
    public PracticeRecord submit(Long courseId, Long questionId, String userAnswer) {
        // 校验课程存在
        if (courseMapper.selectById(courseId) == null) {
            throw new IllegalArgumentException("课程不存在: " + courseId);
        }

        // 校验题目存在
        Question question = questionMapper.selectById(questionId);
        if (question == null) {
            throw new IllegalArgumentException("题目不存在: " + questionId);
        }

        // 判断答案是否正确
        boolean isCorrect = question.getAnswer() != null
                && question.getAnswer().trim().equalsIgnoreCase(userAnswer != null ? userAnswer.trim() : "");

        // 保存记录
        PracticeRecord record = new PracticeRecord();
        record.setCourseId(courseId);
        record.setQuestionId(questionId);
        record.setUserAnswer(userAnswer);
        record.setIsCorrect(isCorrect);

        practiceMapper.insert(record);
        return record;
    }

    @Override
    public List<PracticeRecord> listRecords(Long courseId) {
        return practiceMapper.selectListByCourseId(courseId);
    }

    @Override
    public List<PracticeRecord> listWrongQuestions(Long courseId) {
        return practiceMapper.selectWrongByCourseId(courseId);
    }
}
