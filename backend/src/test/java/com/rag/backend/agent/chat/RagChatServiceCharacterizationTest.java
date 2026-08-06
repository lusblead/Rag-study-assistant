package com.rag.backend.agent.chat;

import com.rag.backend.agent.history.ChatHistoryService;
import com.rag.backend.agent.llm.ChatClient;
import com.rag.backend.agent.model.RagChatResponse;
import com.rag.backend.agent.model.RagPromptContext;
import com.rag.backend.agent.prompt.PromptTemplate;
import com.rag.backend.agent.retrieval.KnowledgeRetriever;
import com.rag.backend.agent.retrieval.RetrievedChunk;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// 冻结旧 RagChatService 的编排顺序与副作用，为后续重构提供行为基线。
class RagChatServiceCharacterizationTest {

    @Test
    // 冻结当前 happy path 的先后顺序：先读历史和检索，再调用模型，最后才写入用户/助手消息。
    void currentHappyPathRetrievesBeforeCallingModelAndThenWritesHistory() {
        KnowledgeRetriever retriever = mock(KnowledgeRetriever.class);
        @SuppressWarnings("unchecked")
        PromptTemplate<RagPromptContext> prompt = mock(PromptTemplate.class);
        ChatClient chatClient = mock(ChatClient.class);
        ChatHistoryService history = mock(ChatHistoryService.class);

        RetrievedChunk chunk = new RetrievedChunk(
                101L, 10L, "course.pdf", "事务", "默认回滚规则", 8, 0.91
        );
        when(history.resolveSession(null, 1L, "默认回滚规则是什么？")).thenReturn(99L);
        when(history.recentMessages(99L, 8)).thenReturn(List.of());
        when(retriever.retrieve(1L, "默认回滚规则是什么？", 5)).thenReturn(List.of(chunk));
        when(prompt.render(any(RagPromptContext.class))).thenReturn("rendered-prompt");
        when(chatClient.call("rendered-prompt")).thenReturn("依据课程资料，答案是……");

        RagChatService service = new RagChatService(
                retriever, prompt, chatClient, history, 5, 8
        );

        RagChatResponse response = service.chat(1L, null, "默认回滚规则是什么？");

        assertEquals(99L, response.sessionId());
        assertEquals(List.of(chunk), response.references());

        InOrder order = inOrder(history, retriever, prompt, chatClient);
        order.verify(history).resolveSession(null, 1L, "默认回滚规则是什么？");
        order.verify(history).recentMessages(99L, 8);
        order.verify(retriever).retrieve(1L, "默认回滚规则是什么？", 5);
        order.verify(prompt).render(any(RagPromptContext.class));
        order.verify(chatClient).call("rendered-prompt");
        order.verify(history).appendMessage(99L, "user", "默认回滚规则是什么？");
        order.verify(history).appendMessage(99L, "assistant", "依据课程资料，答案是……");
        verify(retriever).retrieve(1L, "默认回滚规则是什么？", 5);
    }
}
