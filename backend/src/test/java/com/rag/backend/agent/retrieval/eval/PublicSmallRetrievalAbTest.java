package com.rag.backend.agent.retrieval.eval;

import com.rag.backend.agent.evaluation.Answerability;
import com.rag.backend.agent.evaluation.CaseRetrievalMetrics;
import com.rag.backend.agent.evaluation.RetrievalEvalReport;
import com.rag.backend.agent.evaluation.RetrievalGroundTruth;
import com.rag.backend.agent.evaluation.RetrievalMetricsCalculator;
import com.rag.backend.agent.rerank.KnowledgeReranker;
import com.rag.backend.agent.rerank.LocalLexicalKnowledgeReranker;
import com.rag.backend.agent.rerank.NoOpKnowledgeReranker;
import com.rag.backend.agent.retrieval.RetrievedChunk;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Credential-free paired retrieval comparison over public-small-v1.
 * The exact-cosine candidates are built once per case; only the reranker changes.
 */
class PublicSmallRetrievalAbTest {
    private static final String SCHEME_A = "A_NO_RERANK";
    private static final String SCHEME_B = "B_LOCAL_LEXICAL_RERANK";
    private static final double EPSILON = 1.0e-12;
    private static final DecimalFormat DECIMAL = new DecimalFormat(
            "0.000000", DecimalFormatSymbols.getInstance(Locale.ROOT));

    @Test
    void reportIsByteStableAcrossRepeatedRuns() throws IOException {
        PublicSmallDatasetSupport.Dataset dataset = loadDataset();
        Comparison first = evaluateComparison(dataset);
        Comparison second = evaluateComparison(dataset);

        assertEquals(first, second,
                "Repeated A/B runs must preserve candidates, rankings and metrics");
        String firstReport = renderMarkdown(first);
        String secondReport = renderMarkdown(second);
        assertEquals(firstReport, secondReport,
                "Repeated A/B reports must be byte-stable");
        writeMarkdownReport(firstReport);
    }

    @Test
    void bothSchemesShareEveryConfigurationExceptReranker() {
        Comparison comparison = evaluateComparison(loadDataset());
        Set<String> changedFields = new LinkedHashSet<>();
        for (String key : comparison.schemeA().configuration().keySet()) {
            if (!comparison.schemeA().configuration().get(key)
                    .equals(comparison.schemeB().configuration().get(key))) {
                changedFields.add(key);
            }
        }

        assertEquals(Set.of("reranker"), changedFields);
        assertEquals(
                comparison.schemeA().cases().stream()
                        .map(VariantCase::candidateChunkIds).toList(),
                comparison.schemeB().cases().stream()
                        .map(VariantCase::candidateChunkIds).toList(),
                "Both schemes must receive the exact same candidate lists");
    }

    @Test
    void reportContainsConfigurationCasesAggregatesAndRegressions() {
        Comparison comparison = evaluateComparison(loadDataset());
        String report = renderMarkdown(comparison);

        assertTrue(report.contains("## Frozen configuration"));
        assertTrue(report.contains("## Per-case results"));
        assertTrue(report.contains("## Overall metrics"));
        assertTrue(report.contains("## Improved cases"));
        assertTrue(report.contains("## Regressed cases"));
        assertTrue(report.contains("## Evidence boundary"));
        comparison.dataset().cases().forEach(evalCase ->
                assertTrue(report.contains(evalCase.caseId())));
        assertTrue(report.contains("fixture-only component evidence"));
    }

    @Test
    void currentLocalRerankerChangesAtLeastOneFrozenCaseOrder() {
        Comparison comparison = evaluateComparison(loadDataset());
        assertFalse(comparison.orderChangedCaseIds().isEmpty(),
                "The frozen set must exercise a real before/after rerank order change");
    }

