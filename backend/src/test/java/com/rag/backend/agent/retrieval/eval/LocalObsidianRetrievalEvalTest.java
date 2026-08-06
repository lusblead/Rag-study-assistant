package com.rag.backend.agent.retrieval.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rag.backend.agent.embedding.EmbeddingClient;
import com.rag.backend.agent.model.KnowledgeChunk;
import com.rag.backend.agent.repository.KnowledgeChunkRepository;
import com.rag.backend.agent.rerank.LocalLexicalKnowledgeReranker;
import com.rag.backend.agent.retrieval.MilvusKnowledgeRetriever;
import com.rag.backend.agent.retrieval.RetrievedChunk;
import com.rag.backend.ingestionlab.identity.StableHash;
import com.rag.backend.ingestionlab.vector.ConsistentVectorStore;
import com.rag.backend.ingestionlab.vector.MilvusConsistentVectorStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 本地 Obsidian v3 reviewed Golden Dataset 的检索评测。
 *
 * 与公共 T2 评测不同的地方：
 * 1. 真值是 chunk 级：relevant_chunk_ids / acceptable_chunk_ids / required_evidence_groups；
 * 2. 指标额外包含 Source Coverage、证据组覆盖和拒答（unanswerable）误召回；
 * 3. corpus 的 chunk_id 是字符串，Milvus 需要 long 主键，因此使用冻结排序后的
 *    稳定序号映射，并把映射指纹写入报告。
 *
 * 评测链路与公共基线一致：硅基流动 BAAI/bge-m3、临时 Milvus 2.4.11、
 * FLAT/COSINE 精确索引、flush + count 可见性屏障、LocalLexicalKnowledgeReranker(0.7,0.3)、
 * candidateK=20、topK=10。默认 replay 模式复用冻结 query 向量，不重复调用 Provider。
 */
