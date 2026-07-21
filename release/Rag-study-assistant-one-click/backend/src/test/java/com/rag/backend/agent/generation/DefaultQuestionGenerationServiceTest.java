package com.rag.backend.agent.generation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.backend.agent.llm.ChatClient;
import com.rag.backend.agent.prompt.QuestionPromptTemplate;
import com.rag.backend.agent.retrieval.KnowledgeRetriever;
import com.rag.backend.agent.retrieval.RetrievedChunk;
import com.rag.backend.common.BizException;
import com.rag.backend.question.QuestionService;
import com.rag.backend.question.model.Question;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DefaultQuestionGenerationServiceTest {

    @Test
    void parsesWrappedJsonAndNormalizesQuestionFields() {
        String modelOutput = """
                ```json
                {
                  "questions": [
                    {
                      "type": "多选题",
                      "stem": "哪些做法符合质量管理要求？",
                      "options": "A. 基于课程资料；B. 随机猜测；C. 依据证据；D. 忽略资料",
                      "answer": "C A",
                      "difficulty": "困难",
                      "knowledgePoint": "质量管理",
                      "sourceChunkId": 999
                    },
                    {
                      "type": "判断题",
                      "stem": "质量管理可以脱离课程资料。",
                      "answer": "false",
                      "difficulty": "简单",
                      "sourceChunkId": 10
                    }
                  ]
                }
                ```
                """;

        InMemoryQuestionService questionService = new InMemoryQuestionService();
        DefaultQuestionGenerationService service = serviceWith(modelOutput, chunks(), questionService);

        List<Question> questions = service.generateAndSave(2L, "生成题目");

        assertEquals(2, questions.size());

        Question multi = questions.get(0);
        assertEquals(2L, multi.getCourseId());
        assertEquals(10L, multi.getSourceChunkId());
        assertEquals(Question.TYPE_MULTI_CHOICE, multi.getType());
        assertEquals(Question.DIFF_HARD, multi.getDifficulty());
        assertEquals("AC", multi.getAnswer());
        assertEquals("[\"A. 基于课程资料\",\"B. 随机猜测\",\"C. 依据证据\",\"D. 忽略资料\"]", multi.getOptions());

        Question trueFalse = questions.get(1);
        assertEquals(Question.TYPE_TRUE_FALSE, trueFalse.getType());
        assertEquals(Question.DIFF_EASY, trueFalse.getDifficulty());
        assertEquals("错误", trueFalse.getAnswer());
        assertEquals("[\"正确\",\"错误\"]", trueFalse.getOptions());
    }

    @Test
    void rejectsGenerationWhenNoChunksAreAvailable() {
        DefaultQuestionGenerationService service = serviceWith("[]", List.of(), new InMemoryQuestionService());

        BizException error = assertThrows(BizException.class, () -> service.generateAndSave(2L, "生成题目"));

        assertEquals(400, error.getCode());
        assertNull(error.getCause());
    }

    private static DefaultQuestionGenerationService serviceWith(String modelOutput,
                                                                List<RetrievedChunk> chunks,
                                                                QuestionService questionService) {
        KnowledgeRetriever retriever = (courseId, query, topK) -> chunks;
        ChatClient chatClient = new ChatClient() {
            @Override
            public String call(String prompt) {
                return modelOutput;
            }

            @Override
            public Flux<String> stream(String prompt) {
                return Flux.empty();
            }
        };
        return new DefaultQuestionGenerationService(
                retriever,
                questionService,
                new QuestionPromptTemplate(),
                chatClient,
                new ObjectMapper(),
                5
        );
    }

    private static List<RetrievedChunk> chunks() {
        return List.of(new RetrievedChunk(10L, 20L, "课程资料", "质量管理需要基于证据和课程资料。", 0.9));
    }

    private static final class InMemoryQuestionService implements QuestionService {
        private final AtomicLong ids = new AtomicLong(0);
        private final List<Question> saved = new ArrayList<>();

        @Override
        public Question save(Question question) {
            question.setId(ids.incrementAndGet());
            saved.add(question);
            return question;
        }

        @Override
        public List<Question> batchSave(List<Question> questions) {
            questions.forEach(this::save);
            return questions;
        }

        @Override
        public List<Question> listByCourse(Long courseId, String type, String difficulty) {
            return saved.stream()
                    .filter(question -> courseId.equals(question.getCourseId()))
                    .filter(question -> type == null || type.equals(question.getType()))
                    .filter(question -> difficulty == null || difficulty.equals(question.getDifficulty()))
                    .toList();
        }
    }
}
