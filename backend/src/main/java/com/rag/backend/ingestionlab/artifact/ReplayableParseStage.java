// Stage 目的：先核验已有解析制品的输入身份和摘要，只有未命中时才调用当前 Parser。
package com.rag.backend.ingestionlab.artifact;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.backend.agent.parse.DocumentParser;
import com.rag.backend.agent.parse.DocumentParserFactory;
import com.rag.backend.ingestionlab.identity.StableHash;

import java.nio.file.Path;

// 可重放解析：命中先校验，未命中才真正解析。
public final class ReplayableParseStage {
    // 解析/OCR 是昂贵且可能非确定的依赖。
    private final DocumentParserFactory parserFactory;
    // 保存可重放输出，命中决定是否重新执行。
    private final ArtifactStore store;
    private final ObjectMapper objectMapper;

    // ParserFactory 产生真实解析结果，ArtifactStore 持久化，ObjectMapper 负责同一 Envelope 的编解码。
    public ReplayableParseStage(DocumentParserFactory parserFactory,
                                ArtifactStore store,
                                ObjectMapper objectMapper) {
        this.parserFactory = parserFactory;
        this.store = store;
        this.objectMapper = objectMapper;
    }

    // 命中制品就校验复用，未命中才运行阶段。
    public Result execute(long documentVersionId,
                          Path source,
                          String fileType,
                          String sourceHash,
                          String pipelineFingerprint) {
        String key = documentVersionId + "/parsed.json";
        // Replay 命中仍要核对输入身份与输出摘要。
        if (store.exists(key)) {
            Envelope envelope = decode(store.read(key));
            requireSameInput(envelope, sourceHash, pipelineFingerprint);
            requireSnapshotDigest(envelope);
            return new Result(key, envelope.snapshotHash(), envelope.snapshot(), true);
        }

        DocumentParser parser = parserFactory.getParser(fileType);
        ParseSnapshot snapshot = ParseSnapshot.from(parser.parse(source));
        String snapshotHash = StableHash.sha256(encode(snapshot));
        Envelope envelope = new Envelope(sourceHash, pipelineFingerprint,
                snapshotHash, snapshot);
        store.writeAtomically(key, encodeBytes(envelope));
        return new Result(key, snapshotHash, snapshot, false);
    }

    // 核对输入身份或输出摘要，冲突立即拒绝重放。
    private void requireSameInput(Envelope envelope, String sourceHash, String fingerprint) {
        if (!envelope.sourceHash().equals(sourceHash)
                || !envelope.pipelineFingerprint().equals(fingerprint)) {
            throw new IllegalStateException("Artifact input mismatch; create a new version");
        }
    }

    // 核对输入身份或输出摘要，冲突立即拒绝重放。
    private void requireSnapshotDigest(Envelope envelope) {
        String actual = StableHash.sha256(encode(envelope.snapshot()));
        if (!actual.equals(envelope.snapshotHash())) {
            throw new IllegalStateException("Corrupted parse artifact");
        }
    }

    private byte[] encodeBytes(Object value) {
        try { return objectMapper.writeValueAsBytes(value); }
        // 序列化失败表示本次制品尚未提交；保留 Jackson cause 供上层归类并重试 Parse Step。
        catch (Exception e) { throw new IllegalStateException("Cannot encode artifact", e); }
    }

    private String encode(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        // 计算摘要前必须使用同一个 ObjectMapper 序列化规则；失败时不能生成缺少 digest 的制品。
        catch (Exception e) { throw new IllegalStateException("Cannot encode artifact", e); }
    }

    private Envelope decode(byte[] bytes) {
        try { return objectMapper.readValue(bytes, Envelope.class); }
        // 读取到无法反序列化的 JSON 表示制品损坏或 schema 不兼容，Stage 必须拒绝复用。
        catch (Exception e) { throw new IllegalStateException("Cannot decode artifact", e); }
    }

    // Envelope 把源内容身份、管线身份、输出摘要和解析负载绑定在一起，重放时四项共同核验。
    public record Envelope(String sourceHash, String pipelineFingerprint,
                           String snapshotHash, ParseSnapshot snapshot) { }
    // Result 把 Step 需要持久化的制品引用和摘要连同已核验快照返回给下游 Chunk Stage。
    public record Result(String artifactKey, String outputDigest,
                         ParseSnapshot snapshot, boolean replayed) { }
}