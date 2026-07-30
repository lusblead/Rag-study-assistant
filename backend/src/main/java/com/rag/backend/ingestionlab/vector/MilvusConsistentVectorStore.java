// 用确定性 ID 让 MySQL 与向量库在重放后收敛。
package com.rag.backend.ingestionlab.vector;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.AddFieldReq;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import io.milvus.v2.service.vector.request.DeleteReq;
import io.milvus.v2.service.vector.request.QueryReq;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.request.UpsertReq;
import io.milvus.v2.service.vector.request.data.FloatVec;
import io.milvus.v2.service.vector.response.QueryResp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
// MilvusConsistentVectorStore：ConsistentVectorStore 的 Milvus 实现，使用新 collection，
// 主键为应用计算的确定性 vectorId，同时保存完整业务身份元数据供对账。
public class MilvusConsistentVectorStore implements ConsistentVectorStore {
    private static final Logger log = LoggerFactory.getLogger(MilvusConsistentVectorStore.class);

    private final MilvusClientV2 client;
    private final String collectionName;
    private final int embeddingDimension;
    private final Gson gson = new Gson();

    // 从配置读取新 collection 名称与维度；旧 collection 不受影响。
    public MilvusConsistentVectorStore(@Value("${milvus.host}") String host,
                                       @Value("${milvus.port}") int port,
                                       @Value("${ingestion.milvus.collection-name}") String collectionName,
                                       @Value("${milvus.embedding-dimension}") int embeddingDimension) {
        this.collectionName = collectionName;
        this.embeddingDimension = embeddingDimension;
        ConnectConfig config = ConnectConfig.builder()
                .uri("http://" + host + ":" + port)
                .build();
        this.client = new MilvusClientV2(config);
        initCollection();
    }

    // ── collection 初始化 ────────────────────────────────────────────
    // 使用新 schema：vector_id 为主键且关闭 autoID；同时保存 mysql_chunk_id、
    // document_version_id、chunk 身份、模型、维度等可核验字段。
    private void initCollection() {
        HasCollectionReq hasReq = HasCollectionReq.builder()
                .collectionName(collectionName)
                .build();
        if (client.hasCollection(hasReq)) {
            log.info("Milvus collection '{}' already exists", collectionName);
            return;
        }

        CreateCollectionReq.CollectionSchema schema = client.createSchema();

        // vector_id 由应用计算且关闭 autoID；重复 upsert 才能落到同一 Milvus 主键。
        schema.addField(AddFieldReq.builder()
                .fieldName("vector_id").dataType(DataType.Int64)
                .isPrimaryKey(true).autoID(false).build());
        // mysql_chunk_id 用于检索回表；document_version_id 用于可见性过滤和对账。
        schema.addField(AddFieldReq.builder()
                .fieldName("mysql_chunk_id").dataType(DataType.Int64).build());
        schema.addField(AddFieldReq.builder()
                .fieldName("course_id").dataType(DataType.Int64).build());
        schema.addField(AddFieldReq.builder()
                .fieldName("document_id").dataType(DataType.Int64).build());
        schema.addField(AddFieldReq.builder()
                .fieldName("document_version_id").dataType(DataType.Int64).build());
        // 远端保留 Chunk 业务身份，find(vectorId) 才能发现碰撞或误复用。
        schema.addField(AddFieldReq.builder()
                .fieldName("chunk_business_key")
                .dataType(DataType.VarChar).maxLength(128).build());
        schema.addField(AddFieldReq.builder()
                .fieldName("content_hash")
                .dataType(DataType.VarChar).maxLength(64).build());
        schema.addField(AddFieldReq.builder()
                .fieldName("embedding_model")
                .dataType(DataType.VarChar).maxLength(128).build());
        schema.addField(AddFieldReq.builder()
                .fieldName("embedding_dimension").dataType(DataType.Int64).build());
        // embedding 维度必须与 pipelineFingerprint 中记录的模型维度一致。
        schema.addField(AddFieldReq.builder()
                .fieldName("embedding").dataType(DataType.FloatVector)
                .dimension(embeddingDimension).build());

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
        log.info("Milvus collection '{}' created with dimension {}", collectionName, embeddingDimension);
    }

