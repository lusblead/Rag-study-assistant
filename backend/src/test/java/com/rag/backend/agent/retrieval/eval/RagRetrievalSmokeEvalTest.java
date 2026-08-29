package com.rag.backend.agent.retrieval.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.backend.agent.evaluation.Answerability;
import com.rag.backend.agent.evaluation.CaseRetrievalMetrics;
import com.rag.backend.agent.evaluation.RetrievalEvalReport;
import com.rag.backend.agent.evaluation.RetrievalGroundTruth;
import com.rag.backend.agent.evaluation.RetrievalMetricsAccumulator;
import com.rag.backend.agent.evaluation.RetrievalMetricsCalculator;
import com.rag.backend.agent.embedding.MockEmbeddingClient;
import com.rag.backend.agent.model.KnowledgeChunk;
import com.rag.backend.agent.repository.KnowledgeChunkRepository;
import com.rag.backend.agent.rerank.LocalLexicalKnowledgeReranker;
import com.rag.backend.agent.retrieval.MilvusKnowledgeRetriever;
import com.rag.backend.agent.retrieval.RetrievedChunk;
import com.rag.backend.ingestionlab.identity.StableHash;
import com.rag.backend.ingestionlab.vector.ConsistentVectorStore;
import com.rag.backend.ingestionlab.vector.InMemoryConsistentVectorStore;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 使用当前版本门禁、Mock Embedding、本地向量端口和本地 Rerank 跑一套可复现 smoke。
 * 该结果用于冻结“测试接线基线”，不能冒充真实 BGE-M3 + Milvus 的效果指标。
 */
