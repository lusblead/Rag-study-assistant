package com.rag.backend.agent.history;

import java.util.List;

// 定义聊天会话历史的服务接口。
public interface ChatHistoryService {
    Long resolveSession(Long sessionId, Long courseId, String firstQuestion);

    List<ChatMessage> recentMessages(Long sessionId, int limit);

    List<ChatSession> listSessions(Long courseId);

    List<ChatMessage> listMessages(Long sessionId);

    void appendMessage(Long sessionId, String role, String content);

    void deleteSession(Long sessionId);

    void deleteByCourseId(Long courseId);
}
