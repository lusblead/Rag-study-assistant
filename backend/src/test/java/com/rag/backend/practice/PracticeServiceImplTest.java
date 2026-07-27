package com.rag.backend.practice;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.backend.agent.llm.ChatClient;
import com.rag.backend.agent.repository.KnowledgeChunkRepository;
import com.rag.backend.agent.retrieval.KnowledgeRetriever;
import com.rag.backend.course.CourseMapper;
import com.rag.backend.course.model.Course;
import com.rag.backend.practice.model.PracticeRecord;
import com.rag.backend.question.QuestionMapper;
import com.rag.backend.question.model.Question;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PracticeServiceImplTest {
    @Test
    void gradesObjectiveSubQuestionAndLeavesSubjectivePending() {
        PracticeMapper practiceMapper = mock(PracticeMapper.class);
        CourseMapper courseMapper = mock(CourseMapper.class);
        QuestionMapper questionMapper = mock(QuestionMapper.class);
        when(courseMapper.selectById(1L)).thenReturn(new Course());
        Question question = new Question();
        question.setId(2L);
        question.setType(Question.TYPE_CLASSICAL_CHINESE_READING);
        question.setQuestionData("""
                {"material":{"text":"原文"},"subQuestions":[
                  {"key":"q1","type":"single_choice","answer":"A","maxScore":2,"gradingStrategy":"rule"},
                  {"key":"q2","type":"translation","answer":"参考译文","maxScore":4,"gradingStrategy":"manual"}
                ]}
                """);
        when(questionMapper.selectById(2L)).thenReturn(question);
        doAnswer(invocation -> { invocation.getArgument(0, PracticeRecord.class).setId(10L); return 1; })
                .when(practiceMapper).insert(any(PracticeRecord.class));
        PracticeServiceImpl service = new PracticeServiceImpl(
                practiceMapper, courseMapper, questionMapper, mock(KnowledgeRetriever.class),
                mock(KnowledgeChunkRepository.class), mock(ChatClient.class), new ObjectMapper(), 5);

        PracticeRecord record = service.submit(1L, 2L, null,
                "{\"answers\":[{\"subQuestionKey\":\"q1\",\"answer\":\"A\"},{\"subQuestionKey\":\"q2\",\"answer\":\"译文\"}]}");

        assertNull(record.getIsCorrect());
        assertEquals("manual_required", record.getGradingStatus());
        assertEquals(0, record.getScore().compareTo(java.math.BigDecimal.valueOf(2)));
        assertEquals(2, record.getSubResults().size());
        assertTrue(record.getSubResults().getFirst().getCorrect());
        assertNull(record.getSubResults().get(1).getCorrect());
        verify(practiceMapper).insert(record);
    }
}
