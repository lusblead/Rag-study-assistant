package com.rag.backend.agent.llm;
import reactor.core.publisher.Flux;
public interface ChatClient {
    String call(String prompt);

    Flux<String> stream(String prompt);
}
