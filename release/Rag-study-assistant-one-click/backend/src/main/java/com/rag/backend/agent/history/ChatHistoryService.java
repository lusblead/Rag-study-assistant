package com.rag.backend.agent.history;

import java.util.List;

public interface ChatHistoryService {
    Long resolveSession(Long sessionId, Long courseId, String firstQuestion);

    List<ChatMessage> recentMessages(Long sessionId, int limit);

    List<ChatSession> listSessions(Long courseId);

    List<ChatMessage> listMessages(Long sessionId);

    void appendMessage(Long sessionId, String role, String content);

    void deleteSession(Long sessionId);

    void deleteByCourseId(Long courseId);
}
