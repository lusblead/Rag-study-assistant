package com.rag.backend.agent.retrieval.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.backend.agent.evaluation.CaseRetrievalMetrics;
import com.rag.backend.agent.evaluation.RetrievalEvalReport;
import com.rag.backend.agent.evaluation.RetrievalGroundTruth;
import com.rag.backend.agent.evaluation.RetrievalMetricsCalculator;
import com.rag.backend.agent.rerank.LocalLexicalKnowledgeReranker;
import com.rag.backend.agent.rerank.RerankExecutionResult;
import com.rag.backend.agent.retrieval.RetrievedChunk;
import com.rag.backend.agent.settings.AgentModelSettingsService;
import com.rag.backend.rerank.DynamicKnowledgeReranker;
import com.rag.backend.rerank.RerankSettings;
import com.rag.backend.rerank.RerankSettingsMapper;
import com.rag.backend.rerank.RerankSettingsService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Step 3.1 的受控 Dense-only 消融入口。该类默认不启用；PowerShell gate 成功后才传入显式开关。
 * 它绝不读取 Test55，并且候选只由 Local harness 生成一次，再复制给三个 Rerank 臂。
 * 该入口不包含当前 Hybrid/MySQL Lexical 候选生成，不能作为 Hybrid 生产链质量验收。
 */
class ReviewedDev45RerankAblationTest {
    private static final String DEV45 =
            LocalObsidianRetrievalEvalTest.REVIEWED_DEV45_CASE_FILE;

    @Test
    void onlyTheExplicitReviewedDev45FileIsAccepted() {
        assertThrows(IllegalArgumentException.class,
                () -> LocalObsidianRetrievalEvalTest.requireDev45CaseFile(
                        "standard_reviewed_100_retrieval_test.jsonl"));
        assertThrows(IllegalArgumentException.class,
                () -> LocalObsidianRetrievalEvalTest.requireDev45CaseFile(
                        "standard_reviewed_100_retrieval.jsonl"));
        assertThrows(IllegalArgumentException.class,
                () -> LocalObsidianRetrievalEvalTest.requireDev45CaseFile("other.jsonl"));
        LocalObsidianRetrievalEvalTest.requireDev45CaseFile(DEV45);
    }

    @Test
    void invalidCompositeWeightsAreRejectedBeforeCandidateCapture() {
        assertThrows(IllegalArgumentException.class,
                () -> new AblationPolicy(
                        false, 0.0, false, 0.0,
                        true, "normalized-min-max-v1", -0.1, 1.1));
        assertThrows(IllegalArgumentException.class,
                () -> new AblationPolicy(
                        false, 0.0, false, 0.0,
                        true, "normalized-min-max-v1", 0.0, 0.0));
    }

    @Test
    @EnabledIfSystemProperty(named = "rag.dev45.rerank.ablation", matches = "true")
    void runsFrozenCandidateNoneLocalRemoteAblationOnDev45Only() throws Exception {
        int candidateK = positiveInt("rag.dev45.candidateK", 20);
        int finalK = positiveInt("rag.dev45.finalK", 5);
        if (candidateK < finalK) {
            throw new IllegalArgumentException("CandidateK must be >= FinalK");
        }
        String caseFile = System.getProperty("rag.dev45.caseFile", "").trim();
        LocalObsidianRetrievalEvalTest.requireDev45CaseFile(caseFile);
        Path datasetRoot = Path.of(requiredProperty("rag.eval.datasetDir"));
        boolean remoteFailOpen = Boolean.parseBoolean(System.getProperty(
                "rag.dev45.remote.failOpen", "true"));
        AblationPolicy policy = AblationPolicy.fromSystemProperties();

        LocalObsidianRetrievalEvalTest.CapturedDev45Candidates captured =
                LocalObsidianRetrievalEvalTest.captureDev45Candidates(
                        datasetRoot, caseFile, candidateK, finalK);
        assertEquals(45, captured.cases().size(), "Step 3.1 only evaluates Dev45");

        List<VariantEvaluation> variants = List.of(
                evaluate("none", captured, false, policy),
                evaluate("local", captured, false, policy),
                evaluate("remote", captured, remoteFailOpen, policy));
        RetrievalEvalReport baselineCandidate = variants.get(0).candidateReport();
        for (VariantEvaluation variant : variants) {
            assertEquals(baselineCandidate, variant.candidateReport(),
                    "Pure rerank arms must share exactly the same CandidateK metrics");
            assertEquals(captured.cases().stream()
                            .map(item -> chunkIds(item.frozenCandidates())).toList(),
                    variant.frozenCandidateIds(),
                    "Every arm must receive the captured CandidateK list unchanged");
        }

        writeAggregateReport(captured, variants, remoteFailOpen, policy);
    }

