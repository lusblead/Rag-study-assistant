package com.rag.backend.agent.embedding;

import java.util.List;

// 将一段文本转换为单个向量；Provider 选择和本地/远程失败策略由实现类处理。
public interface EmbeddingClient {
    List<Double> embed(String text);
}
