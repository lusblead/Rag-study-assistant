package com.rag.backend.practice;

import com.rag.backend.course.CourseMapper;
import com.rag.backend.practice.model.PracticeRecord;
import com.rag.backend.question.QuestionMapper;
import com.rag.backend.question.model.Question;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

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
        if (courseMapper.selectById(courseId) == null) {
            throw new IllegalArgumentException("Course does not exist: " + courseId);
        }

        Question question = questionMapper.selectById(questionId);
        if (question == null) {
            throw new IllegalArgumentException("Question does not exist: " + questionId);
        }

        String standardAnswer = normalizeAnswer(question.getType(), question.getAnswer());
        String submittedAnswer = normalizeAnswer(question.getType(), userAnswer);
        boolean isCorrect = !standardAnswer.isBlank() && standardAnswer.equalsIgnoreCase(submittedAnswer);

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

    private String normalizeAnswer(String type, String answer) {
        if (answer == null) {
            return "";
        }
        String text = answer.trim();
        if (text.isBlank()) {
            return "";
        }

        if (Question.TYPE_SINGLE_CHOICE.equals(type)) {
            String letters = choiceLetters(text);
            return letters.isBlank() ? text.toUpperCase() : letters.substring(0, 1);
        }
        if (Question.TYPE_MULTI_CHOICE.equals(type)) {
            String letters = choiceLetters(text);
            return letters.isBlank() ? text.toUpperCase().replaceAll("\\s+", "") : letters;
        }
        if (Question.TYPE_TRUE_FALSE.equals(type)) {
            String lower = text.toLowerCase();
            if (Set.of("true", "t", "yes", "y", "正确", "对", "是").contains(lower)) {
                return "正确";
            }
            if (Set.of("false", "f", "no", "n", "错误", "错", "否").contains(lower)) {
                return "错误";
            }
        }
        return text.replaceAll("\\s+", " ").trim();
    }

    private String choiceLetters(String value) {
        TreeSet<Character> letters = new TreeSet<>();
        for (char ch : value.toUpperCase().toCharArray()) {
            if (ch >= 'A' && ch <= 'D') {
                letters.add(ch);
            }
        }
        StringBuilder builder = new StringBuilder();
        for (Character letter : letters) {
            builder.append(letter);
        }
        return builder.toString();
    }
}
