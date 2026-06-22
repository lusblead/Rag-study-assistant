package com.rag.backend.agent.retrieval;

import com.rag.backend.agent.embedding.EmbeddingClient;
import com.rag.backend.agent.model.KnowledgeChunk;
import com.rag.backend.agent.model.VectorSearchResult;
import com.rag.backend.agent.rerank.KnowledgeReranker;
import com.rag.backend.agent.repository.KnowledgeChunkRepository;
import com.rag.backend.agent.vector.VectorStoreService;
import com.rag.backend.common.BizException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
// 使用向量库和重排序器检索课程知识片段。
public class MilvusKnowledgeRetriever implements KnowledgeRetriever {
    private final EmbeddingClient embeddingClient;
    private final VectorStoreService vectorStoreService;
    private final KnowledgeChunkRepository chunkRepository;
    private final KnowledgeReranker reranker;
    private final double similarityThreshold;
    private final int candidateK;

    public MilvusKnowledgeRetriever(EmbeddingClient embeddingClient,
                                    VectorStoreService vectorStoreService,
                                    KnowledgeChunkRepository chunkRepository,
                                    KnowledgeReranker reranker,
                                    @Value("${rag.similarity-threshold:0.0}") double similarityThreshold,
                                    @Value("${rag.candidate-k:20}") int candidateK) {
        this.embeddingClient = embeddingClient;
        this.vectorStoreService = vectorStoreService;
        this.chunkRepository = chunkRepository;
        this.reranker = reranker;
        this.similarityThreshold = similarityThreshold;
        this.candidateK = candidateK;
    }

    @Override
    public List<RetrievedChunk> retrieve(Long courseId,String query,int topK){
        List<Double> queryVector = embeddingClient.embed(query);
        int searchK = Math.max(topK, candidateK);
        List<VectorSearchResult> results = vectorStoreService.search(courseId, queryVector, searchK);

        List<RetrievedChunk> candidates = results.stream()
                .filter(result -> result.score() == null || result.score() >= similarityThreshold)
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
        if (candidates.isEmpty()) {
            candidates = chunkRepository.findByCourseId(courseId, searchK).stream()
                    .map(chunk -> new RetrievedChunk(
                            chunk.getId(),
                            chunk.getDocumentId(),
                            chunk.getTitle(),
                            chunk.getContent(),
                            0.0
                    ))
                    .toList();
        }
        return reranker.rerank(query, candidates, topK);
    }
}
