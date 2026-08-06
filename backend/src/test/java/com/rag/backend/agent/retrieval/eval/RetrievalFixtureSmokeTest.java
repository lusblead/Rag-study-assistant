package com.rag.backend.agent.retrieval.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 无密钥公开合成 Fixture 的检索 Smoke。
 *
 * 与真实评测（Public T2 / Local Obsidian）的区别：
 * 1. 数据全部来自 src/test/resources/eval-fixtures/retrieval-smoke 的虚构技术文档；
 * 2. 向量为固定种子预生成的 64 维确定性向量，不调用外部 Embedding API；
 * 3. 不读取 MySQL 模型配置、不需要 API Key；
 * 4. 用于验证评测代码、临时 Milvus FLAT、指标与报告链路可运行，不代表真实检索质量。
 *
 * 链路复用生产组件：MilvusConsistentVectorStore（FLAT + flush/count 可见性屏障）、
 * MilvusKnowledgeRetriever、LocalLexicalKnowledgeReranker(0.7,0.3)，candidateK=20、topK=10。
 */
@EnabledIfSystemProperty(named = "rag.fixture.smoke", matches = "true")
class RetrievalFixtureSmokeTest {
    private static final long COURSE_ID = 20_260_806L;
    private static final long ACTIVE_VERSION_ID = 1L;
    private static final int CANDIDATE_K = 20;
    private static final int TOP_K = 10;
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void runFixtureSmokeDeterministically() throws Exception {
        // fixture 随 test-classes 打包，从 classpath 读取，避免依赖 Maven 工作目录。
        Path fixtureDir = extractFixtureToTemp();
        JsonNode manifest = EvalFixtureSupport.validateFixtureManifest(fixtureDir);
        int dimension = manifest.path("embeddingDimension").asInt();
        int expectedChunks = manifest.path("expectedChunkCount").asInt();
        int expectedCases = manifest.path("expectedCaseCount").asInt();

        List<CorpusRow> corpus = readCorpus(fixtureDir.resolve("corpus.jsonl"));
        List<SmokeCase> cases = readCases(fixtureDir.resolve("cases.jsonl"));
        Map<String, List<Double>> embeddings = readEmbeddings(fixtureDir.resolve("embeddings.jsonl"));
        assertFalse(corpus.isEmpty(), "Smoke corpus 不能为空");
        assertFalse(cases.isEmpty(), "Smoke cases 不能为空");
        assertEquals(expectedChunks, corpus.size(), "manifest 与 corpus 数量不一致");
        assertEquals(expectedCases, cases.size(), "manifest 与 cases 数量不一致");

        FixtureChunkRepository chunks = new FixtureChunkRepository();
        MilvusConsistentVectorStore vectorStore = new MilvusConsistentVectorStore(
                System.getProperty("rag.eval.milvusHost", "127.0.0.1"),
                Integer.parseInt(System.getProperty("rag.eval.milvusPort", "39530")),
                System.getProperty(
                        "rag.eval.milvusCollection", "rag_fixture_smoke_v1"),
                dimension,
                System.getProperty("rag.eval.milvusIndexType", "FLAT"));

        for (CorpusRow row : corpus) {
            List<Double> vector = embeddings.get("chunk:" + row.chunkId());
            if (vector == null || vector.size() != dimension) {
                throw new IllegalStateException("Smoke corpus 缺少合法向量: chunk=" + row.chunkId());
            }
            KnowledgeChunk chunk = new KnowledgeChunk();
            chunk.setId(row.chunkId());
            chunk.setCourseId(COURSE_ID);
            chunk.setDocumentId(row.documentId());
            chunk.setDocumentVersionId(ACTIVE_VERSION_ID);
            chunk.setChunkIndex(0);
            chunk.setTitle(row.title());
            chunk.setContent(row.content());
            chunk.setSourcePage(1);
            chunks.save(chunk);
            vectorStore.upsert(new ConsistentVectorStore.VectorRecord(
                    row.chunkId(),
                    row.chunkId(),
                    COURSE_ID,
                    row.documentId(),
                    ACTIVE_VERSION_ID,
                    "fixture:" + row.chunkId(),
                    StableHash.sha256(row.content()),
                    "fixture-deterministic",
                    dimension,
                    vector));
        }

        vectorStore.flush(Duration.ofSeconds(60).toMillis());
        awaitVersionVisible(vectorStore, expectedChunks, Duration.ofSeconds(60));

        FixtureQueryEmbeddingClient queryEmbedding =
                new FixtureQueryEmbeddingClient(embeddings);
        MilvusKnowledgeRetriever retriever = new MilvusKnowledgeRetriever(
                queryEmbedding,
                ignored -> Set.of(ACTIVE_VERSION_ID),
                vectorStore,
                chunks,
                null,
                new LocalLexicalKnowledgeReranker(0.7, 0.3),
                -1.0,
                CANDIDATE_K);

        try {
            // 同一 Milvus 快照下连跑两轮，验证返回列表与总体指标逐值一致。
            Round first = runRound(cases, retriever);
            Round second = runRound(cases, retriever);
            assertEquals(first.rankedByCase(), second.rankedByCase(),
                    "Smoke 两轮返回列表不一致");
            // 延迟受机器调度影响，不参与确定性一致性比较；质量指标必须逐值一致。
            assertEquals(withoutLatency(first.overall()), withoutLatency(second.overall()),
                    "Smoke 两轮总体质量指标不一致");

            Map<String, Object> report = new LinkedHashMap<>();
            report.put("evaluationType", "FIXTURE_SMOKE");
            report.put("evidenceBoundary",
                    "自行编写的虚构技术文档 + 确定性预生成向量；仅验证链路可运行，不代表真实检索质量");
            report.put("dataset", Map.ofEntries(
                    Map.entry("fixtureId", manifest.path("fixtureId").asText()),
                    Map.entry("schemaVersion", manifest.path("schemaVersion").asInt()),
                    Map.entry("corpusSha256", manifest.path("corpusSha256").asText()),
                    Map.entry("caseSha256", manifest.path("caseSha256").asText()),
                    Map.entry("embeddingDimension", dimension),
                    Map.entry("expectedChunkCount", expectedChunks),
                    Map.entry("expectedCaseCount", expectedCases)));
            report.put("executedAt", Instant.now().toString());
            report.put("codeRevision", System.getProperty(
                    "rag.eval.codeRevision", "UNRECORDED"));
            report.put("worktreeFingerprint", System.getProperty(
                    "rag.eval.worktreeFingerprint", "UNRECORDED"));
            report.put("configuration", Map.ofEntries(
                    Map.entry("vectorStore", "MilvusConsistentVectorStore/2.4.11"),
                    Map.entry("vectorIndexType",
                            System.getProperty("rag.eval.milvusIndexType", "FLAT")),
                    Map.entry("vectorVisibilityBarrier", "flush-and-count"),
                    Map.entry("reranker", "LocalLexicalKnowledgeReranker(0.7,0.3)"),
                    Map.entry("candidateK", CANDIDATE_K),
                    Map.entry("topK", TOP_K),
                    Map.entry("embeddingModel", "fixture-deterministic"),
                    Map.entry("externalApiCalls", 0)));
            report.put("providerUsage", Map.of(
                    "requestCount", 0L,
                    "retryCount", 0L,
                    "totalTokens", 0L));
            report.put("overall", first.overall());
            report.put("cases", first.caseReports());

            Path output = Path.of(System.getProperty(
                    "rag.eval.output", "target/rag-eval/fixture-smoke.json"));
            if (output.getParent() != null) {
                Files.createDirectories(output.getParent());
            }
        String serialized = json.writerWithDefaultPrettyPrinter()
                .writeValueAsString(report);
        EvalFixtureSupport.assertReportContainsNoApiKey(serialized);
        Files.writeString(output, serialized, StandardCharsets.UTF_8);
        } finally {
            vectorStore.deleteByVersion(ACTIVE_VERSION_ID);
            // 清理临时 fixture 副本，避免测试在用户目录留残留。
            if (fixtureDir != null && fixtureDir.getParent() != null
                    && fixtureDir.getParent().toString().contains("rag-fixture-smoke")) {
                deleteRecursively(fixtureDir);
            }
        }
    }