    private VariantEvaluation evaluate(
            String provider,
            LocalObsidianRetrievalEvalTest.CapturedDev45Candidates captured,
            boolean remoteFailOpen,
            AblationPolicy policy) {
        DynamicKnowledgeReranker reranker = dynamic(
                provider, remoteFailOpen, policy);
        RetrievalMetricsCalculator calculator = new RetrievalMetricsCalculator();
        List<VariantCase> cases = new ArrayList<>();
        for (LocalObsidianRetrievalEvalTest.CapturedDev45Case item : captured.cases()) {
            List<RetrievedChunk> frozen = List.copyOf(item.frozenCandidates());
            RerankExecutionResult execution = reranker.rerankWithResult(
                    item.query(), frozen, captured.finalK());
            List<Long> candidateIds = chunkIds(frozen);
            List<Long> finalIds = chunkIds(execution.chunks());
            cases.add(new VariantCase(
                    item.groundTruth(),
                    calculator.evaluateCase(
                            item.groundTruth(), candidateIds, captured.candidateK()),
                    calculator.evaluateCase(
                            item.groundTruth(), finalIds, captured.finalK()),
                    candidateIds,
                    execution));
        }
        List<RetrievalGroundTruth> truth = cases.stream()
                .map(VariantCase::groundTruth).toList();
        return new VariantEvaluation(
                provider,
                calculator.summarizeGroundTruth(
                        truth, cases.stream().map(VariantCase::candidateMetrics).toList(),
                        captured.candidateK()),
                calculator.summarizeGroundTruth(
                        truth, cases.stream().map(VariantCase::finalMetrics).toList(),
                        captured.finalK()),
                cases);
    }

    private DynamicKnowledgeReranker dynamic(
            String provider,
            boolean remoteFailOpen,
            AblationPolicy policy) {
        RerankSettings settings = new RerankSettings();
        settings.setId(1L);
        settings.setProvider("remote".equals(provider) ? "siliconflow" : provider);
        settings.setFailOpen(remoteFailOpen);
        settings.setBaseUrl("remote".equals(provider)
                ? requiredEnvironment("RAG_EVAL_RERANK_BASE_URL")
                : "https://not-used.invalid/v1");
        settings.setModel("remote".equals(provider)
                ? requiredEnvironment("RAG_EVAL_RERANK_MODEL")
                : "not-used");
        settings.setApiKey("remote".equals(provider)
                ? requiredEnvironment("RAG_EVAL_RERANK_API_KEY")
                : "not-used");

        RerankSettingsMapper mapper = mock(RerankSettingsMapper.class);
        when(mapper.selectCurrent()).thenReturn(settings);
        MockEnvironment environment = new MockEnvironment()
                .withProperty("rerank.timeout-seconds", System.getProperty(
                        "rag.dev45.remote.timeoutSeconds", "60"));
        RerankSettingsService settingsService = new RerankSettingsService(
                mapper, environment, new ObjectMapper(),
                mock(AgentModelSettingsService.class));
        return new DynamicKnowledgeReranker(
                settingsService, new LocalLexicalKnowledgeReranker(0.7, 0.3),
                policy.remoteThresholdEnabled(), policy.remoteThreshold(),
                policy.localThresholdEnabled(), policy.localThreshold(),
                policy.compositeEnabled(), policy.compositeVersion(),
                policy.compositeBaseWeight(), policy.compositeRerankWeight());
    }