    @Test
    void frozenRetrievalExpectationsMatchCurrentImplementations() {
        Comparison comparison = evaluateComparison(loadDataset());
        for (int index = 0; index < comparison.dataset().cases().size(); index++) {
            PublicSmallDatasetSupport.EvalCase evalCase =
                    comparison.dataset().cases().get(index);
            PublicSmallDatasetSupport.RetrievalExpectations expected =
                    evalCase.retrievalExpectations();
            VariantCase schemeA = comparison.schemeA().cases().get(index);
            VariantCase schemeB = comparison.schemeB().cases().get(index);

            assertEquals(expected.exactCosineCandidateChunkIds(),
                    schemeA.candidateChunkIds(), evalCase.caseId());
            assertEquals(expected.exactCosineCandidateChunkIds().stream()
                            .limit(comparison.schemeA().report().k()).toList(),
                    schemeA.rankedChunkIds(), evalCase.caseId());
            assertEquals(expected.rerankedTopChunkIds(),
                    schemeB.rankedChunkIds(), evalCase.caseId());
        }
    }

    private Comparison evaluateComparison(PublicSmallDatasetSupport.Dataset dataset) {
        int candidateK = dataset.manifest().path("candidateK").asInt();
        int topK = dataset.manifest().path("topK").asInt();
        KnowledgeReranker noRerank = new NoOpKnowledgeReranker();
        KnowledgeReranker localLexical =
                new LocalLexicalKnowledgeReranker(0.7, 0.3);
        RetrievalMetricsCalculator calculator = new RetrievalMetricsCalculator();

        Map<Long, Long> sourceByChunkId = new LinkedHashMap<>();
        Map<String, Long> documentIdBySource = new LinkedHashMap<>();
        dataset.corpus().forEach(row ->
                sourceByChunkId.put(row.chunkId(), row.documentId()));
        dataset.corpus().forEach(row ->
                documentIdBySource.put(row.sourceId(), row.documentId()));
        List<RetrievalGroundTruth> groundTruth = new ArrayList<>();
        List<VariantCase> schemeACases = new ArrayList<>();
        List<VariantCase> schemeBCases = new ArrayList<>();

        for (PublicSmallDatasetSupport.EvalCase evalCase : dataset.cases()) {
            List<RetrievedChunk> candidates = exactCosineCandidates(
                    dataset, evalCase, candidateK);
            List<Long> candidateIds = chunkIds(candidates);
            List<RetrievedChunk> aRanked = noRerank.rerank(
                    evalCase.query(), List.copyOf(candidates), topK);
            List<RetrievedChunk> bRanked = localLexical.rerank(
                    evalCase.query(), List.copyOf(candidates), topK);
            RetrievalGroundTruth truth = groundTruth(
                    evalCase, sourceByChunkId, documentIdBySource);
            groundTruth.add(truth);
            schemeACases.add(new VariantCase(
                    evalCase.caseId(), candidateIds, chunkIds(aRanked),
                    calculator.evaluateCase(truth, chunkIds(aRanked), topK)));
            schemeBCases.add(new VariantCase(
                    evalCase.caseId(), candidateIds, chunkIds(bRanked),
                    calculator.evaluateCase(truth, chunkIds(bRanked), topK)));
        }

        RetrievalEvalReport schemeAReport = calculator.summarizeGroundTruth(
                groundTruth,
                schemeACases.stream().map(VariantCase::metrics).toList(),
                topK);
        RetrievalEvalReport schemeBReport = calculator.summarizeGroundTruth(
                groundTruth,
                schemeBCases.stream().map(VariantCase::metrics).toList(),
                topK);
        Map<String, String> frozen = frozenConfiguration(dataset);
        Map<String, String> aConfig = new LinkedHashMap<>(frozen);
        aConfig.put("reranker", "NoOpKnowledgeReranker");
        Map<String, String> bConfig = new LinkedHashMap<>(frozen);
        bConfig.put("reranker", "LocalLexicalKnowledgeReranker(0.7,0.3)");

        Variant schemeA = new Variant(
                SCHEME_A, aConfig, List.copyOf(schemeACases), schemeAReport);
        Variant schemeB = new Variant(
                SCHEME_B, bConfig, List.copyOf(schemeBCases), schemeBReport);
        List<String> improved = new ArrayList<>();
        List<String> regressed = new ArrayList<>();
        List<String> orderChanged = new ArrayList<>();
        for (int index = 0; index < dataset.cases().size(); index++) {
            VariantCase a = schemeACases.get(index);
            VariantCase b = schemeBCases.get(index);
            if (!a.rankedChunkIds().equals(b.rankedChunkIds())) {
                orderChanged.add(a.caseId());
            }
            Change change = classify(a.metrics(), b.metrics());
            if (change == Change.IMPROVED) {
                improved.add(a.caseId());
            } else if (change == Change.REGRESSED) {
                regressed.add(a.caseId());
            }
        }
        return new Comparison(
                dataset,
                schemeA,
                schemeB,
                List.copyOf(improved),
                List.copyOf(regressed),
                List.copyOf(orderChanged));
    }

