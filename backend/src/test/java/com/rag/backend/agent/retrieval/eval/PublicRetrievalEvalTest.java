package com.rag.backend.agent.retrieval.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rag.backend.agent.evaluation.Answerability;
import com.rag.backend.agent.evaluation.CaseRetrievalMetrics;
import com.rag.backend.agent.evaluation.RetrievalEvalReport;
import com.rag.backend.agent.evaluation.RetrievalGroundTruth;
import com.rag.backend.agent.evaluation.RetrievalMetricsAccumulator;
import com.rag.backend.agent.evaluation.RetrievalMetricsCalculator;
import com.rag.backend.agent.embedding.EmbeddingClient;
import com.rag.backend.agent.model.KnowledgeChunk;
import com.rag.backend.agent.repository.KnowledgeChunkRepository;
import com.rag.backend.agent.rerank.LocalLexicalKnowledgeReranker;
import com.rag.backend.agent.retrieval.MilvusKnowledgeRetriever;
import com.rag.backend.agent.retrieval.RetrievedChunk;
import com.rag.backend.ingestionlab.identity.StableHash;
import com.rag.backend.ingestionlab.vector.ConsistentVectorStore;
import com.rag.backend.ingestionlab.vector.InMemoryConsistentVectorStore;
import com.rag.backend.ingestionlab.vector.MilvusConsistentVectorStore;
import com.rag.backend.ingestionlab.retrieval.VersionedVectorHit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import com.sun.net.httpserver.HttpServer;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 使用公开 T2Retrieval qrels、硅基流动 BGE-M3 和临时 Milvus 跑真实检索基线。
 *
 * 该测试默认关闭，避免普通单测意外调用外部 Provider。只有显式传入
 * rag.public.eval=true 且存在临时 API Key 时才执行。
 */
@EnabledIfSystemProperty(named = "rag.public.eval", matches = "true")
@EnabledIfEnvironmentVariable(named = "RAG_EVAL_EMBEDDING_API_KEY", matches = ".+")
class PublicRetrievalEvalTest {
    private static final long COURSE_ID = 20_260_805L;
    private static final long ACTIVE_VERSION_ID = 1L;
    private static final int DIMENSION = 1024;
    private static final int BATCH_SIZE = 32;
    private static final int CANDIDATE_K = 20;
    private static final int TOP_K = 10;
    private final ObjectMapper json = new ObjectMapper();

