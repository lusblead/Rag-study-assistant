package com.rag.backend.agent.model;

import com.rag.backend.agent.history.ChatMessage;
import com.rag.backend.agent.grounding.CitationCatalog;
import com.rag.backend.agent.retrieval.RetrievedChunk;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

// 聚合生成 RAG 提示词所需的上下文，并冻结本次唯一引用目录。
public record RagPromptContext(
        String question,
        CitationCatalog citationCatalog,
        List<ChatMessage> history
) {
    public RagPromptContext {
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("question must not be blank");
        }
        citationCatalog = Objects.requireNonNull(
                citationCatalog, "citationCatalog");
        history = history == null ? List.of() : List.copyOf(history);
    }

    public RagPromptContext(
            String question,
            List<RetrievedChunk> chunks,
            List<ChatMessage> history) {
        this(question, CitationCatalog.from(chunks), history);
    }

    public RagPromptContext(String question, List<RetrievedChunk> chunks) {
        this(question, chunks, Collections.emptyList());
    }

    public String referencesText() {
        return citationCatalog.promptText();
    }

    public List<RetrievedChunk> chunks() {
        return citationCatalog.sources().stream()
                .map(source -> source.chunk())
                .toList();
    }

    public String historyText() {
        if (history == null || history.isEmpty()) {
            return "无";
        }
        return history.stream()
                .map(message -> roleName(message.getRole()) + "：" + message.getContent())
                .collect(Collectors.joining("\n"));
    }

    private String roleName(String role) {
        if (ChatMessage.ROLE_USER.equals(role)) {
            return "用户";
        }
        if (ChatMessage.ROLE_ASSISTANT.equals(role)) {
            return "助手";
        }
        return role;
    }
}