@EnabledIfSystemProperty(named = "rag.local.obsidian.eval", matches = "true")
@EnabledIfEnvironmentVariable(named = "RAG_EVAL_EMBEDDING_API_KEY", matches = ".+")
class LocalObsidianRetrievalEvalTest {
    private static final long COURSE_ID = 20_260_806L;
    private static final long ACTIVE_VERSION_ID = 1L;
    private static final int DIMENSION = 1024;
    private static final int BATCH_SIZE = 32;
    private static final int CANDIDATE_K = 20;
    private static final int TOP_K = 10;
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void runReviewedLocalObsidianRetrievalBaseline() throws Exception {
        Path datasetRoot = Path.of(System.getProperty(
                "rag.eval.datasetDir",
                "backend/evals/datasets/local-obsidian-v3-reviewed"));
        String caseFileName = System.getProperty(
                "rag.local.obsidian.caseFile",
                "standard_reviewed_100_retrieval.jsonl");
        // 外置私有数据集必须带 Manifest 并通过 Checksum/规模校验；缺失或不匹配即拒绝执行。
        EvalFixtureSupport.validateLocalDatasetManifest(
                datasetRoot, datasetRoot.resolve("manifest.json"));
        List<CorpusRow> corpus = readCorpus(datasetRoot.resolve("corpus.jsonl"));
        List<ObsidianEvalCase> cases = readCases(datasetRoot.resolve(caseFileName));
        assertFalse(corpus.isEmpty(), "本地 Obsidian corpus 不能为空");
        assertFalse(cases.isEmpty(), "本地 Obsidian 检索 case 不能为空");

        String baseUrl = requiredEnv("RAG_EVAL_EMBEDDING_BASE_URL");
        String model = requiredEnv("RAG_EVAL_EMBEDDING_MODEL");
        String apiKey = requiredEnv("RAG_EVAL_EMBEDDING_API_KEY");
        String queryMode = System.getProperty(
                "rag.eval.queryMode", "replay").trim().toLowerCase();
        if (!"live".equals(queryMode) && !"replay".equals(queryMode)) {
            throw new IllegalArgumentException("rag.eval.queryMode 只允许 live 或 replay");
        }

        // chunk_id 与 source_document 都是稳定字符串；为 Milvus 生成冻结排序后的 long 序号。
        IdMapping mapping = buildIdMapping(corpus, cases);
        Path embeddingCache = Path.of(System.getProperty(
                "rag.eval.embeddingCache",
                ".cache/local-obsidian-eval/corpus-embeddings.jsonl"));
        ObsidianEmbeddingGateway embeddingGateway = new ObsidianEmbeddingGateway(
                json, baseUrl, model, apiKey, DIMENSION, embeddingCache);

        Map<Long, List<Double>> corpusEmbeddings =
                embeddingGateway.embedCorpus(corpus, mapping, BATCH_SIZE);
        FixtureChunkRepository chunks = new FixtureChunkRepository();
        MilvusConsistentVectorStore vectorStore = new MilvusConsistentVectorStore(
                System.getProperty("rag.eval.milvusHost", "127.0.0.1"),
                Integer.parseInt(System.getProperty("rag.eval.milvusPort", "39530")),
                System.getProperty(
                        "rag.eval.milvusCollection", "rag_obsidian_v3_eval_v1"),
                DIMENSION,
                System.getProperty("rag.eval.milvusIndexType", "FLAT"));

        for (CorpusRow row : corpus) {
            long chunkId = mapping.chunkIdToLong(row.chunkIdText());
            KnowledgeChunk chunk = new KnowledgeChunk();
            chunk.setId(chunkId);
            chunk.setCourseId(COURSE_ID);
            chunk.setDocumentId(mapping.documentIdToLong(row.sourceDocument()));
            chunk.setDocumentVersionId(ACTIVE_VERSION_ID);
            chunk.setChunkIndex(0);
            chunk.setTitle(row.title());
            chunk.setContent(row.content());
            chunk.setSourcePage(1);
            chunks.save(chunk);
            vectorStore.upsert(new ConsistentVectorStore.VectorRecord(
                    chunkId,
                    chunkId,
                    COURSE_ID,
                    mapping.documentIdToLong(row.sourceDocument()),
                    ACTIVE_VERSION_ID,
                    row.chunkIdText(),
                    StableHash.sha256(row.content()),
                    model,
                    DIMENSION,
                    corpusEmbeddings.get(chunkId)));
        }

        // Milvus 写入成功返回不等于 search 可见；固定批次边界 flush 并确认全部 chunk 可见。
        vectorStore.flush(Duration.ofSeconds(60).toMillis());
        awaitVersionVisible(vectorStore, corpus.size(), Duration.ofSeconds(60));

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
        MetricBucket overall = new MetricBucket();
        Map<String, MetricBucket> byType = new TreeMap<>();
        Map<String, MetricBucket> bySplit = new TreeMap<>();
        try {
            for (ObsidianEvalCase evalCase : cases) {
                long started = System.nanoTime();
                List<RetrievedChunk> rankedChunks = retriever.retrieve(
                        COURSE_ID, evalCase.question(), TOP_K);
                long latencyMs = Duration.ofNanos(started
                        - System.nanoTime()).abs().toMillis();
                latencyMs = Duration.ofNanos(System.nanoTime() - started).toMillis();

                List<Long> rankedChunkIds = rankedChunks.stream()
                        .map(RetrievedChunk::chunkId).toList();
                List<Long> rankedDocumentIds = rankedChunks.stream()
                        .map(RetrievedChunk::documentId).toList();
                List<Long> relevantIds = evalCase.relevantIds(mapping);
                List<Long> acceptableIds = evalCase.acceptableIds(mapping);
                List<Long> confusingIds = evalCase.confusingIds(mapping);
                Map<String, Object> caseMetrics = metrics(
                        rankedChunkIds, rankedDocumentIds, evalCase, relevantIds,
                        acceptableIds, confusingIds, mapping);
                overall.add(evalCase, caseMetrics, latencyMs);
                byType.computeIfAbsent(evalCase.questionType(), k -> new MetricBucket())
                        .add(evalCase, caseMetrics, latencyMs);
                bySplit.computeIfAbsent(evalCase.split(), k -> new MetricBucket())
                        .add(evalCase, caseMetrics, latencyMs);

                Map<String, Object> caseReport = new LinkedHashMap<>();
                caseReport.put("caseId", evalCase.caseId());
                caseReport.put("question", evalCase.question());
                caseReport.put("questionType", evalCase.questionType());
                caseReport.put("difficulty", evalCase.difficulty());
                caseReport.put("split", evalCase.split());
                caseReport.put("answerable", evalCase.answerable());
                caseReport.put("relevantChunkIds", evalCase.relevantChunkIds());
                caseReport.put("acceptableChunkIds", evalCase.acceptableChunkIds());
                caseReport.put("sourceDocuments", evalCase.sourceDocuments());
                caseReport.put("returnedChunkIds", rankedChunkIds);
                caseReport.put("returnedChunkTextIds", rankedChunks.stream()
                        .map(chunk -> mapping.longToChunkId(chunk.chunkId()))
                        .toList());
                caseReport.put("returnedDocumentTextIds", rankedChunks.stream()
                        .map(chunk -> mapping.longToDocumentId(chunk.documentId()))
                        .toList());
                caseReport.put("metrics", caseMetrics);
                caseReport.put("endToEndLatencyMs", latencyMs);
                caseReports.add(caseReport);
            }

            Map<String, Object> report = new LinkedHashMap<>();
            report.put("evaluationType", "LOCAL_OBSIDIAN_RETRIEVAL_REVIEWED");
            report.put("evidenceBoundary",
                    "本地业务语料重建的可审查检索集；reviewed 100 为正式入口，"
                            + "不替代官方全量 C-MTEB，也不代表线上 E2E");
            report.put("dataset", Map.ofEntries(
                    Map.entry("root", datasetRoot.toString()),
                    Map.entry("caseFile", caseFileName),
                    Map.entry("corpusSha256", sha256(datasetRoot.resolve("corpus.jsonl"))),
                    Map.entry("caseSha256", sha256(datasetRoot.resolve(caseFileName))),
                    Map.entry("corpusChunkCount", corpus.size()),
                    Map.entry("caseCount", cases.size()),
                    Map.entry("documentCount", mapping.documentCount()),
                    Map.entry("chunkIdMapFingerprint", mapping.chunkIdFingerprint()),
                    Map.entry("documentIdMapFingerprint", mapping.documentIdFingerprint())));
            report.put("executedAt", Instant.now().toString());
            report.put("codeRevision", System.getProperty(
                    "rag.eval.codeRevision", "UNRECORDED"));
            report.put("worktreeFingerprint", System.getProperty(
                    "rag.eval.worktreeFingerprint", "UNRECORDED"));
            report.put("configuration", Map.ofEntries(
                    Map.entry("embeddingProvider", "SiliconFlow/OpenAI-compatible"),
                    Map.entry("embeddingBaseUrl", withoutTrailingSlash(baseUrl)),
                    Map.entry("embeddingModel", model),
                    Map.entry("embeddingDimension", DIMENSION),
                    Map.entry("vectorStore", "MilvusConsistentVectorStore/2.4.11"),
                    Map.entry("vectorIndexType",
                            System.getProperty("rag.eval.milvusIndexType", "FLAT")),
                    Map.entry("vectorVisibilityBarrier", "flush-and-count"),
                    Map.entry("reranker", "LocalLexicalKnowledgeReranker(0.7,0.3)"),
                    Map.entry("candidateK", CANDIDATE_K),
                    Map.entry("topK", TOP_K),
                    Map.entry("queryEmbeddingMode", queryMode),
                    Map.entry("metricLevel", "chunk-with-source-and-group-coverage"),
                    Map.entry("idMapping", "sorted-string-to-long-ordinal")));
            report.put("providerUsage", embeddingGateway.usageReport());
            report.put("overall", overall.report());
            report.put("byQuestionType", byType.entrySet().stream()
                    .collect(TreeMap::new,
                            (m, e) -> m.put(e.getKey(), e.getValue().report()),
                            TreeMap::putAll));
            report.put("bySplit", bySplit.entrySet().stream()
                    .collect(TreeMap::new,
                            (m, e) -> m.put(e.getKey(), e.getValue().report()),
                            TreeMap::putAll));
            report.put("cases", caseReports);

            Path output = Path.of(System.getProperty(
                    "rag.eval.output", "target/rag-eval/local-obsidian-latest.json"));
            if (output.getParent() != null) {
                Files.createDirectories(output.getParent());
            }
            json.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report);
        } finally {
            vectorStore.deleteByVersion(ACTIVE_VERSION_ID);
        }