    private List<RetrievedChunk> exactCosineCandidates(
            PublicSmallDatasetSupport.Dataset dataset,
            PublicSmallDatasetSupport.EvalCase evalCase,
            int candidateK) {
        List<Double> queryVector = requireVector(
                dataset, "query:" + evalCase.caseId());
        return dataset.corpus().stream()
                .map(row -> new RetrievedChunk(
                        row.chunkId(),
                        row.documentId(),
                        row.sourceId(),
                        row.title(),
                        row.content(),
                        null,
                        cosine(queryVector,
                                requireVector(dataset, "chunk:" + row.chunkId()))))
                .sorted(Comparator
                        .comparing(RetrievedChunk::score,
                                Comparator.reverseOrder())
                        .thenComparing(RetrievedChunk::chunkId)
                        .thenComparing(RetrievedChunk::documentId))
                .limit(candidateK)
                .toList();
    }

    private RetrievalGroundTruth groundTruth(
            PublicSmallDatasetSupport.EvalCase evalCase,
            Map<Long, Long> sourceByChunkId,
            Map<String, Long> documentIdBySource) {
        List<Set<Long>> groups = evalCase.requiredEvidenceGroups().stream()
                .map(group -> Set.copyOf(group.chunkIds()))
                .toList();
        Set<Long> requiredSourceIds = new TreeSet<>();
        for (String sourceId : evalCase.requiredSourceIds()) {
            Long documentId = documentIdBySource.get(sourceId);
            if (documentId == null) {
                throw new IllegalStateException("Unknown sourceId " + sourceId);
            }
            requiredSourceIds.add(documentId);
        }
        return new RetrievalGroundTruth(
                evalCase.caseId(),
                evalCase.answerable()
                        ? Answerability.ANSWERABLE
                        : Answerability.UNANSWERABLE,
                new TreeSet<>(evalCase.relevantChunkIds()),
                new TreeSet<>(evalCase.acceptableChunkIds()),
                groups,
                sourceByChunkId,
                requiredSourceIds);
    }

    private Map<String, String> frozenConfiguration(
            PublicSmallDatasetSupport.Dataset dataset) {
        Map<String, String> result = new LinkedHashMap<>();
        result.put("datasetId", dataset.manifest().path("datasetId").asText());
        result.put("datasetVersion",
                dataset.manifest().path("datasetVersion").asText());
        result.put("corpusSha256", dataset.corpusSha256());
        result.put("casesSha256", dataset.casesSha256());
        result.put("embeddingsSha256", dataset.embeddingsSha256());
        result.put("seed", dataset.manifest().path("seed").asText());
        result.put("embeddingDimension",
                dataset.manifest().path("embeddingDimension").asText());
        result.put("indexType", dataset.manifest().path("indexType").asText());
        result.put("tieBreaker", dataset.manifest().path("tieBreaker").asText());
        result.put("candidateK", dataset.manifest().path("candidateK").asText());
        result.put("topK", dataset.manifest().path("topK").asText());
        result.put("externalCalls", "0");
        return result;
    }

