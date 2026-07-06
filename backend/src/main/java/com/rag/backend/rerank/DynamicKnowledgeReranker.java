package com.rag.backend.rerank;

import com.rag.backend.agent.rerank.KnowledgeReranker;
import com.rag.backend.agent.rerank.LocalLexicalKnowledgeReranker;
import com.rag.backend.agent.retrieval.RetrievedChunk;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.List;

@Primary
@Component
public class DynamicKnowledgeReranker implements KnowledgeReranker {
    private final RerankSettingsService settingsService;
    private final LocalLexicalKnowledgeReranker local = new LocalLexicalKnowledgeReranker();

    public DynamicKnowledgeReranker(RerankSettingsService settingsService) {
        this.settingsService = settingsService;
    }

    @Override
    public List<RetrievedChunk> rerank(String query, List<RetrievedChunk> chunks, int topK) {
        RerankSettings settings = settingsService.current();
        if ("none".equals(settings.getProvider())) return chunks.stream().limit(topK).toList();
        if ("local".equals(settings.getProvider())) return local.rerank(query, chunks, topK);
        try {
            return settingsService.rerankRemote(query, chunks, topK, settings);
        } catch (RuntimeException error) {
            if (Boolean.TRUE.equals(settings.getFailOpen())) return local.rerank(query, chunks, topK);
            throw error;
        }
    }
}
