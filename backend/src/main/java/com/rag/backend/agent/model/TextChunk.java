package com.rag.backend.agent.model;

// 表示文本切片后的单个片段。
public record TextChunk(Integer index,String title,String content,
                        Integer sourcePage,
                        Integer tokenCount) {
}