    private Change classify(
            CaseRetrievalMetrics baseline, CaseRetrievalMetrics candidate) {
        double[] before = qualityVector(baseline);
        double[] after = qualityVector(candidate);
        boolean decreased = false;
        boolean increased = false;
        for (int index = 0; index < before.length; index++) {
            decreased |= after[index] < before[index] - EPSILON;
            increased |= after[index] > before[index] + EPSILON;
        }
        // Conservative paired reporting: any lost metric makes the case a regression.
        if (decreased) {
            return Change.REGRESSED;
        }
        if (increased) {
            return Change.IMPROVED;
        }
        return Change.UNCHANGED;
    }

    private double[] qualityVector(CaseRetrievalMetrics metrics) {
        return new double[] {
                metrics.recallAtK(),
                metrics.acceptableRecallAtK(),
                metrics.reciprocalRank(),
                metrics.acceptableReciprocalRank(),
                metrics.ndcgAtK(),
                metrics.acceptableNdcgAtK(),
                metrics.sourceCoverageAtK(),
                metrics.requiredEvidenceGroupCoverageAtK(),
                -metrics.unanswerableFalsePositive()
        };
    }

    private String renderMarkdown(Comparison comparison) {
        StringBuilder output = new StringBuilder();
        output.append("# public-small-v1 Retrieval A/B\n\n");
        output.append("This is measured, fixture-only component evidence. ")
                .append("It compares two currently wired reranker implementations over ")
                .append("one frozen exact-cosine candidate list per case.\n\n");
        output.append("## Frozen configuration\n\n");
        output.append("| Field | Scheme A | Scheme B |\n")
                .append("|---|---|---|\n");
        comparison.schemeA().configuration().forEach((key, valueA) -> {
            String valueB = comparison.schemeB().configuration().get(key);
            output.append("| ").append(escape(key)).append(" | ")
                    .append(escape(valueA)).append(" | ")
                    .append(escape(valueB)).append(" |\n");
        });
        output.append("\nOnly changed variable: `reranker`. Corpus, cases, vectors, ")
                .append("candidate K, Top K, seed, index and tie-breaker are identical.\n\n");

        output.append("## Per-case results\n\n");
        output.append("| Case | Category | Query | Exact candidates | A Top K | B Top K | A metrics | B metrics | Change |\n")
                .append("|---|---|---|---|---|---|---|---|---|\n");
        for (int index = 0; index < comparison.dataset().cases().size(); index++) {
            PublicSmallDatasetSupport.EvalCase evalCase =
                    comparison.dataset().cases().get(index);
            VariantCase a = comparison.schemeA().cases().get(index);
            VariantCase b = comparison.schemeB().cases().get(index);
            output.append("| ").append(escape(evalCase.caseId())).append(" | ")
                    .append(escape(evalCase.category())).append(" | ")
                    .append(escape(evalCase.query())).append(" | ")
                    .append(ids(a.candidateChunkIds())).append(" | ")
                    .append(ids(a.rankedChunkIds())).append(" | ")
                    .append(ids(b.rankedChunkIds())).append(" | ")
                    .append(metricSummary(a.metrics())).append(" | ")
                    .append(metricSummary(b.metrics())).append(" | ")
                    .append(classify(a.metrics(), b.metrics()).label()).append(" |\n");
        }

        output.append("\n## Overall metrics\n\n");
        output.append("All positive metrics are macro-averaged over answerable cases only; ")
                .append("unanswerable false-positive rate uses only unanswerable cases.\n\n");
        output.append("| Metric | Scheme A | Scheme B | Delta B-A |\n")
                .append("|---|---:|---:|---:|\n");
        appendOverallRows(output,
                comparison.schemeA().report(), comparison.schemeB().report());

        output.append("\n## Improved cases\n\n");
        appendCaseList(output, comparison, comparison.improvedCaseIds());
        output.append("\n## Regressed cases\n\n");
        appendCaseList(output, comparison, comparison.regressedCaseIds());
        output.append("\n## Order-changed cases\n\n");
        appendCaseList(output, comparison, comparison.orderChangedCaseIds());

        output.append("\n## Evidence boundary\n\n");
        output.append("- The corpus and questions are original neutral technical fiction; ")
                .append("no private corpus is transformed or copied.\n");
        output.append("- Vectors are fixed synthetic fixture vectors; network, credentials, ")
                .append("real Embedding and LLM calls are all absent.\n");
        output.append("- Exact-cosine candidates and these metrics validate deterministic ")
                .append("evaluation and reranker behavior only. They do not establish real ")
                .append("retrieval quality, RAG answer quality or production acceptance.\n");
        output.append("- Unanswerable false positives mean the retriever returned any Top K ")
                .append("chunk; this fixture does not add a calibrated abstention threshold.\n");
        output.append("- No quality release threshold is inferred from this small synthetic set.\n\n");
        output.append("Reproduce from the repository root:\n\n")
                .append("```powershell\n")
                .append("& 'backend\\evals\\scripts\\run_public_small_retrieval_ab.ps1'\n")
                .append("```\n");
        String report = output.toString();
        PublicSmallDatasetSupport.scanPrivacy("generated A/B report", report);
        return report;
    }

