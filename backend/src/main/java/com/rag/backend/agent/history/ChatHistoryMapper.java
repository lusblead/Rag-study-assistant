package com.rag.backend.agent.history;

import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface ChatHistoryMapper {
    @Insert("INSERT INTO chat_sessions (course_id, title) VALUES (#{courseId}, #{title})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertSession(ChatSession session);

    @Select("SELECT * FROM chat_sessions WHERE id = #{id}")
    ChatSession selectSessionById(Long id);

    @Select("SELECT * FROM chat_sessions WHERE course_id = #{courseId} ORDER BY updated_at DESC")
    List<ChatSession> selectSessionsByCourseId(Long courseId);

    @Update("UPDATE chat_sessions SET updated_at = CURRENT_TIMESTAMP WHERE id = #{id}")
    int touchSession(Long id);

    @Insert("INSERT INTO chat_messages (session_id, role, content) VALUES (#{sessionId}, #{role}, #{content})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertMessage(ChatMessage message);

    @Select("""
            SELECT * FROM (
                SELECT * FROM chat_messages
                WHERE session_id = #{sessionId}
                ORDER BY created_at DESC, id DESC
                LIMIT #{limit}
            ) recent
            ORDER BY created_at ASC, id ASC
            """)
    List<ChatMessage> selectRecentMessages(@Param("sessionId") Long sessionId, @Param("limit") int limit);

    @Select("SELECT * FROM chat_messages WHERE session_id = #{sessionId} ORDER BY created_at ASC, id ASC")
    List<ChatMessage> selectMessagesBySessionId(Long sessionId);

    @Delete("DELETE FROM chat_messages WHERE session_id = #{sessionId}")
    int deleteMessagesBySessionId(Long sessionId);

    @Delete("DELETE FROM chat_sessions WHERE id = #{sessionId}")
    int deleteSession(Long sessionId);

    @Delete("DELETE FROM chat_messages WHERE session_id IN (SELECT id FROM chat_sessions WHERE course_id = #{courseId})")
    int deleteMessagesByCourseId(Long courseId);

    @Delete("DELETE FROM chat_sessions WHERE course_id = #{courseId}")
    int deleteSessionsByCourseId(Long courseId);
}
