package com.rag.backend.agent.chat;

import com.rag.backend.agent.history.ChatMessage;
import com.rag.backend.agent.history.ChatHistoryService;
import com.rag.backend.agent.llm.ChatClient;
import com.rag.backend.agent.model.RagChatResponse;
import com.rag.backend.agent.model.RagChatStreamResponse;
import com.rag.backend.agent.model.RagPromptContext;
import com.rag.backend.agent.prompt.PromptTemplate;
import com.rag.backend.agent.retrieval.KnowledgeRetriever;
import com.rag.backend.agent.retrieval.RetrievedChunk;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.List;

@Service
// 编排 RAG 问答、检索上下文、调用模型并保存会话历史。
public class RagChatService {
    private final int topK;
    private final int historyLimit;
    private final KnowledgeRetriever knowledgeRetriever;
    private final PromptTemplate<RagPromptContext> promptTemplate;
    private final ChatClient chatClient;
    private final ChatHistoryService chatHistoryService;

    public RagChatService(KnowledgeRetriever knowledgeRetriever,
                          PromptTemplate<RagPromptContext> promptTemplate,
                          ChatClient chatClient,
                          ChatHistoryService chatHistoryService,
                          @Value("${rag.top-k:5}") int topK,
                          @Value("${rag.history-limit:8}") int historyLimit) {
        this.knowledgeRetriever = knowledgeRetriever;
        this.promptTemplate = promptTemplate;
        this.chatClient = chatClient;
        this.chatHistoryService = chatHistoryService;
        this.topK = topK;
        this.historyLimit = historyLimit;
    }

    public RagChatResponse chat(Long courseId, String question) {
        return chat(courseId, null, question);
    }

    public RagChatResponse chat(Long courseId, Long sessionId, String question) {
        Long effectiveSessionId = chatHistoryService.resolveSession(sessionId, courseId, question);
        List<ChatMessage> history = chatHistoryService.recentMessages(effectiveSessionId, historyLimit);
        List<RetrievedChunk> chunks = knowledgeRetriever.retrieve(courseId, retrievalQuery(question, history), topK);
        String prompt = promptTemplate.render(new RagPromptContext(question, chunks, history));
        String answer = chatClient.call(prompt);
        chatHistoryService.appendMessage(effectiveSessionId, ChatMessage.ROLE_USER, question);
        chatHistoryService.appendMessage(effectiveSessionId, ChatMessage.ROLE_ASSISTANT, answer);
        return new RagChatResponse(effectiveSessionId, answer, chunks);
    }

    public Flux<String> stream(Long courseId, String question) {
        return stream(courseId, null, question).stream();
    }

    public RagChatStreamResponse stream(Long courseId, Long sessionId, String question) {
        Long effectiveSessionId = chatHistoryService.resolveSession(sessionId, courseId, question);
        List<ChatMessage> history = chatHistoryService.recentMessages(effectiveSessionId, historyLimit);
        List<RetrievedChunk> chunks = knowledgeRetriever.retrieve(courseId, retrievalQuery(question, history), topK);
        String prompt = promptTemplate.render(new RagPromptContext(question, chunks, history));
        StringBuilder answer = new StringBuilder();
        Flux<String> stream = chatClient.stream(prompt)
                .doOnNext(answer::append)
                .doOnComplete(() -> {
                    chatHistoryService.appendMessage(effectiveSessionId, ChatMessage.ROLE_USER, question);
                    chatHistoryService.appendMessage(effectiveSessionId, ChatMessage.ROLE_ASSISTANT, answer.toString());
                });
        return new RagChatStreamResponse(effectiveSessionId, chunks, stream);
    }

    private String retrievalQuery(String question, List<ChatMessage> history) {
        if (history == null || history.isEmpty()) {
            return question;
        }
        String recentUserContext = history.stream()
                .filter(message -> ChatMessage.ROLE_USER.equals(message.getRole()))
                .map(ChatMessage::getContent)
                .reduce((previous, current) -> previous + "\n" + current)
                .orElse("");
        if (recentUserContext.isBlank()) {
            return question;
        }
        return recentUserContext + "\n" + question;
    }
}