class RagRetrievalSmokeEvalTest {
    private static final long COURSE_ID = 10L;
    private static final int DIMENSION = 64;
    private static final int CANDIDATE_K = 10;
    private static final int TOP_K = 5;
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void runVersionSafeOfflineSmokeAndWriteReport() throws Exception {
        Path explicitProbe = Path.of("explicit", "report.json");
        assertEquals(explicitProbe,
                resolveReportOutput(explicitProbe.toString(), null),
                "显式输出必须优先于模块默认目录");
        Path moduleBuildDirectoryProbe =
                Path.of("module-build-probe").toAbsolutePath().normalize();
        assertEquals(
                moduleBuildDirectoryProbe.resolve("rag-eval")
                        .resolve("latest.json").normalize(),
                resolveReportOutput(
                        null, moduleBuildDirectoryProbe.toString()),
                "未显式指定输出时必须使用模块构建目录");
        assertThrows(IllegalStateException.class,
                () -> resolveReportOutput(null, null),
                "缺少显式输出和模块构建目录时必须 fail closed");

        Path output = resolveReportOutput(
                System.getProperty("rag.eval.output"),
                System.getProperty("rag.eval.buildDirectory"));
        Path datasetRoot = Path.of(System.getProperty(
                "rag.eval.datasetDir",
                defaultDatasetRoot().toString()));
        List<CorpusRow> corpus = readCorpus(datasetRoot.resolve("corpus.jsonl"));
        List<EvalCase> cases = readCases(datasetRoot.resolve("cases.jsonl"));
        assertFalse(corpus.isEmpty(), "评测语料不能为空");
        assertFalse(cases.isEmpty(), "评测问题不能为空");

        MockEmbeddingClient embedding = new MockEmbeddingClient(DIMENSION);
        InMemoryConsistentVectorStore vectorStore =
                new InMemoryConsistentVectorStore();
        FixtureChunkRepository chunks = new FixtureChunkRepository();
        Set<Long> activeVersionIds = corpus.stream()
                .filter(CorpusRow::active)
                .map(CorpusRow::documentVersionId)
                .collect(Collectors.toUnmodifiableSet());

        for (CorpusRow row : corpus) {
            KnowledgeChunk chunk = toChunk(row);
            chunks.save(chunk);
            vectorStore.upsert(new ConsistentVectorStore.VectorRecord(
                    row.chunkId(), row.chunkId(), COURSE_ID,
                    row.documentId(), row.documentVersionId(),
                    "eval-" + row.chunkId(), StableHash.sha256(row.content()),
                    "mock-hash", DIMENSION, embedding.embed(row.content())));
        }

        // 使用生产构造器，确保 active-version 门禁和回表二次校验都开启。
        MilvusKnowledgeRetriever retriever = new MilvusKnowledgeRetriever(
                embedding,
                ignored -> activeVersionIds,
                vectorStore,
                chunks,
                null,
                new LocalLexicalKnowledgeReranker(0.7, 0.3),
                -1.0,
                CANDIDATE_K);

        Map<Long, CorpusRow> corpusById = corpus.stream().collect(
                Collectors.toMap(CorpusRow::chunkId, value -> value));
        List<Map<String, Object>> caseReports = new ArrayList<>();
        RetrievalMetricsCalculator metricsCore = new RetrievalMetricsCalculator();
        RetrievalMetricsAccumulator canonical = new RetrievalMetricsAccumulator();
        int staleLeakCount = 0;

        for (EvalCase evalCase : cases) {
            long started = System.nanoTime();
            List<RetrievedChunk> ranked = retriever.retrieve(
                    COURSE_ID, evalCase.query(), TOP_K);
            long latencyMs = (System.nanoTime() - started) / 1_000_000L;
            List<Long> ids = ranked.stream()
                    .map(RetrievedChunk::chunkId)
                    .toList();

            int caseStaleLeaks = (int) ids.stream()
                    .map(corpusById::get)
                    .filter(row -> row != null && !row.active())
                    .count();
            staleLeakCount += caseStaleLeaks;

            RetrievalGroundTruth groundTruth = groundTruth(evalCase);
            Map<Integer, CaseRetrievalMetrics> canonicalMetrics = Map.of(
                    1, metricsCore.evaluateCase(groundTruth, ids, 1),
                    3, metricsCore.evaluateCase(groundTruth, ids, 3),
                    5, metricsCore.evaluateCase(groundTruth, ids, 5));
            canonical.add(groundTruth, canonicalMetrics);
            CaseRetrievalMetrics at5 = canonicalMetrics.get(5);
            Map<String, Object> metrics = new LinkedHashMap<>();
            if (evalCase.answerable()) {
                metrics.put("recallAt1", canonicalMetrics.get(1).recallAtK());
                metrics.put("recallAt3", canonicalMetrics.get(3).recallAtK());
                metrics.put("recallAt5", at5.recallAtK());
                metrics.put("reciprocalRank", at5.reciprocalRank());
                metrics.put("ndcgAt5", at5.ndcgAtK());
            } else {
                metrics.put("returnedAnyChunk", at5.unanswerableFalsePositive() > 0.0);
            }
            metrics.put("staleVersionLeakCount", caseStaleLeaks);

            Map<String, Object> caseReport = new LinkedHashMap<>();
            caseReport.put("caseId", evalCase.caseId());
            caseReport.put("query", evalCase.query());
            caseReport.put("answerability", evalCase.answerability());
            caseReport.put("tags", evalCase.tags());
            caseReport.put("relevantChunkIds", evalCase.relevantChunkIds());
            caseReport.put("returned", ranked.stream().map(value -> Map.of(
                    "chunkId", value.chunkId(),
                    "score", value.score(),
                    "title", value.title())).toList());
            caseReport.put("metrics", metrics);
            caseReport.put("latencyMs", latencyMs);
            caseReports.add(caseReport);
        }

        RetrievalEvalReport at1 = metricsCore.summarizeGroundTruth(
                canonical.groundTruths(), canonical.resultsAt(1), 1);
        RetrievalEvalReport at3 = metricsCore.summarizeGroundTruth(
                canonical.groundTruths(), canonical.resultsAt(3), 3);
        RetrievalEvalReport at5 = metricsCore.summarizeGroundTruth(
                canonical.groundTruths(), canonical.resultsAt(5), 5);
        Map<String, Object> overall = new LinkedHashMap<>();
        overall.put("caseCount", cases.size());
        overall.put("answerableCaseCount", at5.answerableCases());
        overall.put("unanswerableCaseCount", at5.unanswerableCases());
        overall.put("recallAt1", at1.macroRecallAtK());
        overall.put("recallAt3", at3.macroRecallAtK());
        overall.put("recallAt5", at5.macroRecallAtK());
        overall.put("mrr", at5.meanReciprocalRank());
        overall.put("ndcgAt5", at5.macroNdcgAtK());
        overall.put("noAnswerFalsePositiveRate",
                at5.unanswerableFalsePositiveRate());
        overall.put("staleVersionLeakCount", staleLeakCount);

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("evaluationType", "OFFLINE_MOCK_RETRIEVAL_SMOKE");
        report.put("evidenceBoundary",
                "验证版本门禁、候选检索与本地重排接线；不代表真实 BGE-M3 或 Milvus 效果");
        report.put("datasetVersion", "rag-retrieval-smoke-v1");
        report.put("executedAt", Instant.now().toString());
        report.put("codeRevision", System.getProperty(
                "rag.eval.codeRevision", "UNRECORDED"));
        report.put("worktreeFingerprint", System.getProperty(
                "rag.eval.worktreeFingerprint", "UNRECORDED"));
        report.put("configuration", Map.of(
                "embedding", "MockEmbeddingClient/hash",
                "dimension", DIMENSION,
                "vectorStore", "InMemoryConsistentVectorStore",
                "reranker", "LocalLexicalKnowledgeReranker(0.7,0.3)",
                "candidateK", CANDIDATE_K,
                "topK", TOP_K,
                "activeVersionIds", activeVersionIds));
        report.put("overall", overall);
        report.put("cases", caseReports);

        if (output.getParent() != null) {
            Files.createDirectories(output.getParent());
        }
        json.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report);

