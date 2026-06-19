package com.rag.backend.agent.llm;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

@Component
@ConditionalOnProperty(name = "agent.mock", havingValue = "true", matchIfMissing = true)
public class MockChatClient implements ChatClient {
    @Override
    public String call(String prompt) {
        if (prompt != null && prompt.contains("JSON") && prompt.contains("type")) {
            return """
                    [
                      {
                        "type": "single_choice",
                        "stem": "Mock question: which statement follows the retrieved course material?",
                        "options": ["A. Use retrieved material", "B. Ignore retrieved material", "C. Guess randomly", "D. Avoid references"],
                        "answer": "A",
                        "explanation": "Mock output for validating the AI question generation pipeline.",
                        "difficulty": "medium",
                        "knowledgePoint": "RAG question generation",
                        "sourceChunkId": null
                      }
                    ]
                    """;
        }
        return "Mock AI answer. The RAG orchestration is working; configure a real model for production answers.\n\n"
                + "Prompt preview:\n"
                + prompt.substring(0, Math.min(prompt.length(), 300));
    }

    @Override
    public Flux<String> stream(String prompt) {
        return Flux.just(call(prompt));
    }
}