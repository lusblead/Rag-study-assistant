package com.rag.backend.agent.llm;
import reactor.core.publisher.Flux;
// 定义大模型聊天客户端的统一接口。
public interface ChatClient {
    String call(String prompt);

    Flux<String> stream(String prompt);
}
