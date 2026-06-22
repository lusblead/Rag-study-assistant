package com.rag.backend.agent.retrieval;

import java.util.List;

// 定义课程知识检索的统一接口。
public interface KnowledgeRetriever {
    List<RetrievedChunk> retrieve(Long courseId,String query,int topK);
}
