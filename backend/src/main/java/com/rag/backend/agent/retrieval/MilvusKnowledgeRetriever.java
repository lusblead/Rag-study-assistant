package com.rag.backend.agent.retrieval;

import com.rag.backend.agent.embedding.EmbeddingClient;
import com.rag.backend.agent.model.KnowledgeChunk;
import com.rag.backend.agent.model.VectorSearchResult;
import com.rag.backend.agent.repository.KnowledgeChunkRepository;
import com.rag.backend.agent.vector.VectorStoreService;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class MilvusKnowledgeRetriever implements KnowledgeRetriever {
    private final EmbeddingClient embeddingClient;
    private final VectorStoreService vectorStoreService;
    private final KnowledgeChunkRepository chunkRepository;

    public MilvusKnowledgeRetriever(EmbeddingClient embeddingClient, VectorStoreService vectorStoreService, KnowledgeChunkRepository chunkRepository) {
        this.embeddingClient = embeddingClient;
        this.vectorStoreService = vectorStoreService;
        this.chunkRepository = chunkRepository;
    }

    @Override
    public List<RetrievedChunk> retrieve(Long courseId,String query,int topK){
        List<Double> queryVector = embeddingClient.embed(query);
        List<VectorSearchResult> results = vectorStoreService.search(courseId, queryVector, topK);

        return results.stream()
                .map(result ->{
                    KnowledgeChunk chunk = chunkRepository.findById(result.chunkId());
                    if(chunk==null){
                        throw new BizException("知识片段不存在，chunkId=" + result.chunkId());
                    }
                    return new RetrievedChunk(
                            chunk.getId(),
                            chunk.getDocumentId(),
                            chunk.getTitle(),
                            chunk.getContent(),
                            result.score()
                    );
                })
                .toList();
    }
}
