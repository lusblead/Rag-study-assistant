package com.rag.backend.agent.chat;

import com.rag.backend.agent.history.ChatHistoryService;
import com.rag.backend.agent.history.ChatMessage;
import com.rag.backend.agent.llm.ChatClient;
import com.rag.backend.agent.model.RagChatResponse;
import com.rag.backend.agent.model.RagChatStreamResponse;
import com.rag.backend.agent.model.RagPromptContext;
import com.rag.backend.agent.prompt.PromptTemplate;
import com.rag.backend.agent.prompt.RagPromptTemplate;
import com.rag.backend.agent.retrieval.KnowledgeRetriever;
import com.rag.backend.agent.retrieval.RetrievalExecutionResult;
import com.rag.backend.agent.retrieval.RetrievedChunk;
import com.rag.backend.common.BizException;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Signal;

import java.net.http.HttpTimeoutException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
                101L, 10L, "course.pdf", "事务",
                "默认回滚规则会撤销本次数据库修改。", 8, 0.91
        );
        when(history.resolveSession(null, 1L, "默认回滚规则是什么？")).thenReturn(99L);
        when(history.recentMessages(99L, 8)).thenReturn(List.of());
        when(retriever.retrieveWithResult(
                1L, "默认回滚规则是什么？", 5))
                .thenReturn(retrieval(List.of(chunk)));
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
        order.verify(retriever).retrieveWithResult(
                1L, "默认回滚规则是什么？", 5);
        order.verify(prompt).render(any(RagPromptContext.class));
        order.verify(chatClient).call("rendered-prompt");
        order.verify(history).appendMessage(99L, "user", "默认回滚规则是什么？");
        order.verify(history).appendMessage(99L, "assistant", "依据课程资料，答案是……");
        verify(retriever).retrieveWithResult(
                1L, "默认回滚规则是什么？", 5);
    }

    @Test
    // Step 3.2：同步空检索在生成前 REFUSE，不渲染 Prompt、不调用模型。
    void synchronousEmptyRetrievalRefusesLocallyWithoutCallingModel() {
        KnowledgeRetriever retriever = mock(KnowledgeRetriever.class);
        ChatClient chatClient = mock(ChatClient.class);
        ChatHistoryService history = mock(ChatHistoryService.class);
        RagPromptTemplate promptTemplate = new RagPromptTemplate();

        ChatMessage previousUser = message(ChatMessage.ROLE_USER, "上一轮问了什么？");
        ChatMessage previousAssistant = message(ChatMessage.ROLE_ASSISTANT, "上一轮回答");
        when(history.resolveSession(null, 1L, "没有命中资料时会怎样？")).thenReturn(100L);
        when(history.recentMessages(100L, 8)).thenReturn(
                List.of(previousUser, previousAssistant));
        when(retriever.retrieveWithResult(
                1L,
                "上一轮问了什么？\n没有命中资料时会怎样？",
                5)).thenReturn(retrieval(List.of()));

        RagChatService service = new RagChatService(
                retriever, promptTemplate, chatClient, history, 5, 8
        );

        RagChatResponse response = service.chat(1L, null, "没有命中资料时会怎样？");

        assertEquals(100L, response.sessionId());
        assertEquals("当前知识库中没有找到足够依据，暂时无法回答这个问题。",
                response.answer());
        assertEquals(List.of(), response.references());
        assertEquals("REFUSE",
                response.metadata().evidenceDecision().decision().name());

        verify(chatClient, never()).call(anyString());
        verify(chatClient, never()).stream(anyString());
        verify(history).appendMessage(100L, "user", "没有命中资料时会怎样？");
        verify(history).appendMessage(100L, "assistant",
                "当前知识库中没有找到足够依据，暂时无法回答这个问题。");
    }

    @Test
    // Step 3.2：流式空检索返回一个本地拒答片段，正常完成后保存历史。
    void streamingEmptyRetrievalRefusesLocallyAndWritesHistoryAfterCompletion() {
        KnowledgeRetriever retriever = mock(KnowledgeRetriever.class);
        ChatClient chatClient = mock(ChatClient.class);
        ChatHistoryService history = mock(ChatHistoryService.class);
        RagPromptTemplate promptTemplate = new RagPromptTemplate();

        when(history.resolveSession(null, 1L, "流式空检索会怎样？")).thenReturn(101L);
        when(history.recentMessages(101L, 8)).thenReturn(List.of());
        when(retriever.retrieveWithResult(
                1L, "流式空检索会怎样？", 5))
                .thenReturn(retrieval(List.of()));

        RagChatService service = new RagChatService(
                retriever, promptTemplate, chatClient, history, 5, 8
        );

        RagChatStreamResponse response = service.stream(1L, null, "流式空检索会怎样？");

        assertEquals(101L, response.sessionId());
        assertEquals(List.of(), response.references());
        verify(history, never()).appendMessage(anyLong(), anyString(), anyString());

        assertEquals(List.of(
                        "当前知识库中没有找到足够依据，暂时无法回答这个问题。"),
                response.stream().collectList().block());

        verify(chatClient, never()).call(anyString());
        verify(chatClient, never()).stream(anyString());
        verify(history).appendMessage(101L, "user", "流式空检索会怎样？");
        verify(history).appendMessage(101L, "assistant",
                "当前知识库中没有找到足够依据，暂时无法回答这个问题。");
    }

    @Test
    // 同步模型失败时，当前实现不会把本次问答误记为成功历史。
    void currentSynchronousModelFailureDoesNotAppendSuccessfulConversationMessages() {
        KnowledgeRetriever retriever = mock(KnowledgeRetriever.class);
        @SuppressWarnings("unchecked")
        PromptTemplate<RagPromptContext> prompt = mock(PromptTemplate.class);
        ChatClient chatClient = mock(ChatClient.class);
        ChatHistoryService history = mock(ChatHistoryService.class);
        BizException modelFailure = new BizException(
                500, "LLM request failed: request timed out");

        when(history.resolveSession(null, 1L, "同步失败问题")).thenReturn(102L);
        when(history.recentMessages(102L, 8)).thenReturn(List.of());
        RetrievedChunk evidence = new RetrievedChunk(
                102L, 10L, "同步失败.md", "同步失败问题",
                "同步失败问题会由模型错误出口处理。", 1, 0.9);
        when(retriever.retrieveWithResult(1L, "同步失败问题", 5))
                .thenReturn(retrieval(List.of(evidence)));
        when(prompt.render(any(RagPromptContext.class))).thenReturn("rendered-prompt");
        when(chatClient.call("rendered-prompt")).thenThrow(modelFailure);

        RagChatService service = new RagChatService(
                retriever, prompt, chatClient, history, 5, 8
        );

        BizException thrown = assertThrows(
                BizException.class,
                () -> service.chat(1L, null, "同步失败问题")
        );

        assertSame(modelFailure, thrown);
        verify(history, never()).appendMessage(anyLong(), anyString(), anyString());
    }

    @Test
    // 流式模型以错误结束时，doOnComplete 不执行，因此不会保存成功问答历史。
    void currentStreamingModelFailureDoesNotAppendSuccessfulConversationMessages() {
        KnowledgeRetriever retriever = mock(KnowledgeRetriever.class);
        @SuppressWarnings("unchecked")
        PromptTemplate<RagPromptContext> prompt = mock(PromptTemplate.class);
        ChatClient chatClient = mock(ChatClient.class);
        ChatHistoryService history = mock(ChatHistoryService.class);
        HttpTimeoutException modelFailure = new HttpTimeoutException("request timed out");

        when(history.resolveSession(null, 1L, "流式失败问题")).thenReturn(103L);
        when(history.recentMessages(103L, 8)).thenReturn(List.of());
        RetrievedChunk evidence = new RetrievedChunk(
                103L, 10L, "流式失败.md", "流式失败问题",
                "流式失败问题会由流式错误出口处理。", 1, 0.9);
        when(retriever.retrieveWithResult(1L, "流式失败问题", 5))
                .thenReturn(retrieval(List.of(evidence)));
        when(prompt.render(any(RagPromptContext.class))).thenReturn("rendered-prompt");
        when(chatClient.stream("rendered-prompt")).thenReturn(Flux.error(modelFailure));

        RagChatService service = new RagChatService(
                retriever, prompt, chatClient, history, 5, 8
        );
        RagChatStreamResponse response = service.stream(1L, null, "流式失败问题");

        Signal<String> terminal = response.stream().materialize().blockLast();

        assertTrue(terminal != null && terminal.isOnError());
        assertSame(modelFailure, terminal.getThrowable());
        verify(history, never()).appendMessage(anyLong(), anyString(), anyString());
    }

    private ChatMessage message(String role, String content) {
        ChatMessage message = new ChatMessage();
        message.setRole(role);
        message.setContent(content);
        return message;
    }

    private RetrievalExecutionResult retrieval(List<RetrievedChunk> chunks) {
        return RetrievalExecutionResult.unobserved(chunks);
    }
}
