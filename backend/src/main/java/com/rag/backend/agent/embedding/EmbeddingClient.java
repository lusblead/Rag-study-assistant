package com.rag.backend.agent.embedding;

import java.util.List;

public interface EmbeddingClient {
    List<Double> embed(String text);
}
