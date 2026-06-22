package com.rag.backend.agent.history;

import com.rag.backend.common.BizException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;

@Service
// 使用 MyBatis 持久化和查询聊天历史。
public class MyBatisChatHistoryService implements ChatHistoryService {
    private final ChatHistoryMapper mapper;

    public MyBatisChatHistoryService(ChatHistoryMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Long resolveSession(Long sessionId, Long courseId, String firstQuestion) {
        if (sessionId == null) {
            ChatSession session = new ChatSession();
            session.setCourseId(courseId);
            session.setTitle(buildTitle(firstQuestion));
            mapper.insertSession(session);
            return session.getId();
        }

        ChatSession existing = mapper.selectSessionById(sessionId);
        if (existing == null) {
            throw new BizException(404, "会话不存在: " + sessionId);
        }
        if (!existing.getCourseId().equals(courseId)) {
            throw new BizException(400, "会话不属于当前课程");
        }
        return sessionId;
    }

    @Override
    public List<ChatMessage> recentMessages(Long sessionId, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        return mapper.selectRecentMessages(sessionId, limit);
    }

    @Override
    public List<ChatSession> listSessions(Long courseId) {
        return mapper.selectSessionsByCourseId(courseId);
    }

    @Override
    public List<ChatMessage> listMessages(Long sessionId) {
        ensureSessionExists(sessionId);
        return mapper.selectMessagesBySessionId(sessionId);
    }

    @Override
    @Transactional
    public void appendMessage(Long sessionId, String role, String content) {
        ensureSessionExists(sessionId);
        if (!ChatMessage.ROLE_USER.equals(role) && !ChatMessage.ROLE_ASSISTANT.equals(role)) {
            throw new BizException(400, "Unsupported chat role: " + role);
        }
        if (!StringUtils.hasText(content)) {
            return;
        }

        ChatMessage message = new ChatMessage();
        message.setSessionId(sessionId);
        message.setRole(role);
        message.setContent(content);
        mapper.insertMessage(message);
        mapper.touchSession(sessionId);
    }

    @Override
    @Transactional
    public void deleteSession(Long sessionId) {
        ensureSessionExists(sessionId);
        mapper.deleteMessagesBySessionId(sessionId);
        mapper.deleteSession(sessionId);
    }

    @Override
    @Transactional
    public void deleteByCourseId(Long courseId) {
        mapper.deleteMessagesByCourseId(courseId);
        mapper.deleteSessionsByCourseId(courseId);
    }

    private void ensureSessionExists(Long sessionId) {
        if (mapper.selectSessionById(sessionId) == null) {
            throw new BizException(404, "会话不存在: " + sessionId);
        }
    }

    private String buildTitle(String firstQuestion) {
        if (!StringUtils.hasText(firstQuestion)) {
            return "新对话";
        }
        String text = firstQuestion.trim().replaceAll("\\s+", " ");
        return text.length() > 40 ? text.substring(0, 40) : text;
    }
}