    private void writeAggregateReport(
            LocalObsidianRetrievalEvalTest.CapturedDev45Candidates captured,
            List<VariantEvaluation> variants,
            boolean remoteFailOpen,
            AblationPolicy policy) throws IOException {
        Path output = Path.of(requiredProperty("rag.dev45.output"))
                .toAbsolutePath().normalize();
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("schema", "reviewed-dev45-rerank-ablation/v1");
        report.put("runStatus", "COMPLETED");
        report.put("split", "dev");
        report.put("evidenceBoundary",
                "Frozen Dense-only candidates; not Hybrid/MySQL Lexical/RRF acceptance");
        report.put("caseCount", captured.cases().size());
        report.put("configuration", Map.of(
                "candidateGenerator", "local-obsidian-dense-only-v1",
                "candidateK", captured.candidateK(),
                "finalK", captured.finalK(),
                "remoteFailOpen", remoteFailOpen,
                "policy", policy.reportView()));
        report.put("arms", variants.stream()
                .map(this::aggregateArm).toList());
        // 只写聚合值：不得在制品中包含 query、chunk、路径、凭据或远程响应。
        try (var stream = Files.newOutputStream(
                output, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(stream, report);
        }
    }

    private Map<String, Object> aggregateArm(VariantEvaluation variant) {
        Map<String, List<VariantCase>> byActual = new TreeMap<>();
        Map<String, Integer> fallbackDistribution = new TreeMap<>();
        for (VariantCase item : variant.cases()) {
            String actual = item.execution().actualReranker().name().toLowerCase();
            byActual.computeIfAbsent(actual, ignored -> new ArrayList<>()).add(item);
            fallbackDistribution.merge(item.execution().fallbackReason().name().toLowerCase(),
                    1, Integer::sum);
        }
        Map<String, Object> actualMetrics = new TreeMap<>();
        RetrievalMetricsCalculator calculator = new RetrievalMetricsCalculator();
        byActual.forEach((actual, cases) -> actualMetrics.put(actual,
                metricView(calculator.summarizeGroundTruth(
                        cases.stream().map(VariantCase::groundTruth).toList(),
                        cases.stream().map(VariantCase::finalMetrics).toList(),
                        variant.finalReport().k()))));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("requestedReranker", variant.requestedReranker());
        result.put("candidate", metricView(variant.candidateReport()));
        result.put("final", metricView(variant.finalReport()));
        result.put("actualRerankerDistribution", byActual.entrySet().stream()
                .collect(TreeMap::new, (map, entry) -> map.put(
                        entry.getKey(), entry.getValue().size()), TreeMap::putAll));
        result.put("fallbackReasonDistribution", fallbackDistribution);
        // fallback 仅出现在其 actual 分组，不能伪装成 remote 成功质量。
        result.put("finalMetricsByActualReranker", actualMetrics);
        return result;
    }

    private Map<String, Object> metricView(RetrievalEvalReport report) {
        return Map.ofEntries(
                Map.entry("k", report.k()),
                Map.entry("answerableCaseCount", report.answerableCases()),
                Map.entry("unanswerableCaseCount", report.unanswerableCases()),
                Map.entry("strictRecallAtK", report.macroRecallAtK()),
                Map.entry("acceptableRecallAtK",
                        report.macroAcceptableRecallAtK()),
                Map.entry("precisionAtK", report.macroPrecisionAtK()),
                Map.entry("mrr", report.meanReciprocalRank()),
                Map.entry("acceptableMrr",
                        report.meanAcceptableReciprocalRank()),
                Map.entry("ndcgAtK", report.macroNdcgAtK()),
                Map.entry("acceptableNdcgAtK",
                        report.macroAcceptableNdcgAtK()),
                Map.entry("sourceCoverageAtK",
                        report.macroSourceCoverageAtK()),
                Map.entry("requiredEvidenceGroupCoverageAtK",
                        report.macroRequiredEvidenceGroupCoverageAtK()),
                Map.entry("emptyRetrievalAccuracy",
                        report.emptyRetrievalAccuracy()),
                Map.entry("unanswerableFalsePositiveRate",
                        report.unanswerableFalsePositiveRate()));
    }

    private static List<Long> chunkIds(List<RetrievedChunk> chunks) {
        return chunks.stream().map(RetrievedChunk::chunkId).toList();
    }

    private static int positiveInt(String name, int fallback) {
        int value = Integer.parseInt(System.getProperty(name, String.valueOf(fallback)));
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be > 0");
        }
        return value;
    }

