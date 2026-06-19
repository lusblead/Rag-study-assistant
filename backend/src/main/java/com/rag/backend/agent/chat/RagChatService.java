package com.rag.backend.agent.chat;

import com.rag.backend.agent.llm.ChatClient;
import com.rag.backend.agent.model.RagChatResponse;
import com.rag.backend.agent.model.RagPromptContext;
import com.rag.backend.agent.prompt.PromptTemplate;
import com.rag.backend.agent.retrieval.KnowledgeRetriever;
import com.rag.backend.agent.retrieval.RetrievedChunk;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class RagChatService {
    private final int topK;
    private final KnowledgeRetriever knowledgeRetriever;
    private final PromptTemplate<RagPromptContext> promptTemplate;
    private final ChatClient chatClient;

    public RagChatService(KnowledgeRetriever knowledgeRetriever,
                          PromptTemplate<RagPromptContext> promptTemplate,
                          ChatClient chatClient,
                          @Value("${rag.top-k:5}") int topK) {
        this.knowledgeRetriever = knowledgeRetriever;
        this.promptTemplate = promptTemplate;
        this.chatClient = chatClient;
        this.topK = topK;
    }

    public RagChatResponse chat(Long courseId, String question) {
        List<RetrievedChunk> chunks = knowledgeRetriever.retrieve(courseId, question, topK);
        String prompt = promptTemplate.render(new RagPromptContext(question, chunks));
        String answer = chatClient.call(prompt);
        return new RagChatResponse(answer, chunks);
    }
}