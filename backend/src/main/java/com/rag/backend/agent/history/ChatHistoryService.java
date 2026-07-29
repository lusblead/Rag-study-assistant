package com.rag.backend.agent.history;

import java.util.List;

// 负责会话归属、有限历史读取和消息写入；聊天记录只提供上下文，不充当课程业务状态真相。
public interface ChatHistoryService {
    Long resolveSession(Long sessionId, Long courseId, String firstQuestion);

    List<ChatMessage> recentMessages(Long sessionId, int limit);

    List<ChatSession> listSessions(Long courseId);

    List<ChatMessage> listMessages(Long sessionId);

    void appendMessage(Long sessionId, String role, String content);

    void deleteSession(Long sessionId);

    void deleteByCourseId(Long courseId);
}
