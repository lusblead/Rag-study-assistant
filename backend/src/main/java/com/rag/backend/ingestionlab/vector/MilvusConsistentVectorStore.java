// 用确定性 ID 让 MySQL 与向量库在重放后收敛。
package com.rag.backend.ingestionlab.vector;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.rag.backend.ingestionlab.retrieval.VersionedVectorHit;
import com.rag.backend.ingestionlab.retrieval.VersionedVectorSearch;
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
import io.milvus.v2.service.utility.request.FlushReq;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
@ConditionalOnExpression("'${agent.mock:false}' == 'false' && '${vector.provider:milvus}' == 'milvus'")
// MilvusConsistentVectorStore：ConsistentVectorStore 的 Milvus 实现，使用新 collection，
// 主键为应用计算的确定性 vectorId，同时保存完整业务身份元数据供对账。
public class MilvusConsistentVectorStore
        implements ConsistentVectorStore, VersionedVectorSearch {
    private static final Logger log = LoggerFactory.getLogger(MilvusConsistentVectorStore.class);

    private final MilvusClientV2 client;
    private final String collectionName;
    private final int embeddingDimension;
    private final IndexParam.IndexType indexType;
    private final Gson gson = new Gson();

    public MilvusConsistentVectorStore(String host,
                                       int port,
                                       String collectionName,
                                       int embeddingDimension,
                                       String indexType) {
        this(host, port, collectionName, embeddingDimension, indexType, 30_000L);
    }

    // 从配置读取新 collection 名称与维度；旧 collection 不受影响。
    @Autowired
    public MilvusConsistentVectorStore(@Value("${milvus.host}") String host,
                                       @Value("${milvus.port}") int port,
                                       @Value("${ingestion.milvus.collection-name}") String collectionName,
                                       @Value("${milvus.embedding-dimension}") int embeddingDimension,
                                       @Value("${ingestion.milvus.index-type:AUTOINDEX}") String indexType,
                                       @Value("${ingestion.milvus.rpc-deadline-ms:30000}") long rpcDeadlineMs) {
        if (rpcDeadlineMs <= 0) {
            throw new IllegalArgumentException(
                    "Milvus RPC deadline 必须大于 0");
        }
        this.collectionName = collectionName;
        this.embeddingDimension = embeddingDimension;
        this.indexType = parseIndexType(indexType);
        ConnectConfig config = ConnectConfig.builder()
                .uri("http://" + host + ":" + port)
                .connectTimeoutMs(rpcDeadlineMs)
                .rpcDeadlineMs(rpcDeadlineMs)
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
                .indexType(this.indexType)
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

    // 生产默认使用 AUTOINDEX；评测可以显式传入 FLAT，避免索引重建的近似召回波动。
    static IndexParam.IndexType parseIndexType(String configuredValue) {
        if (configuredValue == null || configuredValue.isBlank()) {
            return IndexParam.IndexType.AUTOINDEX;
        }
        try {
            return IndexParam.IndexType.valueOf(
                    configuredValue.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException invalidIndexType) {
            throw new IllegalArgumentException(
                    "不支持的 Milvus index type: " + configuredValue,
                    invalidIndexType);
        }
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

    /**
     * 等待当前 collection 的已写入 segment 完成 flush。
     * 在线链路不应每条写入都调用；awaitVersionVisible 只在完整批次边界调用一次。
     */
    public void flush(long timeoutMs) {
        if (timeoutMs <= 0) {
            throw new IllegalArgumentException("Milvus flush timeout 必须大于 0");
        }
        client.flush(FlushReq.builder()
                .collectionNames(List.of(collectionName))
                .waitFlushedTimeoutMs(timeoutMs)
                .build());
    }

    @Override
    public void awaitVersionVisible(long documentVersionId,
                                    int expectedCount,
                                    Duration timeout) {
        if (expectedCount < 0 || timeout == null || timeout.isZero()
                || timeout.isNegative()) {
            throw new IllegalArgumentException(
                    "Expected count must be non-negative and timeout positive");
        }

        long timeoutNanos;
        try {
            timeoutNanos = timeout.toNanos();
        } catch (ArithmeticException tooLarge) {
            throw new IllegalArgumentException(
                    "Vector visibility timeout is too large", tooLarge);
        }
        long started = System.nanoTime();
        flush(Math.max(1L, timeout.toMillis()));

        long observed = countByVersion(documentVersionId);
        while (observed < expectedCount) {
            long remaining = timeoutNanos - (System.nanoTime() - started);
            if (remaining <= 0) {
                throw new VectorVisibilityTimeoutException(
                        documentVersionId, expectedCount, observed, timeout);
            }
            try {
                TimeUnit.NANOSECONDS.sleep(Math.min(
                        remaining, TimeUnit.MILLISECONDS.toNanos(100)));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new VectorVisibilityTimeoutException(
                        documentVersionId, expectedCount, observed,
                        timeout, interrupted);
            }
            observed = countByVersion(documentVersionId);
        }
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
        return listIdsByVersion(documentVersionId).size();
    }

    @Override
    public Set<Long> listIdsByVersion(long documentVersionId) {
        final int pageSize = 1_000;
        long offset = 0;
        Set<Long> result = new HashSet<>();
        while (true) {
            QueryResp response = client.query(QueryReq.builder()
                    .collectionName(collectionName)
                    .filter("document_version_id == " + documentVersionId)
                    .outputFields(Collections.singletonList("vector_id"))
                    .offset(offset)
                    .limit(pageSize)
                    .build());
            List<QueryResp.QueryResult> page = response.getQueryResults();
            for (QueryResp.QueryResult row : page) {
                result.add(toLong(row.getEntity().get("vector_id")));
            }
            if (page.size() < pageSize) {
                return Set.copyOf(result);
            }
            offset += page.size();
        }
    }

    @Override
    public List<VersionedVectorHit> search(
            long courseId,
            Set<Long> activeVersionIds,
            List<Double> queryVector,
            int topK) {
        if (activeVersionIds.isEmpty() || topK <= 0) {
            return List.of();
        }
        String versions = activeVersionIds.stream()
                .sorted()
                .map(String::valueOf)
                .collect(Collectors.joining(", "));
        SearchReq request = SearchReq.builder()
                .collectionName(collectionName)
                .data(Collections.singletonList(
                        new FloatVec(toFloatArray(queryVector))))
                .filter("course_id == " + courseId
                        + " && document_version_id in [" + versions + "]")
                .topK(topK)
                .outputFields(List.of("mysql_chunk_id", "document_version_id"))
                .build();
        var searchResults = client.search(request).getSearchResults();
        if (searchResults.isEmpty()) {
            return List.of();
        }
        return searchResults.get(0).stream()
                .map(result -> new VersionedVectorHit(
                        toLong(result.getEntity().get("mysql_chunk_id")),
                        toLong(result.getEntity().get("document_version_id")),
                        result.getScore().doubleValue()))
                .toList();
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

    private float[] toFloatArray(List<Double> embedding) {
        float[] values = new float[embedding.size()];
        for (int index = 0; index < embedding.size(); index++) {
            values[index] = embedding.get(index).floatValue();
        }
        return values;
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
