package com.rag.backend.agent.llm;
import reactor.core.publisher.Flux;
// 为同一 Prompt 提供同步和流式模型调用边界；鉴权、超时及 Provider 协议由实现类封装。
public interface ChatClient {
    String call(String prompt);

    Flux<String> stream(String prompt);
}
