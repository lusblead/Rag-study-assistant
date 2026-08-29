package com.rag.backend.agent.controller;

import com.rag.backend.agent.chat.RagChatService;
import com.rag.backend.agent.history.ChatHistoryService;
import com.rag.backend.agent.model.RagChatRequest;
import com.rag.backend.common.BizException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class RagChatControllerValidationTest {

    @Test
    void missingCourseIdIsRejectedBeforeServiceCall() {
        RagChatService service = mock(RagChatService.class);
        ChatHistoryService history = mock(ChatHistoryService.class);
        RagChatController controller = new RagChatController(service, history);
        RagChatRequest request = new RagChatRequest();
        request.setQuestion("什么是事务隔离？");

        BizException error = assertThrows(
                BizException.class,
                () -> controller.chat(request));

        assertEquals(400, error.getCode());
        assertEquals("courseId 不能为空", error.getMessage());
        verifyNoInteractions(service, history);
    }

    @Test
    void blankQuestionIsRejectedBeforeServiceCall() {
        RagChatService service = mock(RagChatService.class);
        ChatHistoryService history = mock(ChatHistoryService.class);
        RagChatController controller = new RagChatController(service, history);
        RagChatRequest request = new RagChatRequest();
        request.setCourseId(7L);
        request.setQuestion("  \t  ");

        BizException error = assertThrows(
                BizException.class,
                () -> controller.stream(request));

        assertEquals(400, error.getCode());
        assertEquals("question 不能为空", error.getMessage());
        verifyNoInteractions(service, history);
    }
}