    private static String requiredProperty(String name) {
        String value = System.getProperty(name, "").trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("Missing required system property " + name);
        }
        return value;
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required environment variable " + name);
        }
        return value;
    }

    private static boolean booleanProperty(String name, boolean fallback) {
        String value = System.getProperty(name, String.valueOf(fallback)).trim();
        if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) {
            throw new IllegalArgumentException(name + " must be true or false");
        }
        return Boolean.parseBoolean(value);
    }

    private static double finiteDouble(String name, double fallback) {
        double value = Double.parseDouble(
                System.getProperty(name, String.valueOf(fallback)));
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
        return value;
    }

    private record AblationPolicy(
            boolean remoteThresholdEnabled,
            double remoteThreshold,
            boolean localThresholdEnabled,
            double localThreshold,
            boolean compositeEnabled,
            String compositeVersion,
            double compositeBaseWeight,
            double compositeRerankWeight) {
        private AblationPolicy {
            if (compositeVersion == null || compositeVersion.isBlank()) {
                throw new IllegalArgumentException(
                        "rag.dev45.composite.version must not be blank");
            }
            if (compositeBaseWeight < 0.0 || compositeRerankWeight < 0.0
                    || (compositeBaseWeight == 0.0
                    && compositeRerankWeight == 0.0)) {
                throw new IllegalArgumentException(
                        "Composite weights must be non-negative and at least one positive");
            }
        }

        private static AblationPolicy fromSystemProperties() {
            return new AblationPolicy(
                    booleanProperty(
                            "rag.dev45.threshold.remote.enabled", false),
                    finiteDouble("rag.dev45.threshold.remote.minScore", 0.0),
                    booleanProperty(
                            "rag.dev45.threshold.local.enabled", false),
                    finiteDouble("rag.dev45.threshold.local.minScore", 0.0),
                    booleanProperty("rag.dev45.composite.enabled", false),
                    System.getProperty(
                            "rag.dev45.composite.version",
                            "normalized-min-max-v1").trim(),
                    finiteDouble("rag.dev45.composite.baseWeight", 0.5),
                    finiteDouble("rag.dev45.composite.rerankWeight", 0.5));
        }

        private Map<String, Object> reportView() {
            return Map.of(
                    "remoteThreshold", Map.of(
                            "enabled", remoteThresholdEnabled,
                            "minScore", remoteThreshold),
                    "localThreshold", Map.of(
                            "enabled", localThresholdEnabled,
                            "minScore", localThreshold),
                    "composite", Map.of(
                            "enabled", compositeEnabled,
                            "version", compositeVersion,
                            "baseWeight", compositeBaseWeight,
                            "rerankWeight", compositeRerankWeight));
        }
    }

    private record VariantCase(
            RetrievalGroundTruth groundTruth,
            CaseRetrievalMetrics candidateMetrics,
            CaseRetrievalMetrics finalMetrics,
            List<Long> frozenCandidateIds,
            RerankExecutionResult execution) {
    }

    private record VariantEvaluation(
            String requestedReranker,
            RetrievalEvalReport candidateReport,
            RetrievalEvalReport finalReport,
            List<VariantCase> cases) {
        private List<List<Long>> frozenCandidateIds() {
            return cases.stream().map(VariantCase::frozenCandidateIds).toList();
        }
    }
}