    private void appendOverallRows(
            StringBuilder output,
            RetrievalEvalReport a,
            RetrievalEvalReport b) {
        appendOverallRow(output, "Recall@K",
                a.macroRecallAtK(), b.macroRecallAtK());
        appendOverallRow(output, "Acceptable Recall@K",
                a.macroAcceptableRecallAtK(), b.macroAcceptableRecallAtK());
        appendOverallRow(output, "MRR",
                a.meanReciprocalRank(), b.meanReciprocalRank());
        appendOverallRow(output, "Acceptable MRR",
                a.meanAcceptableReciprocalRank(), b.meanAcceptableReciprocalRank());
        appendOverallRow(output, "nDCG@K",
                a.macroNdcgAtK(), b.macroNdcgAtK());
        appendOverallRow(output, "Acceptable nDCG@K",
                a.macroAcceptableNdcgAtK(), b.macroAcceptableNdcgAtK());
        appendOverallRow(output, "Source Coverage@K",
                a.macroSourceCoverageAtK(), b.macroSourceCoverageAtK());
        appendOverallRow(output, "Required Evidence Group Coverage@K",
                a.macroRequiredEvidenceGroupCoverageAtK(),
                b.macroRequiredEvidenceGroupCoverageAtK());
        appendOverallRow(output, "Unanswerable false-positive rate",
                a.unanswerableFalsePositiveRate(),
                b.unanswerableFalsePositiveRate());
    }

    private void appendOverallRow(
            StringBuilder output, String label, double a, double b) {
        output.append("| ").append(label).append(" | ")
                .append(format(a)).append(" | ")
                .append(format(b)).append(" | ")
                .append(format(b - a)).append(" |\n");
    }

    private void appendCaseList(
            StringBuilder output, Comparison comparison, List<String> caseIds) {
        if (caseIds.isEmpty()) {
            output.append("- None on this frozen fixture.\n");
            return;
        }
        for (String caseId : caseIds) {
            int index = indexOfCase(comparison.dataset().cases(), caseId);
            VariantCase a = comparison.schemeA().cases().get(index);
            VariantCase b = comparison.schemeB().cases().get(index);
            output.append("- `").append(caseId).append("`: A ")
                    .append(ids(a.rankedChunkIds())).append(" -> B ")
                    .append(ids(b.rankedChunkIds())).append("; ")
                    .append(metricSummary(a.metrics())).append(" -> ")
                    .append(metricSummary(b.metrics())).append("\n");
        }
    }

    private int indexOfCase(
            List<PublicSmallDatasetSupport.EvalCase> cases, String caseId) {
        for (int index = 0; index < cases.size(); index++) {
            if (cases.get(index).caseId().equals(caseId)) {
                return index;
            }
        }
        throw new IllegalStateException("Unknown caseId " + caseId);
    }

