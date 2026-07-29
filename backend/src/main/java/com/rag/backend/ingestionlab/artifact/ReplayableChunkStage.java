// Chunk Stage 目的：由已核验 ParseSnapshot 生成稳定 Chunk 列表，并把上游 digest 写入制品 Envelope。
package com.rag.backend.ingestionlab.artifact;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.backend.agent.chunk.TextChunker;
import com.rag.backend.agent.model.TextChunk;
import com.rag.backend.ingestionlab.identity.ChunkBusinessKey;
import com.rag.backend.ingestionlab.identity.StableHash;

import java.util.List;

// 可重放切块：固化 chunk、业务键和输出摘要。
public final class ReplayableChunkStage {
    private final TextChunker chunker;
    // 保存可重放输出，命中决定是否重新执行。
    private final ArtifactStore store;
    private final ObjectMapper objectMapper;

    // Chunker 产生业务输出，Store 负责提交，ObjectMapper 同时用于制品编码和输出摘要计算。
    public ReplayableChunkStage(TextChunker chunker, ArtifactStore store,
                                ObjectMapper objectMapper) {
        this.chunker = chunker;
        this.store = store;
        this.objectMapper = objectMapper;
    }

    // 命中制品就校验复用，未命中才运行阶段。
    public Result execute(long versionId, ParseSnapshot parsed,
                          String parseDigest, String pipelineFingerprint,
                          int chunkSize, int overlap) {
        String key = versionId + "/chunks-" + chunkSize + "-" + overlap + ".json";
        if (store.exists(key)) {
            ChunkEnvelope envelope = decode(store.read(key));
            // 同一个 key 只有在上游解析制品和管线语义完全相同时才允许复用。
            if (!parseDigest.equals(envelope.parseDigest())
                    || !pipelineFingerprint.equals(envelope.pipelineFingerprint())) {
                throw new IllegalStateException(
                        "Chunk artifact input mismatch; create a new version");
            }
            // 重新编码稳定 Chunk 列表并核对摘要，损坏文件不能作为 DONE 输出复用。
            String actualDigest = digest(envelope.chunks());
            if (!actualDigest.equals(envelope.outputDigest())) {
                throw new IllegalStateException("Corrupted chunk artifact");
            }
            return new Result(key, envelope.outputDigest(),
                    envelope.chunks(), true);
        }
        List<ChunkSnapshot> chunks = chunker
                .chunk(parsed.toParsedDocument(), chunkSize, overlap).stream()
                .map(ChunkSnapshot::from)
                .toList();
        String outputDigest = digest(chunks);
        ChunkEnvelope envelope = new ChunkEnvelope(parseDigest,
                pipelineFingerprint, outputDigest, chunks);
        store.writeAtomically(key, encode(envelope));
        return new Result(key, outputDigest, chunks, false);
    }

    private byte[] encode(Object value) {
        try { return objectMapper.writeValueAsBytes(value); }
        // Chunk 列表无法编码时不写制品；上层仍可用相同 Parse Artifact 安全重试本步骤。
        catch (Exception e) { throw new IllegalStateException("Cannot encode chunks", e); }
    }

    private ChunkEnvelope decode(byte[] bytes) {
        try {
            return objectMapper.readValue(bytes, ChunkEnvelope.class);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot decode chunks", e);
        }
    }

    // 输出摘要只覆盖稳定 Chunk 数据；Envelope 的输入字段单独比较，职责更清楚。
    private String digest(List<ChunkSnapshot> chunks) {
        try {
            return StableHash.sha256(objectMapper.writeValueAsString(chunks));
        } catch (Exception e) {
            throw new IllegalStateException("Cannot digest chunks", e);
        }
    }

    // ChunkSnapshot：切块阶段的不可变输出，附带业务键和内容摘要供重放。
    public record ChunkSnapshot(int index, String title, String content,
                                Integer sourcePage, int tokenCount,
                                String businessKey, String contentHash) {
        static ChunkSnapshot from(TextChunk chunk) {
            ChunkBusinessKey key = ChunkBusinessKey.from(chunk);
            return new ChunkSnapshot(chunk.index(), chunk.title(), chunk.content(),
                    chunk.sourcePage(), chunk.tokenCount(), key.value(), key.contentHash());
        }
    }
    // Envelope 同时保存上游输入身份和本阶段输出摘要，供跨进程重放核验。
    public record ChunkEnvelope(String parseDigest, String pipelineFingerprint,
                                String outputDigest,
                                List<ChunkSnapshot> chunks) { }
    // Result 向 StepExecutor 返回 Chunk 制品引用、摘要、稳定列表及是否复用，供后续 MySQL 写阶段消费。
    public record Result(String artifactKey, String outputDigest,
                         List<ChunkSnapshot> chunks, boolean replayed) { }
}