package com.rag.backend.agent.model;

// 表示文档解析出的单页文本内容。
public record PageText(Integer pageNo, String text) {
}
