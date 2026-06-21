package com.rag.backend.agent.llm;

import reactor.core.publisher.Flux;

public class LocalChatClient implements ChatClient {
    @Override
    public String call(String prompt) {
        if (prompt != null && prompt.contains("JSON") && prompt.contains("type")) {
            return """
                    [
                      {
                        "type": "single_choice",
                        "stem": "According to the retrieved course material, which option is correct?",
                        "options": ["A. The answer should be based on the course material", "B. The answer should ignore the material", "C. The answer should be random", "D. The answer should be unrelated"],
                        "answer": "A",
                        "explanation": "The RAG workflow requires generated questions to be grounded in retrieved course chunks.",
                        "difficulty": "medium",
                        "knowledgePoint": "RAG workflow",
                        "sourceChunkId": null
                      }
                    ]
                    """;
        }
        return "Local RAG answer: the system retrieved course chunks and built a prompt successfully. "
                + "Configure a real ChatClient later if you need natural-language model quality.\n\n"
                + preview(prompt);
    }

    @Override
    public Flux<String> stream(String prompt) {
        return Flux.just(call(prompt));
    }

    private String preview(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            return "";
        }
        return prompt.substring(0, Math.min(prompt.length(), 600));
    }
}
