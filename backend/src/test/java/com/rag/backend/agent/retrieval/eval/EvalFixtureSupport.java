package com.rag.backend.agent.retrieval.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 评测夹具与数据集校验支持（test-support）。
 *
 * 职责：
 * 1. 校验公开合成 Fixture 的 manifest、SHA-256、文件存在性和规模；
 * 2. 校验外置本地 Dataset 的 manifest/checksum（挂载路径不进入版本库）；
 * 3. 提供缺少环境变量时的 fail-fast 守卫；
 * 4. 断言评测报告序列化结果不包含 API Key。
 */
final class EvalFixtureSupport {
    private static final ObjectMapper JSON = new ObjectMapper();

    private EvalFixtureSupport() {
    }

    static String sha256(Path path) {
        try {
            byte[] bytes = Files.readAllBytes(path);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("无法计算文件 SHA-256: " + path, e);
        }
    }

    /**
     * 校验公开合成 Fixture：manifest 可解析、corpus/cases/embeddings 存在、
     * SHA-256 匹配、规模匹配、维度合法。任何一项失败都会抛出异常。
     */
    static JsonNode validateFixtureManifest(Path fixtureDir) {
        Path manifestPath = fixtureDir.resolve("manifest.json");
        Path corpusPath = fixtureDir.resolve("corpus.jsonl");
        Path casesPath = fixtureDir.resolve("cases.jsonl");
        Path embeddingsPath = fixtureDir.resolve("embeddings.jsonl");
        requireRegularFile(manifestPath, "Fixture manifest");
        requireRegularFile(corpusPath, "Fixture corpus");
        requireRegularFile(casesPath, "Fixture cases");
        requireRegularFile(embeddingsPath, "Fixture embeddings");

        JsonNode manifest = readJson(manifestPath);
        if (!manifest.has("corpusSha256") || !manifest.has("caseSha256")
                || !manifest.has("expectedChunkCount") || !manifest.has("expectedCaseCount")
                || !manifest.has("embeddingDimension")) {
            throw new IllegalStateException("Fixture manifest 缺少必要字段: " + manifestPath);
        }
        String expectedCorpus = manifest.path("corpusSha256").asText();
        String actualCorpus = sha256(corpusPath);
        if (!expectedCorpus.equalsIgnoreCase(actualCorpus)) {
            throw new IllegalStateException("Fixture corpus SHA-256 不匹配: expected="
                    + expectedCorpus + ", actual=" + actualCorpus);
        }
        String expectedCases = manifest.path("caseSha256").asText();
        String actualCases = sha256(casesPath);
        if (!expectedCases.equalsIgnoreCase(actualCases)) {
            throw new IllegalStateException("Fixture cases SHA-256 不匹配: expected="
                    + expectedCases + ", actual=" + actualCases);
        }
        int expectedChunks = manifest.path("expectedChunkCount").asInt(-1);
        int expectedCasesCount = manifest.path("expectedCaseCount").asInt(-1);
        int expectedDimension = manifest.path("embeddingDimension").asInt(-1);
        if (expectedChunks <= 0 || expectedCasesCount <= 0 || expectedDimension <= 0) {
            throw new IllegalStateException("Fixture manifest 规模或维度非法");
        }
        long chunkLines = countLines(corpusPath);
        long caseLines = countLines(casesPath);
        if (chunkLines != expectedChunks) {
            throw new IllegalStateException("Fixture chunk 数量不匹配: expected="
                    + expectedChunks + ", actual=" + chunkLines);
        }
        if (caseLines != expectedCasesCount) {
            throw new IllegalStateException("Fixture case 数量不匹配: expected="
                    + expectedCasesCount + ", actual=" + caseLines);
        }
        return manifest;
    }

    /**
     * 校验外置本地 Dataset：manifest 存在、corpus/case 文件存在、SHA-256 匹配、
     * 规模匹配。任何一项失败都会抛出异常，不打印 Warning 后继续。
     */
    static JsonNode validateLocalDatasetManifest(Path datasetDir, Path manifestFile) {
        requireRegularFile(manifestFile, "Local Dataset manifest");
        JsonNode manifest = readJson(manifestFile);
        if (!manifest.has("corpusSha256") || !manifest.has("retrievalCaseSha256")
                || !manifest.has("expectedChunkCount") || !manifest.has("expectedCaseCount")) {
            throw new IllegalStateException("Local Dataset manifest 缺少必要字段: " + manifestFile);
        }
        Path corpusPath = datasetDir.resolve("corpus.jsonl");
        Path casePath = datasetDir.resolve(
                manifest.path("retrievalCaseFile").asText("standard_reviewed_100_retrieval.jsonl"));
        requireRegularFile(corpusPath, "Local Dataset corpus");
        requireRegularFile(casePath, "Local Dataset retrieval case");

        String expectedCorpus = manifest.path("corpusSha256").asText();
        String actualCorpus = sha256(corpusPath);
        if (!expectedCorpus.equalsIgnoreCase(actualCorpus)) {
            throw new IllegalStateException("Local Dataset corpus SHA-256 不匹配: expected="
                    + expectedCorpus + ", actual=" + actualCorpus);
        }
        String expectedCases = manifest.path("retrievalCaseSha256").asText();
        String actualCases = sha256(casePath);
        if (!expectedCases.equalsIgnoreCase(actualCases)) {
            throw new IllegalStateException("Local Dataset retrieval case SHA-256 不匹配: expected="
                    + expectedCases + ", actual=" + actualCases);
        }
        int expectedChunks = manifest.path("expectedChunkCount").asInt(-1);
        int expectedCasesCount = manifest.path("expectedCaseCount").asInt(-1);
        if (expectedChunks <= 0 || expectedCasesCount <= 0) {
            throw new IllegalStateException("Local Dataset manifest 规模非法");
        }
        long chunkLines = countLines(corpusPath);
        long caseLines = countLines(casePath);
        if (chunkLines != expectedChunks) {
            throw new IllegalStateException("Local Dataset chunk 数量不匹配: expected="
                    + expectedChunks + ", actual=" + chunkLines);
        }
        if (caseLines != expectedCasesCount) {
            throw new IllegalStateException("Local Dataset case 数量不匹配: expected="
                    + expectedCasesCount + ", actual=" + caseLines);
        }
        return manifest;
    }

    /** 缺少必要环境变量时立即失败，避免把 Key 缺失伪装成评测结果。 */
    static String requireNonBlankEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("缺少环境变量 " + name + "；请先配置后再运行");
        }
        return value.trim();
    }

    /** 报告序列化后不得包含真实 Key 值（字段名如 apiKeyConfigured 允许存在）。 */
    static void assertReportContainsNoApiKey(String serializedReport) {
        String lower = serializedReport.toLowerCase();
        if (lower.contains("sk-") || lower.contains("bearer ")) {
            throw new IllegalStateException("评测报告疑似包含 API Key 敏感字段");
        }
    }

    private static void requireRegularFile(Path path, String label) {
        if (!Files.isRegularFile(path)) {
            throw new IllegalStateException(label + " 不存在: " + path);
        }
    }

    private static JsonNode readJson(Path path) {
        try {
            return JSON.readTree(Files.readString(path, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("无法解析 JSON: " + path, e);
        }
    }

    private static long countLines(Path path) {
        try {
            return Files.readAllLines(path, StandardCharsets.UTF_8).stream()
                    .filter(line -> !line.isBlank())
                    .count();
        } catch (IOException e) {
            throw new IllegalStateException("无法读取文件: " + path, e);
        }
    }
}