        assertEquals(corpus.size(), corpusEmbeddings.size(),
                "每篇 corpus chunk 都必须有真实 BGE-M3 向量");
    }

    private Map<String, Object> metrics(
            List<Long> rankedChunkIds,
            List<Long> rankedDocumentIds,
            ObsidianEvalCase evalCase,
            List<Long> relevantIds,
            List<Long> acceptableIds,
            List<Long> confusingIds,
            IdMapping mapping) {
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("strictRecallAt1", recallAt(rankedChunkIds, relevantIds, 1));
        metrics.put("strictRecallAt3", recallAt(rankedChunkIds, relevantIds, 3));
        metrics.put("strictRecallAt5", recallAt(rankedChunkIds, relevantIds, 5));
        metrics.put("strictRecallAt10", recallAt(rankedChunkIds, relevantIds, 10));
        metrics.put("acceptableRecallAt1", recallAt(rankedChunkIds, acceptableIds, 1));
        metrics.put("acceptableRecallAt3", recallAt(rankedChunkIds, acceptableIds, 3));
        metrics.put("acceptableRecallAt5", recallAt(rankedChunkIds, acceptableIds, 5));
        metrics.put("acceptableRecallAt10", recallAt(rankedChunkIds, acceptableIds, 10));
        metrics.put("strictMrr", reciprocalRank(rankedChunkIds, relevantIds));
        metrics.put("acceptableMrr", reciprocalRank(rankedChunkIds, acceptableIds));
        metrics.put("strictNdcgAt10", ndcgAt(rankedChunkIds, relevantIds, 10));
        metrics.put("acceptableNdcgAt10", ndcgAt(rankedChunkIds, acceptableIds, 10));
        metrics.put("firstRelevantRank", firstRelevantRank(rankedChunkIds, acceptableIds));
        metrics.put("sourceCoverageAt5", sourceCoverage(
                rankedDocumentIds, evalCase.sourceDocumentIds(mapping), 5));
        metrics.put("sourceCoverageAt10", sourceCoverage(
                rankedDocumentIds, evalCase.sourceDocumentIds(mapping), 10));
        metrics.put("evidenceGroupCoverageAt10", evidenceGroupCoverage(
                rankedChunkIds, evalCase.requiredEvidenceGroupIds(mapping), 10));
        metrics.put("misleadingRecall", misleadingRecall(rankedChunkIds, confusingIds));
        metrics.put("returnedAnyChunk", !rankedChunkIds.isEmpty());
        metrics.put("unanswerablePrecision", evalCase.answerable()
                ? -1.0
                : (rankedChunkIds.isEmpty() ? 1.0 : 0.0));
        return metrics;
    }

    private List<CorpusRow> readCorpus(Path path) throws IOException {
        List<CorpusRow> rows = new ArrayList<>();
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            if (line.isBlank()) continue;
            JsonNode node = json.readTree(line);
            rows.add(new CorpusRow(
                    node.path("chunk_id").asText(),
                    node.path("source_document").asText(),
                    node.path("title").asText(),
                    node.path("content").asText()));
        }
        return List.copyOf(rows);
    }

    private List<ObsidianEvalCase> readCases(Path path) throws IOException {
        List<ObsidianEvalCase> rows = new ArrayList<>();
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            if (line.isBlank()) continue;
            JsonNode node = json.readTree(line);
            List<List<String>> groups = new ArrayList<>();
            node.path("required_evidence_groups").forEach(group -> {
                List<String> ids = new ArrayList<>();
                group.path("chunk_ids").forEach(id -> ids.add(id.asText()));
                groups.add(List.copyOf(ids));
            });
            List<String> confusingIds = new ArrayList<>();
            node.path("confusing_chunks").forEach(chunk ->
                    confusingIds.add(chunk.path("chunk_id").asText()));
            rows.add(new ObsidianEvalCase(
                    node.path("id").asText(),
                    node.path("question").asText(),
                    node.path("question_type").asText(),
                    node.path("difficulty").asText(),
                    node.path("split").asText(),
                    node.path("answerable").asBoolean(true),
                    textList(node.path("relevant_chunk_ids")),
                    textList(node.path("acceptable_chunk_ids")),
                    List.copyOf(groups),
                    textList(node.path("source_documents")),
                    List.copyOf(confusingIds)));
        }
        return List.copyOf(rows);
    }

    private List<String> textList(JsonNode values) {
        List<String> result = new ArrayList<>();
        values.forEach(value -> result.add(value.asText()));
        return List.copyOf(result);
    }

    private IdMapping buildIdMapping(
            List<CorpusRow> corpus, List<ObsidianEvalCase> cases) {
        Set<String> chunkIds = new LinkedHashSet<>();
        Set<String> documents = new LinkedHashSet<>();
        corpus.forEach(row -> {
            chunkIds.add(row.chunkIdText());
            documents.add(row.sourceDocument());
        });
        cases.forEach(c -> {
            c.relevantChunkIds().forEach(chunkIds::add);
            c.acceptableChunkIds().forEach(chunkIds::add);
            c.confusingIds().forEach(chunkIds::add);
            c.sourceDocuments().forEach(documents::add);
        });
        return new IdMapping(chunkIds, documents);
    }

    private void awaitVersionVisible(
            MilvusConsistentVectorStore vectorStore,
            int expectedCount,
            Duration timeout) throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        long visibleCount = -1L;
        while (Instant.now().isBefore(deadline)) {
            visibleCount = vectorStore.countByVersion(ACTIVE_VERSION_ID);
            if (visibleCount == expectedCount) return;
            Thread.sleep(250L);
        }
        throw new AssertionError("Milvus 可见 chunk 数未达到预期: expected="
                + expectedCount + ", actual=" + visibleCount);
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

    private String sha256(Path path) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        return StableHash.sha256(new String(bytes, StandardCharsets.UTF_8));
    }

    private double recallAt(List<Long> ranked, List<Long> relevant, int k) {
        long hits = ranked.stream().limit(k).filter(relevant::contains).count();
        return relevant.isEmpty() ? 0.0 : hits / (double) relevant.size();
    }

    private double reciprocalRank(List<Long> ranked, List<Long> relevant) {
        for (int index = 0; index < ranked.size(); index++) {
            if (relevant.contains(ranked.get(index))) return 1.0 / (index + 1.0);
        }
        return 0.0;
    }

    private double ndcgAt(List<Long> ranked, List<Long> relevant, int k) {
        if (relevant.isEmpty()) return 0.0;
        double dcg = 0.0;
        for (int index = 0; index < Math.min(k, ranked.size()); index++) {
            if (relevant.contains(ranked.get(index))) dcg += 1.0 / log2(index + 2.0);
        }
        double idcg = 0.0;
        for (int index = 0; index < Math.min(k, relevant.size()); index++) {
            idcg += 1.0 / log2(index + 2.0);
        }
        return idcg == 0.0 ? 0.0 : dcg / idcg;
    }

    private double firstRelevantRank(List<Long> ranked, List<Long> relevant) {
        for (int index = 0; index < ranked.size(); index++) {
            if (relevant.contains(ranked.get(index))) return index + 1.0;
        }
        return 0.0;
    }

    private double sourceCoverage(
            List<Long> rankedDocuments, List<Long> requiredDocuments, int k) {
        if (requiredDocuments.isEmpty()) return 0.0;
        long covered = rankedDocuments.stream().limit(k).distinct()
                .filter(requiredDocuments::contains).count();
        return covered / (double) requiredDocuments.size();
    }

    private double evidenceGroupCoverage(
            List<Long> rankedChunkIds, List<List<Long>> groups, int k) {
        if (groups.isEmpty()) return 0.0;
        List<Long> topK = rankedChunkIds.stream().limit(k).toList();
        long covered = groups.stream().filter(group ->
                group.stream().anyMatch(topK::contains)).count();
        return covered / (double) groups.size();
    }

    private double misleadingRecall(List<Long> ranked, List<Long> confusingIds) {
        if (confusingIds.isEmpty()) return 0.0;
        return ranked.stream().anyMatch(confusingIds::contains) ? 1.0 : 0.0;
    }

    private double log2(double value) {
        return Math.log(value) / Math.log(2.0);
    }

    private record CorpusRow(
            String chunkIdText, String sourceDocument, String title, String content) {
    }

    private record ObsidianEvalCase(
            String caseId,
            String question,
            String questionType,
            String difficulty,
            String split,
            boolean answerable,
            List<String> relevantChunkIds,
            List<String> acceptableChunkIds,
            List<List<String>> requiredEvidenceGroups,
            List<String> sourceDocuments,
            List<String> confusingIds) {
        List<Long> relevantIds(IdMapping mapping) {
            return relevantChunkIds.stream().map(mapping::chunkIdToLong).toList();
        }

        List<Long> acceptableIds(IdMapping mapping) {
            return acceptableChunkIds.stream().map(mapping::chunkIdToLong).toList();
        }

        List<Long> sourceDocumentIds(IdMapping mapping) {
            return sourceDocuments.stream().map(mapping::documentIdToLong).toList();
        }

        List<List<Long>> requiredEvidenceGroupIds(IdMapping mapping) {
            return requiredEvidenceGroups.stream()
                    .map(group -> group.stream().map(mapping::chunkIdToLong).toList())
                    .toList();
        }

        List<Long> confusingIds(IdMapping mapping) {
            return confusingIds.stream().map(mapping::chunkIdToLong).toList();
        }
    }

    /**
     * 字符串 chunk_id / source_document 到 long 序号的冻结映射。
     * 排序保证确定性；corpus 或 case 文件变化后指纹改变，防止跨版本误用旧序号。
     */
    private static final class IdMapping {
        private final Map<String, Long> chunkIds = new TreeMap<>();
        private final Map<String, Long> documentIds = new TreeMap<>();
        private final Map<Long, String> longToChunkId = new HashMap<>();
        private final Map<Long, String> longToDocumentId = new HashMap<>();

        private IdMapping(Set<String> chunkIdSet, Set<String> documentSet) {
            List<String> sortedChunks = chunkIdSet.stream().sorted().toList();
            List<String> sortedDocuments = documentSet.stream().sorted().toList();
            for (int index = 0; index < sortedChunks.size(); index++) {
                long id = index + 1L;
                chunkIds.put(sortedChunks.get(index), id);
                longToChunkId.put(id, sortedChunks.get(index));
            }
            for (int index = 0; index < sortedDocuments.size(); index++) {
                long id = index + 1L;
                documentIds.put(sortedDocuments.get(index), id);
                longToDocumentId.put(id, sortedDocuments.get(index));
            }
        }

        long chunkIdToLong(String value) {
            return chunkIds.getOrDefault(value, -1L);
        }

        long documentIdToLong(String value) {
            return documentIds.getOrDefault(value, -1L);
        }

        String longToChunkId(long id) {
            return longToChunkId.getOrDefault(id, "UNMAPPED:" + id);
        }

        String longToDocumentId(long id) {
            return longToDocumentId.getOrDefault(id, "UNMAPPED:" + id);
        }

        int documentCount() {
            return documentIds.size();
        }

        String chunkIdFingerprint() {
            return StableHash.sha256(String.join("\n", chunkIds.keySet()));
        }

        String documentIdFingerprint() {
            return StableHash.sha256(String.join("\n", documentIds.keySet()));
        }
    }

    private final class ObsidianEmbeddingGateway {
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

        private ObsidianEmbeddingGateway(
                ObjectMapper json, String baseUrl, String model,
                String apiKey, int expectedDimension, Path cachePath) throws IOException {
            this.json = json;
            this.endpoint = withoutTrailingSlash(baseUrl) + "/embeddings";
            this.model = model;
            this.apiKey = apiKey;
            this.expectedDimension = expectedDimension;
            this.cachePath = cachePath;
            this.http = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(30)).build();
            loadCache();
        }

        private Map<Long, List<Double>> embedCorpus(
                List<CorpusRow> corpus, IdMapping mapping, int batchSize)
                throws IOException, InterruptedException {
            Map<Long, List<Double>> result = new LinkedHashMap<>();
            List<CorpusRow> missing = new ArrayList<>();
            for (CorpusRow row : corpus) {
                String key = cacheKey(row.content());
                List<Double> vector = cache.get(key);
                if (vector == null) missing.add(row);
                else {
                    result.put(mapping.chunkIdToLong(row.chunkIdText()), vector);
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
                    result.put(mapping.chunkIdToLong(row.chunkIdText()),
                            vectors.get(index));
                    appendCache(cacheKey(row.content()), vectors.get(index));
                }
            }
            return Map.copyOf(result);
        }

        private List<Double> embedLiveQuery(String query) {
            try {
                return requestEmbeddings(List.of(query)).get(0);
            } catch (IOException e) {
                throw new IllegalStateException(
                        "Query Embedding 请求失败: " + e.getMessage(), e);
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
                throw new IllegalStateException(
                        "Replay Query Embedding 请求失败: " + e.getMessage(), e);
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
                if (response.statusCode() < 400) {
                    return parseResponse(response.body(), inputs.size());
                }
                if (response.statusCode() != 429
                        && response.statusCode() != 503
                        && response.statusCode() != 504) {
                    throw new IOException("Embedding 返回不可重试状态 "
                            + response.statusCode() + ": "
                            + abbreviate(response.body(), 300));
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
                    .POST(HttpRequest.BodyPublishers.ofString(
                            json.writeValueAsString(root)))
                    .build();
            long started = System.nanoTime();
            requestCount++;
            HttpResponse<String> response =
                    http.send(request, HttpResponse.BodyHandlers.ofString());
            providerLatencyMs += Duration.ofNanos(System.nanoTime() - started).toMillis();
            return response;
        }

        private List<List<Double>> parseResponse(
                String body, int expectedCount) throws IOException {
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
                    throw new IOException("Embedding 维度错误，expected="
                            + expectedDimension + ", actual=" + vectorNode.size());
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
            if (cachePath.getParent() != null) {
                Files.createDirectories(cachePath.getParent());
            }
            String line = json.writeValueAsString(Map.of(
                    "key", key, "embedding", vector)) + "\n";
            Files.writeString(cachePath, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }

        private String cacheKey(String content) {
            return StableHash.sha256(model + "\n" + content);
        }

        private String abbreviate(String value, int maxLength) {
            if (value == null) return "";
            return value.length() <= maxLength
                    ? value : value.substring(0, maxLength) + "...";
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
                    "cachePath", cachePath.toString());
        }
    }

    private static final class MetricBucket {
        private long count;
        private double strictRecallAt1;
        private double strictRecallAt3;
        private double strictRecallAt5;
        private double strictRecallAt10;
        private double acceptableRecallAt5;
        private double acceptableRecallAt10;
        private double strictMrr;
        private double acceptableMrr;
        private double strictNdcgAt10;
        private double acceptableNdcgAt10;
        private double firstRelevantRank;
        private double sourceCoverageAt10;
        private double evidenceGroupCoverageAt10;
        private double misleadingRecall;
        private double unanswerablePrecisionSum;
        private long unanswerableCount;
        private final List<Long> latencies = new ArrayList<>();

        private void add(ObsidianEvalCase evalCase,
                         Map<String, Object> metrics, long latencyMs) {
            count++;
            strictRecallAt1 += (double) metrics.get("strictRecallAt1");
            strictRecallAt3 += (double) metrics.get("strictRecallAt3");
            strictRecallAt5 += (double) metrics.get("strictRecallAt5");
            strictRecallAt10 += (double) metrics.get("strictRecallAt10");
            acceptableRecallAt5 += (double) metrics.get("acceptableRecallAt5");
            acceptableRecallAt10 += (double) metrics.get("acceptableRecallAt10");
            strictMrr += (double) metrics.get("strictMrr");
            acceptableMrr += (double) metrics.get("acceptableMrr");
            strictNdcgAt10 += (double) metrics.get("strictNdcgAt10");
            acceptableNdcgAt10 += (double) metrics.get("acceptableNdcgAt10");
            firstRelevantRank += (double) metrics.get("firstRelevantRank");
            sourceCoverageAt10 += (double) metrics.get("sourceCoverageAt10");
            evidenceGroupCoverageAt10 +=
                    (double) metrics.get("evidenceGroupCoverageAt10");
            misleadingRecall += (double) metrics.get("misleadingRecall");
            double unanswerablePrecision =
                    (double) metrics.get("unanswerablePrecision");
            if (unanswerablePrecision >= 0.0) {
                unanswerablePrecisionSum += unanswerablePrecision;
                unanswerableCount++;
            }
            latencies.add(latencyMs);
        }

        private Map<String, Object> report() {
            List<Long> sorted = latencies.stream().sorted().toList();
            return Map.ofEntries(
                    Map.entry("caseCount", count),
                    Map.entry("strictRecallAt1",
                            count == 0 ? 0.0 : strictRecallAt1 / count),
                    Map.entry("strictRecallAt3",
                            count == 0 ? 0.0 : strictRecallAt3 / count),
                    Map.entry("strictRecallAt5",
                            count == 0 ? 0.0 : strictRecallAt5 / count),
                    Map.entry("strictRecallAt10",
                            count == 0 ? 0.0 : strictRecallAt10 / count),
                    Map.entry("acceptableRecallAt5",
                            count == 0 ? 0.0 : acceptableRecallAt5 / count),
                    Map.entry("acceptableRecallAt10",
                            count == 0 ? 0.0 : acceptableRecallAt10 / count),
                    Map.entry("strictMrr",
                            count == 0 ? 0.0 : strictMrr / count),
                    Map.entry("acceptableMrr",
                            count == 0 ? 0.0 : acceptableMrr / count),
                    Map.entry("strictNdcgAt10",
                            count == 0 ? 0.0 : strictNdcgAt10 / count),
                    Map.entry("acceptableNdcgAt10",
                            count == 0 ? 0.0 : acceptableNdcgAt10 / count),
                    Map.entry("firstRelevantRank",
                            count == 0 ? 0.0 : firstRelevantRank / count),
                    Map.entry("sourceCoverageAt10",
                            count == 0 ? 0.0 : sourceCoverageAt10 / count),
                    Map.entry("evidenceGroupCoverageAt10",
                            count == 0 ? 0.0 : evidenceGroupCoverageAt10 / count),
                    Map.entry("misleadingRecall",
                            count == 0 ? 0.0 : misleadingRecall / count),
                    Map.entry("unanswerableCaseCount", unanswerableCount),
                    Map.entry("unanswerablePrecision",
                            unanswerableCount == 0
                                    ? -1.0
                                    : unanswerablePrecisionSum / unanswerableCount),
                    Map.entry("endToEndLatencyP50Ms", percentile(sorted, 0.50)),
                    Map.entry("endToEndLatencyP95Ms", percentile(sorted, 0.95)));
        }

        private long percentile(List<Long> sorted, double percentile) {
            if (sorted.isEmpty()) return 0L;
            int index = (int) Math.ceil(percentile * sorted.size()) - 1;
            return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
        }
    }

    /** 本地评测只允许按 ID 回表，禁止测试悄悄退回 course 级全量扫描。 */
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
            throw new UnsupportedOperationException("本地评测禁止 course 级兜底");
        }

        @Override
        public void deleteByDocumentId(long documentId) {
            throw new UnsupportedOperationException("本地评测语料只读");
        }

        @Override
        public void deleteByCourseId(long courseId) {
            throw new UnsupportedOperationException("本地评测语料只读");
        }

        @Override
        public void updateVectorStatus(
                Long chunkId, String milvusVectorId, String embeddingStatus) {
            throw new UnsupportedOperationException("本地评测语料只读");
        }
    }
}