    /**
     * 专用 closeout 路径：只读 corpus cache + 真实 query provider + 进程内 exact cosine。
     * 不改变上面的 Milvus 测试默认语义，也不写入 corpus cache。
     */
    @Test
    void runPublicT2SubsetWithRealBgeM3InProcessExactCosine() throws Exception {
        if (!Boolean.getBoolean("rag.eval.inProcessExactCosine")) return;
        Path root = Path.of(System.getProperty("rag.eval.datasetDir",
                "backend/evals/datasets/t2-retrieval-public-v1"));
        Path output = Path.of(System.getProperty("rag.eval.output"));
        verifyFrozenInputsAndExclusiveOutput(root, output);
        List<CorpusRow> corpus = readCorpus(root.resolve("corpus.jsonl"));
        List<EvalCase> cases = readCases(root.resolve("cases.jsonl"));
        assertEquals(2000, corpus.size(), "frozen corpus count");
        assertEquals(100, cases.size(), "frozen case count");
        assertEquals(508, cases.stream().mapToInt(c -> c.relevantDocumentIds().size()).sum(),
                "frozen positives");
        String baseUrl = requiredEnv("RAG_EVAL_EMBEDDING_BASE_URL");
        String model = requiredEnv("RAG_EVAL_EMBEDDING_MODEL");
        String apiKey = requiredEnv("RAG_EVAL_EMBEDDING_API_KEY");
        if (!"BAAI/bge-m3".equals(model)) throw new IllegalStateException("embedding model drift");
        Path cache = Path.of(System.getProperty("rag.eval.embeddingCache"));
        BatchEmbeddingGateway gateway = new BatchEmbeddingGateway(json, baseUrl, model, apiKey,
                DIMENSION, cache);
        Map<Long, List<Double>> vectors = gateway.embedCorpusFromReadOnlyCache(corpus);
        if (vectors.size() != 2000 || gateway.cacheHitCount != 2000) {
            throw new IllegalStateException("BLOCKED corpus cache miss");
        }
        InMemoryConsistentVectorStore store = new InMemoryConsistentVectorStore();
        Map<Long, Long> documentByChunk = new HashMap<>();
        for (CorpusRow row : corpus) {
            documentByChunk.put(row.chunkId(), row.documentId());
            store.upsert(new ConsistentVectorStore.VectorRecord(row.chunkId(), row.chunkId(), COURSE_ID,
                    row.documentId(), ACTIVE_VERSION_ID, "t2:" + row.documentId(),
                    StableHash.sha256(row.content()), model, DIMENSION, vectors.get(row.chunkId())));
        }
        List<Map<String, Object>> caseReports = new ArrayList<>();
        RetrievalMetricsCalculator metrics = new RetrievalMetricsCalculator();
        Totals totals = new Totals(metrics);
        for (EvalCase evalCase : cases) {
            long started = System.nanoTime();
            List<Double> query = gateway.embedLiveQuery(evalCase.query());
            List<VersionedVectorHit> hits = store.search(COURSE_ID, Set.of(ACTIVE_VERSION_ID), query, TOP_K)
                    .stream().sorted((a, b) -> {
                        int score = Double.compare(b.score(), a.score());
                        return score != 0 ? score : Long.compare(a.mysqlChunkId(), b.mysqlChunkId());
                    }).toList();
            List<Long> docs = new ArrayList<>(new LinkedHashSet<>(hits.stream()
                    .map(hit -> documentByChunk.get(hit.mysqlChunkId())).toList()));
            Map<Integer, CaseRetrievalMetrics> byK = Map.of(
                    1, metrics.evaluateCase(groundTruth(evalCase), docs, 1),
                    3, metrics.evaluateCase(groundTruth(evalCase), docs, 3),
                    5, metrics.evaluateCase(groundTruth(evalCase), docs, 5),
                    10, metrics.evaluateCase(groundTruth(evalCase), docs, 10));
            long latency = Duration.ofNanos(System.nanoTime() - started).toMillis();
            totals.add(groundTruth(evalCase), byK, latency);
            caseReports.add(Map.of("caseId", evalCase.caseId(), "returnedDocumentIds", docs,
                    "metrics", metrics(byK), "endToEndLatencyMs", latency));
        }
        if (gateway.querySuccessCount != 100 || gateway.requestCount < 100) {
            throw new IllegalStateException("incomplete live query budget");
        }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("evaluationType", "IN_PROCESS_EXACT_COSINE_COMPONENT_EVAL");
        report.put("evidenceBoundary", "IN_PROCESS_EXACT_COSINE_COMPONENT_EVAL; not Milvus E2E, Golden Dataset, refusal/answer quality, production performance, or Owner acceptance");
        report.put("configuration", Map.of("vectorStore", "InMemoryConsistentVectorStore",
                "visibilitySemantics", "process-local immediate visibility", "embeddingModel", model,
                "topK", TOP_K, "stableTieBreak", "score desc then chunkId asc"));
        report.put("providerUsage", gateway.usageReport());
        report.put("overall", totals.report());
        report.put("cases", caseReports);
        writeExclusiveReport(output, report);
    }

