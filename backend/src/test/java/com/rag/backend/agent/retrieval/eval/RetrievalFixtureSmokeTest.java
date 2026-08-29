package com.rag.backend.agent.retrieval.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
        RetrievalMetricsCalculator metricsCore = new RetrievalMetricsCalculator();
        Map<Long, Long> sourceByChunkId = corpus.stream().collect(
                java.util.stream.Collectors.toUnmodifiableMap(
                        CorpusRow::chunkId, CorpusRow::documentId));

        try {
            // 同一 Milvus 快照下连跑两轮，验证返回列表与总体指标逐值一致。
            Round first = runRound(cases, retriever, metricsCore, sourceByChunkId);
            Round second = runRound(cases, retriever, metricsCore, sourceByChunkId);
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

    private Round runRound(List<SmokeCase> cases,
                           MilvusKnowledgeRetriever retriever,
                           RetrievalMetricsCalculator metricsCore,
                           Map<Long, Long> sourceByChunkId) {
        List<Map<String, Object>> caseReports = new ArrayList<>();
        MetricBucket totals = new MetricBucket(metricsCore);
        Map<String, List<Long>> rankedByCase = new LinkedHashMap<>();
        for (SmokeCase evalCase : cases) {
            long started = System.nanoTime();
            List<RetrievedChunk> rankedChunks =
                    retriever.retrieve(COURSE_ID, evalCase.query(), TOP_K);
            long latencyMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
            List<Long> rankedChunkIds = rankedChunks.stream()
                    .map(RetrievedChunk::chunkId).toList();
            rankedByCase.put(evalCase.caseId(), rankedChunkIds);

            RetrievalGroundTruth groundTruth = groundTruth(
                    evalCase, sourceByChunkId);
            Map<Integer, CaseRetrievalMetrics> canonicalMetrics = Map.of(
                    1, metricsCore.evaluateCase(groundTruth, rankedChunkIds, 1),
                    3, metricsCore.evaluateCase(groundTruth, rankedChunkIds, 3),
                    5, metricsCore.evaluateCase(groundTruth, rankedChunkIds, 5),
                    10, metricsCore.evaluateCase(groundTruth, rankedChunkIds, 10));
            Map<String, Object> metrics = metrics(evalCase, canonicalMetrics);
            totals.add(groundTruth, canonicalMetrics, metrics, latencyMs);
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

    private Map<String, Object> metrics(
            SmokeCase evalCase,
            Map<Integer, CaseRetrievalMetrics> canonicalMetrics) {
        List<Long> confusing = evalCase.confusingChunkIds();
        CaseRetrievalMetrics at10 = canonicalMetrics.get(10);
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("strictRecallAt1", canonicalMetrics.get(1).recallAtK());
        metrics.put("strictRecallAt3", canonicalMetrics.get(3).recallAtK());
        metrics.put("strictRecallAt5", canonicalMetrics.get(5).recallAtK());
        metrics.put("strictRecallAt10", at10.recallAtK());
        metrics.put("acceptableRecallAt5",
                canonicalMetrics.get(5).acceptableRecallAtK());
        metrics.put("acceptableRecallAt10", at10.acceptableRecallAtK());
        metrics.put("strictMrr", at10.reciprocalRank());
        metrics.put("acceptableMrr", at10.acceptableReciprocalRank());
        metrics.put("strictNdcgAt10", at10.ndcgAtK());
        metrics.put("acceptableNdcgAt10", at10.acceptableNdcgAtK());
        metrics.put("firstRelevantRank", firstRelevantRank(
                at10.rankedChunkIds(), evalCase.acceptableChunkIds()));
        metrics.put("sourceCoverageAt10", at10.sourceCoverageAtK());
        metrics.put("evidenceGroupCoverageAt10",
                at10.requiredEvidenceGroupCoverageAtK());
        metrics.put("misleadingRecall", misleadingRecall(
                at10.rankedChunkIds(), confusing));
        metrics.put("returnedAnyChunk", !at10.rankedChunkIds().isEmpty());
        metrics.put("unanswerablePrecision", evalCase.answerable()
                ? -1.0
                : (at10.unanswerableFalsePositive() > 0.0 ? 0.0 : 1.0));
        return metrics;
    }

    private RetrievalGroundTruth groundTruth(
            SmokeCase evalCase,
            Map<Long, Long> sourceByChunkId) {
        Set<Long> relevant = Set.copyOf(evalCase.relevantChunkIds());
        Set<Long> acceptable = Set.copyOf(evalCase.acceptableChunkIds());
        List<Set<Long>> requiredGroups = relevant.stream()
                .map(Set::of)
                .toList();
        Set<Long> requiredSources = evalCase.sourceDocuments().stream()
                .map(this::documentIdOf)
                .filter(id -> id >= 0)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        return new RetrievalGroundTruth(
                evalCase.caseId(),
                evalCase.answerable()
                        ? Answerability.ANSWERABLE
                        : Answerability.UNANSWERABLE,
                relevant,
                acceptable,
                requiredGroups,
                sourceByChunkId,
                requiredSources);
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

    private double firstRelevantRank(List<Long> ranked, List<Long> relevant) {
        for (int index = 0; index < ranked.size(); index++) {
            if (relevant.contains(ranked.get(index))) return index + 1.0;
        }
        return 0.0;
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
        private final RetrievalMetricsCalculator metricsCore;
        private final RetrievalMetricsAccumulator canonical =
                new RetrievalMetricsAccumulator();
        private double firstRelevantRank;
        private double misleadingRecall;
        private final List<Long> latencies = new ArrayList<>();

        private MetricBucket(RetrievalMetricsCalculator metricsCore) {
            this.metricsCore = metricsCore;
        }

        private void add(RetrievalGroundTruth groundTruth,
                         Map<Integer, CaseRetrievalMetrics> canonicalMetrics,
                         Map<String, Object> metrics,
                         long latencyMs) {
            canonical.add(groundTruth, canonicalMetrics);
            firstRelevantRank += (double) metrics.get("firstRelevantRank");
            misleadingRecall += (double) metrics.get("misleadingRecall");
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
            long count = canonical.size();
            List<Long> sorted = latencies.stream().sorted().toList();
            return Map.ofEntries(
                    Map.entry("caseCount", count),
                    Map.entry("strictRecallAt1", at1.macroRecallAtK()),
                    Map.entry("strictRecallAt3", at3.macroRecallAtK()),
                    Map.entry("strictRecallAt5", at5.macroRecallAtK()),
                    Map.entry("strictRecallAt10", at10.macroRecallAtK()),
                    Map.entry("acceptableRecallAt5", at5.macroAcceptableRecallAtK()),
                    Map.entry("acceptableRecallAt10", at10.macroAcceptableRecallAtK()),
                    Map.entry("strictMrr", at10.meanReciprocalRank()),
                    Map.entry("acceptableMrr", at10.meanAcceptableReciprocalRank()),
                    Map.entry("strictNdcgAt10", at10.macroNdcgAtK()),
                    Map.entry("acceptableNdcgAt10", at10.macroAcceptableNdcgAtK()),
                    Map.entry("firstRelevantRank",
                            count == 0 ? 0.0 : firstRelevantRank / count),
                    Map.entry("sourceCoverageAt10", at10.macroSourceCoverageAtK()),
                    Map.entry("evidenceGroupCoverageAt10",
                            at10.macroRequiredEvidenceGroupCoverageAtK()),
                    Map.entry("misleadingRecall",
                            count == 0 ? 0.0 : misleadingRecall / count),
                    Map.entry("unanswerableCaseCount", at10.unanswerableCases()),
                    Map.entry("unanswerablePrecision",
                            at10.unanswerableCases() == 0
                                    ? -1.0 : at10.emptyRetrievalAccuracy()),
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
