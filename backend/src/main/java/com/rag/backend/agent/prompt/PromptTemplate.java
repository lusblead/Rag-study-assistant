package com.rag.backend.agent.prompt;

// 定义提示词模板的统一渲染接口。
public interface PromptTemplate<T> {
    String render(T context);
}
