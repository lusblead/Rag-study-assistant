package com.rag.backend.agent.llm;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

@Component
@ConditionalOnProperty(name = "agent.mock", havingValue = "true", matchIfMissing = true)
public class MockChatClient implements ChatClient {
    @Override
    public String call(String prompt) {
        return "这是 Mock AI 回答。当前已经完成 RAG 流程编排，真实回答需要接入大模型。\n\n" +
                "以下是收到的 Prompt 前 300 个字符：\n" +
                prompt.substring(0, Math.min(prompt.length(), 300));
    }

    @Override
    public Flux<String> stream(String prompt) {
        return Flux.just(call(prompt));
    }
}