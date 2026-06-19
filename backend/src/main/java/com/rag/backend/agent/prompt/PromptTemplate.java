package com.rag.backend.agent.prompt;

public interface PromptTemplate<T> {
    String render(T context);
}
