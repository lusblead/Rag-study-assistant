package com.rag.backend.agent.evaluation;

// 评测历史只保存复现追问所需的 role/content；它不是生产会话记录。
public record GoldenHistoryMessage(String role, String content) {
    public GoldenHistoryMessage {
        if (!"user".equals(role) && !"assistant".equals(role)) {
            throw new IllegalArgumentException("history role must be user or assistant");
        }
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("history content is required");
        }
    }
}
