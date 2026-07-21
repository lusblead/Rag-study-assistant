package com.rag.backend.agent.rerank;

import com.rag.backend.agent.retrieval.RetrievedChunk;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@ConditionalOnExpression("'${rerank.provider:local}' == 'none'")
public class NoOpKnowledgeReranker implements KnowledgeReranker {
    @Override
    public List<RetrievedChunk> rerank(String query, List<RetrievedChunk> chunks, int topK) {
        return chunks.stream().limit(topK).toList();
    }
}