    @Test
    void retryableStatusesExhaustAfterFiveAttemptsWithoutCacheOrReportSideEffects() throws Exception {
        for (int status : List.of(429, 503, 504)) {
            AtomicInteger requests = new AtomicInteger();
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/embeddings", exchange -> {
                requests.incrementAndGet();
                exchange.sendResponseHeaders(status, -1);
                exchange.close();
            });
            server.start();
            Path missingCache = Path.of("target", "rag-public-eval-missing-cache-" + status
                    + "-" + System.nanoTime() + ".jsonl");
            try {
                BatchEmbeddingGateway gateway = new BatchEmbeddingGateway(json,
                        "http://127.0.0.1:" + server.getAddress().getPort(), "BAAI/bge-m3",
                        "test-key", DIMENSION, missingCache);

                assertThrows(IOException.class, () -> gateway.requestEmbeddings(List.of("query")));
                assertEquals(5, requests.get(), "retryable status " + status + " request count");
                assertEquals(5, gateway.requestCount, "retryable status " + status + " gateway count");
                assertEquals(4, gateway.retryCount, "retryable status " + status + " retry count");
                assertFalse(Files.exists(missingCache), "retry exhaustion must not create cache");
            } finally {
                server.stop(0);
            }
        }
    }

    @Test
    void preexistingOutputRejectsBeforeFrozenInputOrReportMutation() throws Exception {
        Path root = Path.of("backend/evals/datasets/t2-retrieval-public-v1");
        Path existingOutput = root.resolve("corpus.jsonl");
        String before = java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(existingOutput)));

        assertThrows(IllegalStateException.class,
                () -> verifyFrozenInputsAndExclusiveOutput(root, existingOutput));

        String after = java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(existingOutput)));
        assertEquals(before, after, "existing output must not be overwritten");
    }

    @Test
    void missingProviderConfigurationRejectsBeforeRequestDispatch() {
        assertThrows(IllegalStateException.class,
                () -> requiredEnv("__RAG_PUBLIC_EVAL_TEST_MISSING_CONFIG__"));
    }

    @Test
    void missingCorpusCacheFailsClosedWithoutRequestOrCacheWrite() throws Exception {
        Path missingCache = Path.of("target", "rag-public-eval-missing-corpus-"
                + System.nanoTime() + ".jsonl");
        BatchEmbeddingGateway gateway = new BatchEmbeddingGateway(json, "http://127.0.0.1:1",
                "BAAI/bge-m3", "test-key", DIMENSION, missingCache);

        assertThrows(IllegalStateException.class, () -> gateway.embedCorpusFromReadOnlyCache(List.of(
                new CorpusRow(1L, 1L, "title", "content", false))));
        assertEquals(0, gateway.requestCount, "cache rejection must precede Provider dispatch");
        assertFalse(Files.exists(missingCache), "cache rejection must not create cache");
    }

    private void verifyFrozenInputsAndExclusiveOutput(Path root, Path output) throws Exception {
        if (output == null || !Files.isDirectory(output.getParent()) || Files.exists(output))
            throw new IllegalStateException("exclusive output gate failed");
        verifySha(root.resolve("corpus.jsonl"), "C46E318E63B34207020982C6DA9781C81E2CC11C3F59FA4DDA5FB21960E7487B");
        verifySha(root.resolve("cases.jsonl"), "98E20783A39403D692D71166DB6CF7929D350F2B1D6E94CCBE2728A2E9999378");
    }

    private void verifySha(Path path, String expected) throws IOException, NoSuchAlgorithmException {
        String actual = java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(path))).toUpperCase();
        if (!expected.equals(actual)) throw new IllegalStateException("frozen input hash drift: " + path);
    }

    private void writeExclusiveReport(Path output, Map<String, Object> report) throws IOException {
        try (var stream = Files.newOutputStream(output, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            json.writerWithDefaultPrettyPrinter().writeValue(stream, report);
        }
    }

    @Test
    void runPublicT2SubsetWithRealBgeM3AndMilvus() throws Exception {
        if (Boolean.getBoolean("rag.eval.inProcessExactCosine")) return;
        Path datasetRoot = Path.of(System.getProperty(
                "rag.eval.datasetDir", "backend/evals/datasets/t2-retrieval-public-v1"));
        List<CorpusRow> corpus = readCorpus(datasetRoot.resolve("corpus.jsonl"));
        List<EvalCase> cases = readCases(datasetRoot.resolve("cases.jsonl"));
        JsonNode manifest = json.readTree(datasetRoot.resolve("manifest.json").toFile());
        assertFalse(corpus.isEmpty(), "公开评测 corpus 不能为空");
        assertFalse(cases.isEmpty(), "公开评测 query 不能为空");

        String baseUrl = requiredEnv("RAG_EVAL_EMBEDDING_BASE_URL");
        String model = requiredEnv("RAG_EVAL_EMBEDDING_MODEL");
        String apiKey = requiredEnv("RAG_EVAL_EMBEDDING_API_KEY");
        String queryMode = System.getProperty("rag.eval.queryMode", "replay").trim().toLowerCase();
        if (!"live".equals(queryMode) && !"replay".equals(queryMode)) {
            throw new IllegalArgumentException("rag.eval.queryMode 只允许 live 或 replay");
        }
        Path embeddingCache = Path.of(System.getProperty(
                "rag.eval.embeddingCache", ".cache/rag-public-eval/corpus-embeddings.jsonl"));
        BatchEmbeddingGateway embeddingGateway = new BatchEmbeddingGateway(
                json, baseUrl, model, apiKey, DIMENSION, embeddingCache);
        String milvusIndexType = System.getProperty(
                "rag.eval.milvusIndexType", "FLAT");

        Map<Long, List<Double>> corpusEmbeddings = embeddingGateway.embedCorpus(corpus, BATCH_SIZE);
        FixtureChunkRepository chunks = new FixtureChunkRepository();
        MilvusConsistentVectorStore vectorStore = new MilvusConsistentVectorStore(
                System.getProperty("rag.eval.milvusHost", "127.0.0.1"),
                Integer.parseInt(System.getProperty("rag.eval.milvusPort", "39530")),
                System.getProperty("rag.eval.milvusCollection", "rag_public_t2_eval_v1"),
                DIMENSION,
                milvusIndexType);

        for (CorpusRow row : corpus) {
            KnowledgeChunk chunk = toChunk(row);
            chunks.save(chunk);
            vectorStore.upsert(new ConsistentVectorStore.VectorRecord(
                    row.chunkId(), row.chunkId(), COURSE_ID,
                    row.documentId(), ACTIVE_VERSION_ID,
                    "t2:" + row.documentId(), StableHash.sha256(row.content()),
                    model, DIMENSION, corpusEmbeddings.get(row.chunkId())));
        }
        // Milvus 写入成功不等于本轮搜索已经能看到完整 corpus。评测必须在固定批次边界
        // flush，并确认版本向量数达到预期后再查询，否则会把最终一致性波动误判成算法变化。
        vectorStore.flush(Duration.ofSeconds(60).toMillis());
        awaitVersionVisible(vectorStore, corpus.size(), Duration.ofSeconds(60));

        // 语料批量建库；在线 query 仍逐条调用同一 API，使每条检索延迟包含真实 Provider 时间。
        EmbeddingClient queryEmbedding = "live".equals(queryMode)
                ? embeddingGateway::embedLiveQuery
                : embeddingGateway::embedReplayQuery;
        MilvusKnowledgeRetriever retriever = new MilvusKnowledgeRetriever(
                queryEmbedding,
                ignored -> Set.of(ACTIVE_VERSION_ID),
                vectorStore,
                chunks,
                null,
                new LocalLexicalKnowledgeReranker(0.7, 0.3),
                -1.0,
                CANDIDATE_K);

        List<Map<String, Object>> caseReports = new ArrayList<>();
        RetrievalMetricsCalculator metricsCore = new RetrievalMetricsCalculator();
        Totals totals = new Totals(metricsCore);
        try {
            for (EvalCase evalCase : cases) {
                long started = System.nanoTime();
                List<RetrievedChunk> rankedChunks = retriever.retrieve(
                        COURSE_ID, evalCase.query(), TOP_K);
                long latencyMs = Duration.ofNanos(System.nanoTime() - started).toMillis();

                // T2 的 qrels 是文档级。生产 Retriever 返回 Chunk，所以按首次出现顺序去重为文档排名。
                List<Long> rankedDocuments = new ArrayList<>(new LinkedHashSet<>(
                        rankedChunks.stream().map(RetrievedChunk::documentId).toList()));
                RetrievalGroundTruth groundTruth = groundTruth(evalCase);
                Map<Integer, CaseRetrievalMetrics> canonicalMetrics = Map.of(
                        1, metricsCore.evaluateCase(groundTruth, rankedDocuments, 1),
                        3, metricsCore.evaluateCase(groundTruth, rankedDocuments, 3),
                        5, metricsCore.evaluateCase(groundTruth, rankedDocuments, 5),
                        10, metricsCore.evaluateCase(groundTruth, rankedDocuments, 10));
                totals.add(groundTruth, canonicalMetrics, latencyMs);
                caseReports.add(caseReport(
                        evalCase, rankedChunks, rankedDocuments,
                        canonicalMetrics, latencyMs));
            }

            Map<String, Object> report = new LinkedHashMap<>();
            report.put("evaluationType", "PUBLIC_T2_RETRIEVAL_SUBSET");
            report.put("evidenceBoundary", manifest.path("evidenceBoundary").asText());
            report.put("dataset", json.convertValue(manifest, Map.class));
            report.put("executedAt", Instant.now().toString());
            report.put("codeRevision", System.getProperty("rag.eval.codeRevision", "UNRECORDED"));
            report.put("worktreeFingerprint", System.getProperty(
                    "rag.eval.worktreeFingerprint", "UNRECORDED"));
            report.put("configuration", Map.ofEntries(
                    Map.entry("embeddingProvider", "SiliconFlow/OpenAI-compatible"),
                    Map.entry("embeddingBaseUrl", withoutTrailingSlash(baseUrl)),
                    Map.entry("embeddingModel", model),
                    Map.entry("embeddingDimension", DIMENSION),
                    Map.entry("vectorStore", "MilvusConsistentVectorStore/2.4.11"),
                    Map.entry("vectorIndexType", milvusIndexType.toUpperCase()),
                    Map.entry("vectorVisibilityBarrier", "flush-and-count"),
                    Map.entry("reranker", "LocalLexicalKnowledgeReranker(0.7,0.3)"),
                    Map.entry("candidateK", CANDIDATE_K),
                    Map.entry("topK", TOP_K),
                    Map.entry("queryEmbeddingMode", queryMode),
                    Map.entry("metricLevel", "document-after-chunk-dedup")));
            report.put("providerUsage", embeddingGateway.usageReport());
            report.put("overall", totals.report());
            report.put("cases", caseReports);

            Path output = Path.of(System.getProperty(
                    "rag.eval.output", "target/rag-eval/t2-public-latest.json"));
            if (output.getParent() != null) {
                Files.createDirectories(output.getParent());
            }
            json.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report);
        } finally {
            // 临时 collection 即使测试断言失败也删除本轮版本，避免下次误复用陈旧向量。
            vectorStore.deleteByVersion(ACTIVE_VERSION_ID);
        }

        assertEquals(corpus.size(), corpusEmbeddings.size(), "每篇 corpus 都必须有真实 BGE-M3 向量");
    }

    private void awaitVersionVisible(
            MilvusConsistentVectorStore vectorStore,
            int expectedCount,
            Duration timeout) throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        long visibleCount = -1L;
        while (Instant.now().isBefore(deadline)) {
            visibleCount = vectorStore.countByVersion(ACTIVE_VERSION_ID);
            if (visibleCount == expectedCount) {
                return;
            }
            Thread.sleep(250L);
        }
        throw new AssertionError("Milvus 可见向量数未达到预期: expected="
                + expectedCount + ", actual=" + visibleCount);
    }

    private Map<String, Object> caseReport(
            EvalCase evalCase,
            List<RetrievedChunk> rankedChunks,
            List<Long> rankedDocuments,
            Map<Integer, CaseRetrievalMetrics> canonicalMetrics,
            long latencyMs) {
        Map<String, Object> metrics = metrics(canonicalMetrics);
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("caseId", evalCase.caseId());
        report.put("sourceQueryId", evalCase.sourceQueryId());
        report.put("query", evalCase.query());
        report.put("relevantDocumentIds", evalCase.relevantDocumentIds());
        report.put("returnedDocumentIds", rankedDocuments);
        report.put("returnedChunks", rankedChunks.stream().map(chunk -> Map.of(
                "chunkId", chunk.chunkId(),
                "documentId", chunk.documentId(),
                "score", chunk.score(),
                "title", chunk.title())).toList());
        report.put("metrics", metrics);
        report.put("endToEndLatencyMs", latencyMs);
        return report;
    }

    private Map<String, Object> metrics(
            Map<Integer, CaseRetrievalMetrics> canonicalMetrics) {
        CaseRetrievalMetrics at10 = canonicalMetrics.get(10);
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("recallAt1", canonicalMetrics.get(1).recallAtK());
        metrics.put("recallAt3", canonicalMetrics.get(3).recallAtK());
        metrics.put("recallAt5", canonicalMetrics.get(5).recallAtK());
        metrics.put("recallAt10", at10.recallAtK());
        metrics.put("reciprocalRank", at10.reciprocalRank());
        metrics.put("ndcgAt10", at10.ndcgAtK());
        return metrics;
    }

    private RetrievalGroundTruth groundTruth(EvalCase evalCase) {
        Set<Long> relevant = Set.copyOf(evalCase.relevantDocumentIds());
        return new RetrievalGroundTruth(
                evalCase.caseId(), Answerability.ANSWERABLE,
                relevant, relevant, List.of(), Map.of(), Set.of());
    }

    private List<CorpusRow> readCorpus(Path path) throws IOException {
        List<CorpusRow> rows = new ArrayList<>();
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            if (line.isBlank()) continue;
            JsonNode node = json.readTree(line);
            rows.add(new CorpusRow(
                    node.path("chunkId").asLong(),
                    node.path("documentId").asLong(),
                    node.path("title").asText(),
                    node.path("content").asText(),
                    node.path("truncated").asBoolean()));
        }
        return List.copyOf(rows);
    }

    private List<EvalCase> readCases(Path path) throws IOException {
        List<EvalCase> rows = new ArrayList<>();
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            if (line.isBlank()) continue;
            JsonNode node = json.readTree(line);
            rows.add(new EvalCase(
                    node.path("caseId").asText(),
                    node.path("sourceQueryId").asText(),
                    node.path("query").asText(),
                    longList(node.path("relevantDocumentIds"))));
        }
        return List.copyOf(rows);
    }

    private List<Long> longList(JsonNode values) {
        List<Long> result = new ArrayList<>();
        values.forEach(value -> result.add(value.asLong()));
        return List.copyOf(result);
    }

    private KnowledgeChunk toChunk(CorpusRow row) {
        KnowledgeChunk chunk = new KnowledgeChunk();
        chunk.setId(row.chunkId());
        chunk.setCourseId(COURSE_ID);
        chunk.setDocumentId(row.documentId());
        chunk.setDocumentVersionId(ACTIVE_VERSION_ID);
        chunk.setChunkIndex(0);
        chunk.setTitle(row.title());
        chunk.setContent(row.content());
        chunk.setSourcePage(1);
        return chunk;
    }

    private String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("缺少环境变量 " + name);
        }
        return value.trim();
    }

    private String withoutTrailingSlash(String value) {
        String result = value;
        while (result.endsWith("/")) result = result.substring(0, result.length() - 1);
        return result;
    }

    private record CorpusRow(long chunkId, long documentId, String title,
                             String content, boolean truncated) { }

    private record EvalCase(String caseId, String sourceQueryId, String query,
                            List<Long> relevantDocumentIds) { }

    private final class BatchEmbeddingGateway {
        private final ObjectMapper json;
        private final String endpoint;
        private final String model;
        private final String apiKey;
        private final int expectedDimension;
        private final Path cachePath;
        private final HttpClient http;
        private final Map<String, List<Double>> cache = new HashMap<>();
        private long requestCount;
        private long retryCount;
        private long promptTokens;
        private long totalTokens;
        private long providerLatencyMs;
        private long cacheHitCount;
        private long queryCacheHitCount;
        private long querySuccessCount;

        private BatchEmbeddingGateway(ObjectMapper json, String baseUrl, String model,
                                      String apiKey, int expectedDimension, Path cachePath)
                throws IOException {
            this.json = json;
            this.endpoint = withoutTrailingSlash(baseUrl) + "/embeddings";
            this.model = model;
            this.apiKey = apiKey;
            this.expectedDimension = expectedDimension;
            this.cachePath = cachePath;
            this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();
            loadCache();
        }

        private Map<Long, List<Double>> embedCorpus(List<CorpusRow> corpus, int batchSize)
                throws IOException, InterruptedException {
            Map<Long, List<Double>> result = new LinkedHashMap<>();
            List<CorpusRow> missing = new ArrayList<>();
            for (CorpusRow row : corpus) {
                String key = cacheKey(row.content());
                List<Double> vector = cache.get(key);
                if (vector == null) missing.add(row);
                else {
                    result.put(row.chunkId(), vector);
                    cacheHitCount++;
                }
            }

            for (int offset = 0; offset < missing.size(); offset += batchSize) {
                List<CorpusRow> batch = missing.subList(
                        offset, Math.min(missing.size(), offset + batchSize));
                List<List<Double>> vectors = requestEmbeddings(
                        batch.stream().map(CorpusRow::content).toList());
                for (int index = 0; index < batch.size(); index++) {
                    CorpusRow row = batch.get(index);
                    List<Double> vector = vectors.get(index);
                    result.put(row.chunkId(), vector);
                    appendCache(cacheKey(row.content()), vector);
                }
            }
            return Map.copyOf(result);
        }

        private Map<Long, List<Double>> embedCorpusFromReadOnlyCache(List<CorpusRow> corpus) {
            Map<Long, List<Double>> result = new LinkedHashMap<>();
            for (CorpusRow row : corpus) {
                List<Double> vector = cache.get(cacheKey(row.content()));
                if (vector == null || vector.size() != expectedDimension) {
                    throw new IllegalStateException("BLOCKED corpus cache miss");
                }
                result.put(row.chunkId(), vector);
                cacheHitCount++;
            }
            return Map.copyOf(result);
        }

        private List<Double> embedLiveQuery(String query) {
            try {
                List<Double> result = requestEmbeddings(List.of(query)).get(0);
                querySuccessCount++;
                return result;
            } catch (IOException e) {
                throw new IllegalStateException("Query Embedding 请求失败: " + e.getMessage(), e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Query Embedding 被中断", e);
            }
        }

        private List<Double> embedReplayQuery(String query) {
            String key = cacheKey(query);
            List<Double> cached = cache.get(key);
            if (cached != null) {
                queryCacheHitCount++;
                return cached;
            }
            try {
                List<Double> vector = requestEmbeddings(List.of(query)).get(0);
                appendCache(key, vector);
                return vector;
            } catch (IOException e) {
                throw new IllegalStateException("Replay Query Embedding 请求失败: " + e.getMessage(), e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Replay Query Embedding 被中断", e);
            }
        }

        private List<List<Double>> requestEmbeddings(List<String> inputs)
                throws IOException, InterruptedException {
            int[] delaysSeconds = {0, 1, 2, 4, 8};
            for (int attempt = 0; attempt < delaysSeconds.length; attempt++) {
                if (delaysSeconds[attempt] > 0) {
                    retryCount++;
                    Thread.sleep(delaysSeconds[attempt] * 1000L);
                }
                HttpResponse<String> response = send(inputs);
                if (response.statusCode() < 400) return parseResponse(response.body(), inputs.size());
                if (response.statusCode() != 429
                        && response.statusCode() != 503
                        && response.statusCode() != 504) {
                    throw new IOException("Embedding 返回不可重试状态 " + response.statusCode());
                }
            }
            throw new IOException("Embedding Provider 在 5 次有界尝试后仍不可用");
        }

        private HttpResponse<String> send(List<String> inputs)
                throws IOException, InterruptedException {
            ObjectNode root = json.createObjectNode();
            root.put("model", model);
            root.put("encoding_format", "float");
            ArrayNode input = root.putArray("input");
            inputs.forEach(input::add);
            HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(120))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(root)))
                    .build();
            long started = System.nanoTime();
            requestCount++;
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            providerLatencyMs += Duration.ofNanos(System.nanoTime() - started).toMillis();
            return response;
        }

        private List<List<Double>> parseResponse(String body, int expectedCount) throws IOException {
            JsonNode root = json.readTree(body);
            promptTokens += root.path("usage").path("prompt_tokens").asLong(0);
            totalTokens += root.path("usage").path("total_tokens").asLong(0);
            JsonNode data = root.path("data");
            if (!data.isArray() || data.size() != expectedCount) {
                throw new IOException("Embedding 返回数量错误，expected="
                        + expectedCount + ", actual=" + data.size());
            }
            List<List<Double>> result = new ArrayList<>();
            for (int expectedIndex = 0; expectedIndex < expectedCount; expectedIndex++) {
                JsonNode item = findByIndex(data, expectedIndex);
                JsonNode vectorNode = item.path("embedding");
                if (!vectorNode.isArray() || vectorNode.size() != expectedDimension) {
                    throw new IOException("Embedding 维度错误，expected=" + expectedDimension
                            + ", actual=" + vectorNode.size());
                }
                List<Double> vector = new ArrayList<>(expectedDimension);
                vectorNode.forEach(value -> vector.add(value.asDouble()));
                result.add(List.copyOf(vector));
            }
            return List.copyOf(result);
        }

        private JsonNode findByIndex(JsonNode data, int expectedIndex) throws IOException {
            for (JsonNode item : data) {
                if (item.path("index").asInt(-1) == expectedIndex) return item;
            }
            throw new IOException("Embedding 响应缺少 index=" + expectedIndex);
        }

        private void loadCache() throws IOException {
            if (!Files.isRegularFile(cachePath)) return;
            for (String line : Files.readAllLines(cachePath, StandardCharsets.UTF_8)) {
                if (line.isBlank()) continue;
                JsonNode node = json.readTree(line);
                JsonNode vectorNode = node.path("embedding");
                if (vectorNode.size() != expectedDimension) continue;
                List<Double> vector = new ArrayList<>(expectedDimension);
                vectorNode.forEach(value -> vector.add(value.asDouble()));
                cache.put(node.path("key").asText(), List.copyOf(vector));
            }
        }

        private void appendCache(String key, List<Double> vector) throws IOException {
            cache.put(key, vector);
            if (cachePath.getParent() != null) Files.createDirectories(cachePath.getParent());
            String line = json.writeValueAsString(Map.of("key", key, "embedding", vector)) + "\n";
            Files.writeString(cachePath, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }

        private String cacheKey(String content) {
            return StableHash.sha256(model + "\n" + content);
        }

        private String abbreviate(String value, int maxLength) {
            if (value == null) return "";
            return value.length() <= maxLength ? value : value.substring(0, maxLength) + "...";
        }

        private Map<String, Object> usageReport() {
            return Map.of(
                    "requestCount", requestCount,
                    "retryCount", retryCount,
                    "promptTokens", promptTokens,
                    "totalTokens", totalTokens,
                    "providerLatencyMs", providerLatencyMs,
                    "corpusCacheHitCount", cacheHitCount,
                    "queryCacheHitCount", queryCacheHitCount,
                    "querySuccessCount", querySuccessCount);
        }
    }

    private final class Totals {
        private final RetrievalMetricsCalculator metricsCore;
        private final RetrievalMetricsAccumulator canonical =
                new RetrievalMetricsAccumulator();
        private final List<Long> latencies = new ArrayList<>();

        private Totals(RetrievalMetricsCalculator metricsCore) {
            this.metricsCore = metricsCore;
        }

        private void add(RetrievalGroundTruth groundTruth,
                         Map<Integer, CaseRetrievalMetrics> metrics,
                         long latencyMs) {
            canonical.add(groundTruth, metrics);
            latencies.add(latencyMs);
        }

        private Map<String, Object> report() {
            RetrievalEvalReport at1 = metricsCore.summarizeGroundTruth(
                    canonical.groundTruths(), canonical.resultsAt(1), 1);
            RetrievalEvalReport at3 = metricsCore.summarizeGroundTruth(
                    canonical.groundTruths(), canonical.resultsAt(3), 3);
            RetrievalEvalReport at5 = metricsCore.summarizeGroundTruth(
                    canonical.groundTruths(), canonical.resultsAt(5), 5);
            RetrievalEvalReport at10 = metricsCore.summarizeGroundTruth(
                    canonical.groundTruths(), canonical.resultsAt(10), 10);
            List<Long> sorted = latencies.stream().sorted().toList();
            return Map.of(
                    "caseCount", canonical.size(),
                    "recallAt1", at1.macroRecallAtK(),
                    "recallAt3", at3.macroRecallAtK(),
                    "recallAt5", at5.macroRecallAtK(),
                    "recallAt10", at10.macroRecallAtK(),
                    "mrr", at10.meanReciprocalRank(),
                    "ndcgAt10", at10.macroNdcgAtK(),
                    "endToEndLatencyP50Ms", percentile(sorted, 0.50),
                    "endToEndLatencyP95Ms", percentile(sorted, 0.95));
        }

        private long percentile(List<Long> sorted, double percentile) {
            if (sorted.isEmpty()) return 0L;
            int index = (int) Math.ceil(percentile * sorted.size()) - 1;
            return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
        }
    }

    /** 公开评测只允许按 ID 回表，禁止测试悄悄退回 course 级全量扫描。 */
    private static final class FixtureChunkRepository implements KnowledgeChunkRepository {
        private final Map<Long, KnowledgeChunk> values = new HashMap<>();

        @Override
        public KnowledgeChunk save(KnowledgeChunk chunk) {
            values.put(chunk.getId(), chunk);
            return chunk;
        }

        @Override
        public KnowledgeChunk findById(long id) {
            return values.get(id);
        }

        @Override
        public List<KnowledgeChunk> findByCourseId(long courseId, int limit) {
            throw new UnsupportedOperationException("公开评测禁止 course 级兜底");
        }

        @Override
        public void deleteByDocumentId(long documentId) {
            throw new UnsupportedOperationException("公开评测语料只读");
        }

        @Override
        public void deleteByCourseId(long courseId) {
            throw new UnsupportedOperationException("公开评测语料只读");
        }

        @Override
        public void updateVectorStatus(Long chunkId, String milvusVectorId, String embeddingStatus) {
            throw new UnsupportedOperationException("公开评测语料只读");
        }
    }
}