    private Path extractFixtureToTemp() throws IOException {
        Path target = Files.createTempDirectory("rag-fixture-smoke");
        String[] files = {"manifest.json", "corpus.jsonl", "cases.jsonl", "embeddings.jsonl"};
        for (String name : files) {
            try (InputStream in = RetrievalFixtureSmokeTest.class
                    .getResourceAsStream("/eval-fixtures/retrieval-smoke/" + name)) {
                if (in == null) {
                    throw new IllegalStateException(
                            "classpath 缺少 fixture 文件: eval-fixtures/retrieval-smoke/" + name);
                }
                Files.copy(in, target.resolve(name));
            }
        }
        return target;
    }

    private void deleteRecursively(Path root) throws IOException {
        if (!Files.isDirectory(root)) {
            Files.deleteIfExists(root);
            return;
        }
        try (var walk = Files.walk(root)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    throw new IllegalStateException("清理临时 fixture 失败: " + path, e);
                }
            });
        }
    }

    private Round runRound(List<SmokeCase> cases, MilvusKnowledgeRetriever retriever) {
        List<Map<String, Object>> caseReports = new ArrayList<>();
        MetricBucket totals = new MetricBucket();
        Map<String, List<Long>> rankedByCase = new LinkedHashMap<>();
        for (SmokeCase evalCase : cases) {
            long started = System.nanoTime();
            List<RetrievedChunk> rankedChunks =
                    retriever.retrieve(COURSE_ID, evalCase.query(), TOP_K);
            long latencyMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
            List<Long> rankedChunkIds = rankedChunks.stream()
                    .map(RetrievedChunk::chunkId).toList();
            rankedByCase.put(evalCase.caseId(), rankedChunkIds);

            Map<String, Object> metrics = metrics(
                    rankedChunkIds, evalCase);
            totals.add(evalCase, metrics, latencyMs);
            Map<String, Object> caseReport = new LinkedHashMap<>();
            caseReport.put("caseId", evalCase.caseId());
            caseReport.put("question", evalCase.query());
            caseReport.put("questionType", evalCase.questionType());
            caseReport.put("answerable", evalCase.answerable());
            caseReport.put("relevantChunkIds", evalCase.relevantChunkIds());
            caseReport.put("acceptableChunkIds", evalCase.acceptableChunkIds());
            caseReport.put("sourceDocuments", evalCase.sourceDocuments());
            caseReport.put("returnedChunkIds", rankedChunkIds);
            caseReport.put("metrics", metrics);
            caseReport.put("endToEndLatencyMs", latencyMs);
            caseReports.add(caseReport);
        }
        return new Round(rankedByCase, totals.report(), caseReports);
    }

    private Map<String, Object> withoutLatency(Map<String, Object> overall) {
        Map<String, Object> result = new LinkedHashMap<>(overall);
        result.remove("endToEndLatencyP50Ms");
        result.remove("endToEndLatencyP95Ms");
        return Map.copyOf(result);
    }

    private Map<String, Object> metrics(List<Long> ranked, SmokeCase evalCase) {
        List<Long> relevant = evalCase.relevantChunkIds();
        List<Long> acceptable = evalCase.acceptableChunkIds();
        List<Long> confusing = evalCase.confusingChunkIds();
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("strictRecallAt1", recallAt(ranked, relevant, 1));
        metrics.put("strictRecallAt3", recallAt(ranked, relevant, 3));
        metrics.put("strictRecallAt5", recallAt(ranked, relevant, 5));
        metrics.put("strictRecallAt10", recallAt(ranked, relevant, 10));
        metrics.put("acceptableRecallAt5", recallAt(ranked, acceptable, 5));
        metrics.put("acceptableRecallAt10", recallAt(ranked, acceptable, 10));
        metrics.put("strictMrr", reciprocalRank(ranked, relevant));
        metrics.put("acceptableMrr", reciprocalRank(ranked, acceptable));
        metrics.put("strictNdcgAt10", ndcgAt(ranked, relevant, 10));
        metrics.put("acceptableNdcgAt10", ndcgAt(ranked, acceptable, 10));
        metrics.put("firstRelevantRank", firstRelevantRank(ranked, acceptable));
        metrics.put("sourceCoverageAt10", sourceCoverage(
                ranked, evalCase.sourceDocuments(), 10));
        metrics.put("evidenceGroupCoverageAt10", evidenceGroupCoverage(
                ranked, evalCase.relevantChunkIds(), 10));
        metrics.put("misleadingRecall", misleadingRecall(ranked, confusing));
        metrics.put("returnedAnyChunk", !ranked.isEmpty());
        metrics.put("unanswerablePrecision", evalCase.answerable()
                ? -1.0
                : (ranked.isEmpty() ? 1.0 : 0.0));
        return metrics;
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
                    node.path("content").asText()));
        }
        return List.copyOf(rows);
    }

    private List<SmokeCase> readCases(Path path) throws IOException {
        List<SmokeCase> rows = new ArrayList<>();
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            if (line.isBlank()) continue;
            JsonNode node = json.readTree(line);
            rows.add(new SmokeCase(
                    node.path("caseId").asText(),
                    node.path("query").asText(),
                    node.path("questionType").asText(),
                    node.path("answerable").asBoolean(true),
                    longList(node.path("relevantChunkIds")),
                    longList(node.path("acceptableChunkIds")),
                    textList(node.path("sourceDocuments")),
                    longList(node.path("confusingChunkIds"))));
        }
        return List.copyOf(rows);
    }

    private Map<String, List<Double>> readEmbeddings(Path path) throws IOException {
        Map<String, List<Double>> result = new HashMap<>();
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            if (line.isBlank()) continue;
            JsonNode node = json.readTree(line);
            List<Double> vector = new ArrayList<>();
            node.path("vector").forEach(value -> vector.add(value.asDouble()));
            result.put(node.path("key").asText(), List.copyOf(vector));
        }
        return Map.copyOf(result);
    }

    private List<Long> longList(JsonNode values) {
        List<Long> result = new ArrayList<>();
        values.forEach(value -> result.add(value.asLong()));
        return List.copyOf(result);
    }

    private List<String> textList(JsonNode values) {
        List<String> result = new ArrayList<>();
        values.forEach(value -> result.add(value.asText()));
        return List.copyOf(result);
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
            List<Long> rankedChunkIds,
            List<String> requiredDocuments,
            int k) {
        if (requiredDocuments.isEmpty()) return 0.0;
        // fixture 中 documentId 与 sourceDocument 一一对应；此处用 chunk 的 document 归属判断。
        List<Long> topK = rankedChunkIds.stream().limit(k).toList();
        Set<Long> covered = new LinkedHashSet<>();
        for (long chunkId : topK) {
            if (chunkId >= 1 && chunkId <= 4) covered.add(101L);
            else if (chunkId >= 5 && chunkId <= 8) covered.add(102L);
            else if (chunkId >= 9 && chunkId <= 12) covered.add(103L);
        }
        Set<Long> required = new LinkedHashSet<>();
        requiredDocuments.forEach(doc -> required.add(documentIdOf(doc)));
        if (required.isEmpty()) return 0.0;
        long hit = covered.stream().filter(required::contains).count();
        return hit / (double) required.size();
    }

    private double evidenceGroupCoverage(
            List<Long> rankedChunkIds, List<Long> relevant, int k) {
        if (relevant.isEmpty()) return 0.0;
        List<Long> topK = rankedChunkIds.stream().limit(k).toList();
        long hit = relevant.stream().filter(topK::contains).count();
        return hit / (double) relevant.size();
    }

    private double misleadingRecall(List<Long> ranked, List<Long> confusing) {
        if (confusing.isEmpty()) return 0.0;
        return ranked.stream().anyMatch(confusing::contains) ? 1.0 : 0.0;
    }

    private long documentIdOf(String sourceDocument) {
        return switch (sourceDocument) {
            case "虚构网关部署手册.md" -> 101L;
            case "虚构任务队列手册.md" -> 102L;
            case "虚构缓存手册.md" -> 103L;
            default -> -1L;
        };
    }

    private double log2(double value) {
        return Math.log(value) / Math.log(2.0);
    }

    private record CorpusRow(long chunkId, long documentId, String title, String content) {
    }

    private record SmokeCase(
            String caseId,
            String query,
            String questionType,
            boolean answerable,
            List<Long> relevantChunkIds,
            List<Long> acceptableChunkIds,
            List<String> sourceDocuments,
            List<Long> confusingChunkIds) {
    }

    private record Round(
            Map<String, List<Long>> rankedByCase,
            Map<String, Object> overall,
            List<Map<String, Object>> caseReports) {
    }

    /** Fixture 的 query 向量来自预生成文件，不调用任何外部服务。 */
    private static final class FixtureQueryEmbeddingClient implements EmbeddingClient {
        private final Map<String, List<Double>> embeddings;

        private FixtureQueryEmbeddingClient(Map<String, List<Double>> embeddings) {
            this.embeddings = embeddings;
        }

        @Override
        public List<Double> embed(String text) {
            List<Double> vector = embeddings.get("querytext:" + text);
            if (vector == null) {
                throw new IllegalStateException("Fixture 缺少 query 向量: " + text);
            }
            return vector;
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

        private void add(SmokeCase evalCase, Map<String, Object> metrics, long latencyMs) {
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

    /** Fixture 评测只允许按 ID 回表，禁止退回 course 级全量扫描。 */
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
            throw new UnsupportedOperationException("Fixture 评测禁止 course 级兜底");
        }

        @Override
        public void deleteByDocumentId(long documentId) {
            throw new UnsupportedOperationException("Fixture 评测语料只读");
        }

        @Override
        public void deleteByCourseId(long courseId) {
            throw new UnsupportedOperationException("Fixture 评测语料只读");
        }

        @Override
        public void updateVectorStatus(
                Long chunkId, String milvusVectorId, String embeddingStatus) {
            throw new UnsupportedOperationException("Fixture 评测语料只读");
        }
    }
}
