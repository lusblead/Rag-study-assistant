package com.rag.backend.agent.retrieval;

import java.util.List;

public interface KnowledgeRetriever {
    List<RetrievedChunk> retrieve(Long courseId,String query,int topK);
}
