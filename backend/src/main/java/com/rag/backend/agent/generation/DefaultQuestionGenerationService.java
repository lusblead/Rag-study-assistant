package com.rag.backend.agent.generation;

import com.rag.backend.agent.retrieval.KnowledgeRetriever;
import com.rag.backend.agent.retrieval.RetrievedChunk;
import com.rag.backend.question.QuestionService;
import com.rag.backend.question.model.Question;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class DefaultQuestionGenerationService implements QuestionGenerationService {
    private final KnowledgeRetriever knowledgeRetriever;
    private final QuestionService questionService;

    public DefaultQuestionGenerationService(KnowledgeRetriever knowledgeRetriever,
                                            QuestionService questionService) {
        this.knowledgeRetriever = knowledgeRetriever;
        this.questionService = questionService;
    }

    @Override
    public String generateQuestions(Long courseId, String requirement) {
        List<Question> saved = generateAndSave(courseId, requirement);
        return "已生成并保存 " + saved.size() + " 道题目";
    }

    public List<Question> generateAndSave(Long courseId, String requirement) {
        List<RetrievedChunk> chunks = knowledgeRetriever.retrieve(courseId, requirement, 5);
        List<Question> questions = new ArrayList<>();

        RetrievedChunk source = chunks.isEmpty() ? null : chunks.get(0);

        Question q = new Question();
        q.setCourseId(courseId);
        q.setSourceChunkId(source == null ? null : source.chunkId());
        q.setType(Question.TYPE_SINGLE_CHOICE);
        q.setStem("根据课程资料，以下哪一项说法是正确的？");
        q.setOptions("[\"A.选项一\",\"B.选项二\",\"C.选项三\",\"D.选项四\"]");
        q.setAnswer("A");
        q.setExplanation(source == null ? "当前没有召回到课程资料。" : "依据片段：" + source.content());
        q.setDifficulty(Question.DIFF_MEDIUM);
        q.setKnowledgePoint("课程知识点");
        questions.add(q);

        return questionService.batchSave(questions);
    }
}