        // 效果分数只记录、不设伪门槛；版本泄漏属于可靠性硬门禁，必须为 0。
        assertEquals(0, staleLeakCount, "检索结果不能包含非 ACTIVE 版本");
    }

    private static Path resolveReportOutput(
            String explicitOutput, String buildDirectory) {
        if (explicitOutput != null && !explicitOutput.isBlank()) {
            return Path.of(explicitOutput);
        }
        if (buildDirectory == null || buildDirectory.isBlank()) {
            throw new IllegalStateException(
                    "缺少 rag.eval.output 或 rag.eval.buildDirectory");
        }
        Path moduleBuildDirectory = Path.of(buildDirectory);
        if (!moduleBuildDirectory.isAbsolute()) {
            throw new IllegalStateException(
                    "rag.eval.buildDirectory 必须是绝对路径: "
                            + buildDirectory);
        }
        return moduleBuildDirectory.resolve("rag-eval")
                .resolve("latest.json")
                .normalize();
    }

    private List<CorpusRow> readCorpus(Path path) throws IOException {
        List<CorpusRow> rows = new ArrayList<>();
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            JsonNode node = json.readTree(line);
            rows.add(new CorpusRow(
                    node.path("chunkId").asLong(),
                    node.path("documentId").asLong(),
                    node.path("documentVersionId").asLong(),
                    node.path("title").asText(),
                    node.path("content").asText(),
                    node.path("active").asBoolean()));
        }
        return List.copyOf(rows);
    }

    private Path defaultDatasetRoot() {
        Path fromRepositoryRoot = Path.of(
                "backend/evals/datasets/rag-retrieval-smoke-v1");
        if (Files.isDirectory(fromRepositoryRoot)) {
            return fromRepositoryRoot;
        }
        return Path.of("evals/datasets/rag-retrieval-smoke-v1");
    }

    private List<EvalCase> readCases(Path path) throws IOException {
        List<EvalCase> rows = new ArrayList<>();
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            JsonNode node = json.readTree(line);
            rows.add(new EvalCase(
                    node.path("caseId").asText(),
                    node.path("query").asText(),
                    longList(node.path("relevantChunkIds")),
                    node.path("answerability").asText(),
                    textList(node.path("tags"))));
        }
        return List.copyOf(rows);
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

    private KnowledgeChunk toChunk(CorpusRow row) {
        KnowledgeChunk chunk = new KnowledgeChunk();
        chunk.setId(row.chunkId());
        chunk.setCourseId(COURSE_ID);
        chunk.setDocumentId(row.documentId());
        chunk.setDocumentVersionId(row.documentVersionId());
        chunk.setChunkIndex(0);
        chunk.setTitle(row.title());
        chunk.setContent(row.content());
        chunk.setSourcePage(1);
        return chunk;
    }

    private RetrievalGroundTruth groundTruth(EvalCase evalCase) {
        return new RetrievalGroundTruth(
                evalCase.caseId(),
                evalCase.answerable()
                        ? Answerability.ANSWERABLE
                        : Answerability.UNANSWERABLE,
                Set.copyOf(evalCase.relevantChunkIds()),
                Set.copyOf(evalCase.relevantChunkIds()),
                List.of(),
                Map.of(),
                Set.of());
    }

    private record CorpusRow(
            long chunkId,
            long documentId,
            long documentVersionId,
            String title,
            String content,
            boolean active) {
    }

    private record EvalCase(
            String caseId,
            String query,
            List<Long> relevantChunkIds,
            String answerability,
            List<String> tags) {
        boolean answerable() {
            return "ANSWERABLE".equals(answerability);
        }
    }

    /** 评测只需要按 id 回表，其余写接口显式拒绝，避免测试悄悄改变语料。 */
    private static final class FixtureChunkRepository
            implements KnowledgeChunkRepository {
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
            throw new UnsupportedOperationException("评测禁止 course 级兜底");
        }

        @Override
        public void deleteByDocumentId(long documentId) {
            throw new UnsupportedOperationException("评测语料只读");
        }

        @Override
        public void deleteByCourseId(long courseId) {
            throw new UnsupportedOperationException("评测语料只读");
        }

        @Override
        public void updateVectorStatus(
                Long chunkId, String milvusVectorId, String embeddingStatus) {
            throw new UnsupportedOperationException("评测语料只读");
        }
    }
}
