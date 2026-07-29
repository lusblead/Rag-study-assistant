package com.rag.backend.agent.prompt;

// 把类型化上下文渲染为 Prompt 文本；模板不调用模型，也不决定检索或业务路由。
public interface PromptTemplate<T> {
    String render(T context);
}