    // ── ConsistentVectorStore 实现 ────────────────────────────────────

    @Override
    public void upsert(VectorRecord record) {
        JsonObject row = new JsonObject();
        row.addProperty("vector_id", record.vectorId());
        row.addProperty("mysql_chunk_id", record.mysqlChunkId());
        row.addProperty("course_id", record.courseId());
        row.addProperty("document_id", record.documentId());
        row.addProperty("document_version_id", record.documentVersionId());
        row.addProperty("chunk_business_key", record.chunkBusinessKey());
        row.addProperty("content_hash", record.contentHash());
        row.addProperty("embedding_model", record.embeddingModel());
        row.addProperty("embedding_dimension", record.dimension());
        row.add("embedding", gson.toJsonTree(toFloatList(record.embedding())));

        UpsertReq req = UpsertReq.builder()
                .collectionName(collectionName)
                .data(Collections.singletonList(row))
                .build();
        client.upsert(req);
    }

    @Override
    public Optional<VectorMetadata> find(long vectorId) {
        QueryResp resp = client.query(QueryReq.builder()
                .collectionName(collectionName)
                .filter("vector_id == " + vectorId)
                .outputFields(List.of("vector_id", "mysql_chunk_id",
                        "document_version_id", "chunk_business_key",
                        "content_hash", "embedding_model", "embedding_dimension"))
                .limit(1)
                .build());

        List<QueryResp.QueryResult> results = resp.getQueryResults();
        if (results.isEmpty()) {
            return Optional.empty();
        }

        Map<String, Object> row = results.get(0).getEntity();
        return Optional.of(new VectorMetadata(
                toLong(row.get("vector_id")),
                toLong(row.get("mysql_chunk_id")),
                toLong(row.get("document_version_id")),
                (String) row.get("chunk_business_key"),
                (String) row.get("content_hash"),
                (String) row.get("embedding_model"),
                toInt(row.get("embedding_dimension"))));
    }

    @Override
    public long countByVersion(long documentVersionId) {
        // 用简单查询表达式统计指定 version 下的向量数。
        QueryResp resp = client.query(QueryReq.builder()
                .collectionName(collectionName)
                .filter("document_version_id == " + documentVersionId)
                .outputFields(Collections.singletonList("vector_id"))
                .limit(10000) // Milvus query 默认限制较小，按需调大。
                .build());
        return resp.getQueryResults().size();
    }

    @Override
    public Set<Long> listIdsByVersion(long documentVersionId) {
        QueryResp resp = client.query(QueryReq.builder()
                .collectionName(collectionName)
                .filter("document_version_id == " + documentVersionId)
                .outputFields(Collections.singletonList("vector_id"))
                .limit(10000)
                .build());
        return resp.getQueryResults().stream()
                .map(r -> toLong(r.getEntity().get("vector_id")))
                .collect(Collectors.toSet());
    }

    @Override
    public void deleteByVersion(long documentVersionId) {
        DeleteReq req = DeleteReq.builder()
                .collectionName(collectionName)
                .filter("document_version_id == " + documentVersionId)
                .build();
        client.delete(req);
    }

    // ── 辅助方法 ──────────────────────────────────────────────────────

    private List<Float> toFloatList(List<Double> embedding) {
        List<Float> floats = new ArrayList<>(embedding.size());
        for (Double value : embedding) {
            floats.add(value.floatValue());
        }
        return floats;
    }

    private long toLong(Object value) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        return Long.parseLong(String.valueOf(value));
    }

    private int toInt(Object value) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        return Integer.parseInt(String.valueOf(value));
    }
}
