package com.rag.backend.agent.vector;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.rag.backend.agent.model.KnowledgeChunk;
import com.rag.backend.agent.model.VectorSearchResult;
import com.rag.backend.agent.repository.KnowledgeChunkRepository;
import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.AddFieldReq;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import io.milvus.v2.service.vector.request.DeleteReq;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.request.UpsertReq;
import io.milvus.v2.service.vector.request.data.FloatVec;
import io.milvus.v2.service.vector.response.SearchResp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Service
@ConditionalOnProperty(name = "agent.mock", havingValue = "false")
public class MilvusVectorStoreService implements VectorStoreService {

    private static final Logger log = LoggerFactory.getLogger(MilvusVectorStoreService.class);

    private final MilvusClientV2 client;
    private final KnowledgeChunkRepository chunkRepository1;
    private final String collectionName;
    private final int embeddingDimension;
    private final Gson gson = new Gson();

    public MilvusVectorStoreService(
            @Value("${milvus.host}") String host,
            @Value("${milvus.port}") int port,
            @Value("${milvus.collection-name}") String collectionName,
            @Value("${milvus.embedding-dimension}") int embeddingDimension,
            KnowledgeChunkRepository chunkRepository1) {
        this.collectionName = collectionName;
        this.embeddingDimension = embeddingDimension;
        this.chunkRepository1 = chunkRepository1;

        ConnectConfig config = ConnectConfig.builder()
                .uri("http://" + host + ":" + port)
                .build();
        this.client = new MilvusClientV2(config);

        initCollection();
    }

    // ── 初始化 collection ──────────────────────────────────────────────

    private void initCollection() {
        HasCollectionReq hasReq = HasCollectionReq.builder()
                .collectionName(collectionName)
                .build();
        if (client.hasCollection(hasReq)) {
            log.info("Milvus collection '{}' already exists", collectionName);
            return;
        }
        log.info("Creating Milvus collection '{}' (dimension={})", collectionName, embeddingDimension);

        CreateCollectionReq.CollectionSchema schema = client.createSchema();
        schema.addField(AddFieldReq.builder()
                .fieldName("chunk_id").dataType(DataType.Int64).isPrimaryKey(true).autoID(false).build());
        schema.addField(AddFieldReq.builder()
                .fieldName("course_id").dataType(DataType.Int64).build());
        schema.addField(AddFieldReq.builder()
                .fieldName("document_id").dataType(DataType.Int64).build());
        schema.addField(AddFieldReq.builder()
                .fieldName("embedding").dataType(DataType.FloatVector).dimension(embeddingDimension).build());

        IndexParam indexParam = IndexParam.builder()
                .fieldName("embedding")
                .metricType(IndexParam.MetricType.COSINE)
                .build();

        CreateCollectionReq createReq = CreateCollectionReq.builder()
                .collectionName(collectionName)
                .collectionSchema(schema)
                .indexParams(Collections.singletonList(indexParam))
                .build();
        client.createCollection(createReq);
        log.info("Milvus collection '{}' created", collectionName);
    }

    // ── 写入 / 更新向量 ───────────────────────────────────────────────

    @Override
    public String upsert(KnowledgeChunk chunk, List<Double> embedding) {
        JsonObject row = new JsonObject();
        row.addProperty("chunk_id", chunk.getId());
        row.addProperty("course_id", chunk.getCourseId());
        row.addProperty("document_id", chunk.getDocumentId());
        row.add("embedding", gson.toJsonTree(toFloatList(embedding)));

        UpsertReq req = UpsertReq.builder()
                .collectionName(collectionName)
                .data(Collections.singletonList(row))
                .build();
        client.upsert(req);

        String milvusVectorId = String.valueOf(chunk.getId());
        chunkRepository1.updateVectorStatus(chunk.getId(), milvusVectorId, "DONE");
        return milvusVectorId;
    }

    // ── 向量相似搜索 ──────────────────────────────────────────────────

    @Override
    public List<VectorSearchResult> search(Long courseId, List<Double> queryVector, int topK) {
        SearchReq req = SearchReq.builder()
                .collectionName(collectionName)
                .data(Collections.singletonList(new FloatVec(toFloatArray(queryVector))))
                .filter("course_id == " + courseId)
                .topK(topK)
                .outputFields(Collections.singletonList("chunk_id"))
                .build();

        SearchResp resp = client.search(req);
        List<List<SearchResp.SearchResult>> searchResults = resp.getSearchResults();
        if (searchResults.isEmpty()) {
            return List.of();
        }

        List<VectorSearchResult> results = new ArrayList<>();
        for (SearchResp.SearchResult result : searchResults.get(0)) {
            Long chunkId = ((Number) result.getId()).longValue();
            results.add(new VectorSearchResult(chunkId, (double) result.getScore()));
        }
        return results;
    }

    // ── 按文档删除向量 ───────────────────────────────────────────────

    @Override
    public void deleteByDocumentId(Long documentId) {
        DeleteReq req = DeleteReq.builder()
                .collectionName(collectionName)
                .filter("document_id == " + documentId)
                .build();
        client.delete(req);
    }

    // ── 类型转换工具 ──────────────────────────────────────────────────

    private List<Float> toFloatList(List<Double> embedding) {
        List<Float> floats = new ArrayList<>(embedding.size());
        for (Double d : embedding) {
            floats.add(d.floatValue());
        }
        return floats;
    }

    private float[] toFloatArray(List<Double> embedding) {
        float[] floats = new float[embedding.size()];
        for (int i = 0; i < embedding.size(); i++) {
            floats[i] = embedding.get(i).floatValue();
        }
        return floats;
    }
}
