package com.rag.backend.agent.vector;

import com.rag.backend.agent.model.KnowledgeChunk;
import com.rag.backend.agent.model.VectorSearchResult;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class MilvusVectorStoreService  implements VectorStoreService{
    @Override
    public String upsert(KnowledgeChunk chunk, List<Double> embedding){
        // TODO: 调用 Milvus Java SDK 写入向量
        // 字段建议包括：chunk_id、course_id、document_id、embedding
        return String.valueOf(chunk.getId());
    }

    @Override
    public List<VectorSearchResult> search(Long courseId,List<Double> queryVector,int topK){
        // TODO: 调用 Milvus search
        // 需要按 course_id 过滤，避免不同课程资料混在一起
        return List.of();
    }

    @Override
    public void deleteByDocumentId(Long documentId){
        // TODO: 删除某个 documentId 对应的 Milvus 向量
    }
}