    private String metricSummary(CaseRetrievalMetrics metrics) {
        return "R=" + format(metrics.recallAtK())
                + ", AR=" + format(metrics.acceptableRecallAtK())
                + ", MRR=" + format(metrics.reciprocalRank())
                + ", AMRR=" + format(metrics.acceptableReciprocalRank())
                + ", nDCG=" + format(metrics.ndcgAtK())
                + ", AnDCG=" + format(metrics.acceptableNdcgAtK())
                + ", Src=" + format(metrics.sourceCoverageAtK())
                + ", Groups=" + format(metrics.requiredEvidenceGroupCoverageAtK())
                + ", UFP=" + format(metrics.unanswerableFalsePositive());
    }

    private void writeMarkdownReport(String report) throws IOException {
        String configured = System.getProperty("rag.public.small.output", "").trim();
        if (configured.isEmpty()) {
            return;
        }
        Path output = Path.of(configured).toAbsolutePath().normalize();
        if (output.getParent() != null) {
            Files.createDirectories(output.getParent());
        }
        Files.writeString(output, report, StandardCharsets.UTF_8);
    }

    private PublicSmallDatasetSupport.Dataset loadDataset() {
        Path root = Path.of(System.getProperty(
                "rag.public.small.datasetDir",
                "backend/evals/datasets/public-small-v1"));
        return PublicSmallDatasetSupport.loadAndValidate(root);
    }

    private List<Double> requireVector(
            PublicSmallDatasetSupport.Dataset dataset, String key) {
        List<Double> vector = dataset.embeddings().get(key);
        if (vector == null) {
            throw new IllegalStateException("Missing frozen vector " + key);
        }
        return vector;
    }

    private double cosine(List<Double> left, List<Double> right) {
        if (left.size() != right.size() || left.isEmpty()) {
            throw new IllegalArgumentException("cosine vectors must have equal dimensions");
        }
        double dot = 0.0;
        double leftNorm = 0.0;
        double rightNorm = 0.0;
        for (int index = 0; index < left.size(); index++) {
            dot += left.get(index) * right.get(index);
            leftNorm += left.get(index) * left.get(index);
            rightNorm += right.get(index) * right.get(index);
        }
        if (leftNorm == 0.0 || rightNorm == 0.0) {
            throw new IllegalArgumentException("cosine vectors must be non-zero");
        }
        return dot / Math.sqrt(leftNorm * rightNorm);
    }

    private List<Long> chunkIds(List<RetrievedChunk> chunks) {
        return chunks.stream().map(RetrievedChunk::chunkId).toList();
    }

    private String ids(List<Long> values) {
        return values.toString().replace(" ", "");
    }

    private String escape(String value) {
        return value.replace("|", "\\|").replace("\n", " ");
    }

    private String format(double value) {
        synchronized (DECIMAL) {
            return DECIMAL.format(value);
        }
    }

    private record VariantCase(
            String caseId,
            List<Long> candidateChunkIds,
            List<Long> rankedChunkIds,
            CaseRetrievalMetrics metrics) {
        VariantCase {
            candidateChunkIds = List.copyOf(candidateChunkIds);
            rankedChunkIds = List.copyOf(rankedChunkIds);
        }
    }

    private record Variant(
            String schemeId,
            Map<String, String> configuration,
            List<VariantCase> cases,
            RetrievalEvalReport report) {
        Variant {
            configuration = Collections.unmodifiableMap(
                    new LinkedHashMap<>(configuration));
            cases = List.copyOf(cases);
        }
    }

    private record Comparison(
            PublicSmallDatasetSupport.Dataset dataset,
            Variant schemeA,
            Variant schemeB,
            List<String> improvedCaseIds,
            List<String> regressedCaseIds,
            List<String> orderChangedCaseIds) {
        Comparison {
            improvedCaseIds = List.copyOf(improvedCaseIds);
            regressedCaseIds = List.copyOf(regressedCaseIds);
            orderChangedCaseIds = List.copyOf(orderChangedCaseIds);
        }
    }

    private enum Change {
        IMPROVED("improved"),
        REGRESSED("regressed"),
        UNCHANGED("unchanged");

        private final String label;

        Change(String label) {
            this.label = label;
        }

        String label() {
            return label;
        }
    }
}
