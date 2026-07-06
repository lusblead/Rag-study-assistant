package com.rag.backend.question;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.backend.agent.llm.ChatClient;
import com.rag.backend.agent.model.KnowledgeChunk;
import com.rag.backend.practice.PracticeMapper;
import com.rag.backend.question.model.Question;
import com.rag.backend.question.model.QuestionBatch;
import com.rag.backend.question.model.QuestionBatchDetail;
import com.rag.backend.question.model.QuestionGenerationRequest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class QuestionBatchGenerationServiceTest {
    @Test
    void generatesScopedBatchAndPersistsBatchMetadata() {
        QuestionChunkMapper chunkMapper = mock(QuestionChunkMapper.class);
        QuestionBatchMapper batchMapper = mock(QuestionBatchMapper.class);
        QuestionMapper questionMapper = mock(QuestionMapper.class);
        PracticeMapper practiceMapper = mock(PracticeMapper.class);
        RecordingQuestionService questionService = new RecordingQuestionService();
        ChatClient chatClient = new ChatClient() {
            @Override
            public String call(String prompt) {
                return """
                        {"styleSummary":"重视场景化题干与高质量干扰项","questions":[
                          {"type":"single_choice","stem":"问题一","options":["A.甲","B.乙"],"answer":"A","difficulty":"medium","knowledgePoint":"知识点一","chapters":["第1章","第3章"],"sourceChunkId":2},
                          {"type":"short_answer","stem":"问题二","answer":"答案二","difficulty":"hard","knowledgePoint":"知识点二","chapters":["第3章"],"sourceChunkId":3}
                        ]}
                        """;
            }

            @Override
            public Flux<String> stream(String prompt) { return Flux.empty(); }
        };
        when(chunkMapper.selectForGeneration(7L, List.of(11L, 12L))).thenReturn(List.of(
                chunk(1L, 11L, "片段一"), chunk(2L, 11L, "片段二"), chunk(3L, 12L, "片段三")
        ));
        when(chunkMapper.selectForGeneration(7L, List.of(12L))).thenReturn(List.of(chunk(3L, 12L, "真题样本")));
        when(batchMapper.selectUsedChunkIds(7L)).thenReturn(List.of(1L));
        doAnswer(invocation -> {
            invocation.getArgument(0, QuestionBatch.class).setId(99L);
            return 1;
        }).when(batchMapper).insert(any(QuestionBatch.class));
        when(batchMapper.selectDocumentIds(99L)).thenReturn(List.of(11L, 12L));
        when(batchMapper.selectChunkIds(99L)).thenReturn(List.of(1L, 2L, 3L));

        QuestionBatchGenerationService service = new QuestionBatchGenerationService(
                chunkMapper, batchMapper, questionMapper, questionService, practiceMapper,
                chatClient, new ObjectMapper()
        );
        QuestionGenerationRequest request = new QuestionGenerationRequest();
        request.setCourseId(7L);
        request.setCount(2);
        request.setType("mixed");
        request.setDifficulty("mixed");
        request.setRequirement("覆盖不同章节");
        request.setMode("exam");
        request.setDocumentIds(List.of(11L, 12L));
        request.setReferenceRealQuestions(true);
        request.setStyleDocumentIds(List.of(12L));

        QuestionBatchDetail result = service.generate(request);

        assertEquals(99L, result.getBatch().getId());
        assertEquals("exam", result.getBatch().getMode());
        assertEquals(2, result.getQuestions().size());
        assertTrue(result.getQuestions().stream().allMatch(question -> Long.valueOf(99L).equals(question.getBatchId())));
        assertEquals("[\"第1章\",\"第3章\"]", result.getQuestions().get(0).getChapterTags());
        assertEquals("重视场景化题干与高质量干扰项", result.getBatch().getStyleSummary());
        verify(batchMapper).insertDocument(99L, 11L);
        verify(batchMapper).insertDocument(99L, 12L);
    }

    @Test
    void avoidsPreviouslyUsedChunksWhileEnoughFreshChunksRemain() {
        QuestionChunkMapper chunkMapper = mock(QuestionChunkMapper.class);
        QuestionBatchMapper batchMapper = mock(QuestionBatchMapper.class);
        QuestionMapper questionMapper = mock(QuestionMapper.class);
        PracticeMapper practiceMapper = mock(PracticeMapper.class);
        List<KnowledgeChunk> chunks = new ArrayList<>();
        for (long id = 1; id <= 10; id++) chunks.add(chunk(id, id % 2 == 0 ? 11L : 12L, "片段 " + id));
        when(chunkMapper.selectForGeneration(7L, List.of())).thenReturn(chunks);
        when(batchMapper.selectUsedChunkIds(7L)).thenReturn(List.of(1L, 2L, 3L));
        doAnswer(invocation -> { invocation.getArgument(0, QuestionBatch.class).setId(88L); return 1; })
                .when(batchMapper).insert(any(QuestionBatch.class));
        when(batchMapper.selectDocumentIds(88L)).thenReturn(List.of(11L, 12L));
        when(batchMapper.selectChunkIds(88L)).thenReturn(List.of());
        ChatClient chatClient = new ChatClient() {
            public String call(String prompt) {
                return "{\"questions\":[{\"type\":\"short_answer\",\"stem\":\"问题\",\"answer\":\"答案\"}]}";
            }
            public Flux<String> stream(String prompt) { return Flux.empty(); }
        };
        QuestionBatchGenerationService service = new QuestionBatchGenerationService(
                chunkMapper, batchMapper, questionMapper, new RecordingQuestionService(), practiceMapper,
                chatClient, new ObjectMapper()
        );
        QuestionGenerationRequest request = new QuestionGenerationRequest();
        request.setCourseId(7L);
        request.setCount(3);
        request.setMode("practice");

        service.generate(request);

        ArgumentCaptor<Long> selectedIds = ArgumentCaptor.forClass(Long.class);
        verify(batchMapper, atLeastOnce()).insertChunk(eq(88L), selectedIds.capture());
        assertTrue(selectedIds.getAllValues().stream().noneMatch(id -> Set.of(1L, 2L, 3L).contains(id)));
        assertEquals(6, selectedIds.getAllValues().size());
    }

    @Test
    void generatesStructuredChineseCompositeQuestion() {
        QuestionChunkMapper chunkMapper = mock(QuestionChunkMapper.class);
        QuestionBatchMapper batchMapper = mock(QuestionBatchMapper.class);
        QuestionMapper questionMapper = mock(QuestionMapper.class);
        PracticeMapper practiceMapper = mock(PracticeMapper.class);
        RecordingQuestionService questionService = new RecordingQuestionService();
        when(chunkMapper.selectForGeneration(7L, List.of())).thenReturn(List.of(chunk(1L, 11L, "岳阳楼记原文")));
        when(batchMapper.selectUsedChunkIds(7L)).thenReturn(List.of());
        doAnswer(invocation -> { invocation.getArgument(0, QuestionBatch.class).setId(77L); return 1; })
                .when(batchMapper).insert(any(QuestionBatch.class));
        when(batchMapper.selectDocumentIds(77L)).thenReturn(List.of(11L));
        when(batchMapper.selectChunkIds(77L)).thenReturn(List.of(1L));
        ChatClient chatClient = new ChatClient() {
            public String call(String prompt) {
                assertTrue(prompt.contains("语文结构规则"));
                return """
                    {"questions":[{"type":"classical_chinese_reading","subject":"chinese","stem":"阅读材料，回答问题","answer":"",
                    "questionData":{"material":{"text":"岳阳楼记原文","source":"课程资料"},"subQuestions":[
                    {"type":"single_choice","stem":"选择正确项","options":["A.甲","B.乙"],"answer":"A","explanation":"解析"},
                    {"type":"translation","stem":"翻译句子","answer":"参考译文","explanation":"采分点"}]},"sourceChunkId":1}]}
                    """;
            }
            public Flux<String> stream(String prompt) { return Flux.empty(); }
        };
        QuestionBatchGenerationService service = new QuestionBatchGenerationService(
                chunkMapper, batchMapper, questionMapper, questionService, practiceMapper, chatClient, new ObjectMapper());
        QuestionGenerationRequest request = new QuestionGenerationRequest();
        request.setCourseId(7L);
        request.setCount(1);
        request.setSubject("chinese");
        request.setQuestionTypes(List.of("classical_chinese_reading"));

        Question question = service.generate(request).getQuestions().getFirst();

        assertEquals(Question.SUBJECT_CHINESE, question.getSubject());
        assertEquals(Question.GRADING_MIXED, question.getGradingStrategy());
        assertTrue(question.getQuestionData().contains("\"key\":\"q1\""));
        assertTrue(question.getQuestionData().contains("\"gradingStrategy\":\"manual\""));
    }

    private static KnowledgeChunk chunk(Long id, Long documentId, String content) {
        KnowledgeChunk chunk = new KnowledgeChunk();
        chunk.setId(id);
        chunk.setCourseId(7L);
        chunk.setDocumentId(documentId);
        chunk.setChunkIndex(id.intValue());
        chunk.setTitle("标题 " + id);
        chunk.setContent(content);
        return chunk;
    }

    private static final class RecordingQuestionService implements QuestionService {
        private final AtomicLong ids = new AtomicLong(100);
        private final List<Question> values = new ArrayList<>();

        @Override
        public Question save(Question question) {
            question.setId(ids.incrementAndGet());
            values.add(question);
            return question;
        }

        @Override
        public List<Question> batchSave(List<Question> questions) {
            questions.forEach(this::save);
            return questions;
        }

        @Override
        public List<Question> listByCourse(Long courseId, String type, String difficulty) {
            return values;
        }
    }
}
