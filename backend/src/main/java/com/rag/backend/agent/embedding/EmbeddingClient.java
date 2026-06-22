package com.rag.backend.agent.embedding;

import java.util.List;

// 定义文本向量化客户端的统一接口。
public interface EmbeddingClient {
    List<Double> embed(String text);
}
