package com.rag.backend.agent.model;

import com.rag.backend.agent.history.ChatMessage;
import com.rag.backend.agent.retrieval.RetrievedChunk;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

// 聚合生成 RAG 提示词所需的上下文。
public record RagPromptContext(String question, List<RetrievedChunk> chunks, List<ChatMessage> history) {
    public RagPromptContext(String question, List<RetrievedChunk> chunks) {
        this(question, chunks, Collections.emptyList());
    }

    public String referencesText() {
        return chunks.stream()
                .map(chunk -> "资料来源：" + sourceText(chunk)
                        + "\n内容：\n" + chunk.content())
                .collect(Collectors.joining("\n\n"));
    }

    public String historyText() {
        if (history == null || history.isEmpty()) {
            return "无";
        }
        return history.stream()
                .map(message -> roleName(message.getRole()) + "：" + message.getContent())
                .collect(Collectors.joining("\n"));
    }

    private String sourceText(RetrievedChunk chunk) {
        String documentName = firstNonBlank(chunk.documentName(), chunk.title(), "未知文件");
        String page = pageText(chunk.sourcePage());
        return "《" + documentName + "》" + (page.isBlank() ? "" : "，" + page);
    }

    private String pageText(Integer sourcePage) {
        return sourcePage == null || sourcePage <= 0 ? "" : "第 " + sourcePage + " 页";
    }

    private String firstNonBlank(String first, String second, String fallback) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        if (second != null && !second.isBlank()) {
            return second;
        }
        return fallback;
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
