// Stage 目的：先固定 MySQL Chunk 与 vectorId，再调用 Embedding/Milvus，最后提交关系库完成状态。
package com.rag.backend.ingestionlab.vector;

import com.rag.backend.agent.embedding.EmbeddingClient;

import java.util.List;
import java.util.Optional;

// VectorWriteStage 承载单个 Chunk 的跨存储写协议；它由 Step Orchestrator 调用，不是测试类。
public final class VectorWriteStage {
    // 按业务键 get-or-create，重放不新增行。
    private final ChunkWriteRepository chunks;
    // 外部依赖，超时不等于服务端未完成。
    private final EmbeddingClient embeddingClient;
    // 支持指定 ID upsert，重复写才能收敛。
    private final ConsistentVectorStore vectors;
    // 参与身份与审计，换模型不能复用旧向量。
    private final String embeddingModel;
    private final int dimension;

    // 构造时固定 Chunk 仓库、Embedding 端口、向量端口以及参与身份计算的模型和维度。
    public VectorWriteStage(ChunkWriteRepository chunks,
                            EmbeddingClient embeddingClient,
                            ConsistentVectorStore vectors,
                            String embeddingModel, int dimension) {
        this.chunks = chunks;
        this.embeddingClient = embeddingClient;
        this.vectors = vectors;
        this.embeddingModel = embeddingModel;
        this.dimension = dimension;
    }

    // 先固定身份再做远端副作用，重放复用同一 vectorId。
    public Result write(ChunkWriteRepository.ChunkDraft draft) {
        ChunkWriteRepository.ChunkRow row = chunks.getOrCreate(draft);
        long vectorId = DeterministicVectorId.from(draft.documentVersionId(),
                draft.businessKey(), embeddingModel, dimension);

        // 先在 MySQL 预留/核对完整身份，64 位碰撞在调用 Milvus 前失败。
        chunks.reserveVectorIdentity(
                row.id(), vectorId, embeddingModel, dimension);
        //查远端的Milvus是否已经有了这个vectorId
        Optional<ConsistentVectorStore.VectorMetadata> remote =
                vectors.find(vectorId);
        if (remote.isPresent() && !matches(
                remote.get(), row.id(), draft, vectorId)) {
            throw new PermanentVectorException("VECTOR_ID_COLLISION");
        }
        //快速返回，已经完成的不做第二遍
        if ("DONE".equals(row.status())
                && row.vectorId() != null && row.vectorId() == vectorId
                && remote.isPresent()) {
            return new Result(row.id(), vectorId, true);
        }

        try {
            List<Double> embedding = embeddingClient.embed(draft.content());
            if (embedding.size() != dimension) {
                throw new PermanentVectorException("EMBED_DIMENSION_MISMATCH");
            }
            // 使用确定性 ID，响应丢失后重试仍覆盖同一向量。
            vectors.upsert(new ConsistentVectorStore.VectorRecord(
                    vectorId, row.id(), draft.courseId(), draft.documentId(),
                    draft.documentVersionId(), draft.businessKey(),
                    draft.contentHash(), embeddingModel, dimension, embedding));
            chunks.markDone(row.id(), vectorId, embeddingModel);
            return new Result(row.id(), vectorId, false);
        } catch (RuntimeException error) {
            chunks.markFailed(row.id(), classify(error));
            throw error;
        }
    }

    private boolean matches(ConsistentVectorStore.VectorMetadata actual,
                            long mysqlChunkId,
                            ChunkWriteRepository.ChunkDraft draft,
                            long vectorId) {
        // 裸 exists 不能证明归属；逐项核对重放依赖的完整业务身份。
        return actual.vectorId() == vectorId
                && actual.mysqlChunkId() == mysqlChunkId
                && actual.documentVersionId() == draft.documentVersionId()
                && actual.chunkBusinessKey().equals(draft.businessKey())
                && actual.contentHash().equals(draft.contentHash())
                && actual.embeddingModel().equals(embeddingModel)
                && actual.dimension() == dimension;
    }

    // 委托 VectorFailureClassifier 按 SDK 稳定类型/状态码分类，不再把多数未知异常归为 TRANSIENT。
    private String classify(RuntimeException error) {
        VectorFailureClassifier.VectorFailure failure =
                VectorFailureClassifier.classify(error);
        return failure.errorCode();
    }

    // Result 关联 MySQL Chunk 与确定性 vectorId，并告诉编排器本次是否直接复用了已收敛结果。
    public record Result(long mysqlChunkId, long vectorId, boolean replayed) { }
    // PermanentVectorException：稳定失败类型，上层不解析异常文案。
    public static final class PermanentVectorException extends RuntimeException {
        private final String code;

        public PermanentVectorException(String code) {
            super("Permanent vector failure: " + code);
            this.code = code;
        }

        // 机器策略读取稳定 code，不能从 getMessage() 的人类文案反向解析。
        public String code() { return code; }
    }
    // 请求已发出，但调用方无法确认 Milvus 是否提交。
    // 真实 Adapter 只在结果确实未知时使用；重放必须继续使用同一个 vectorId。
    public static final class VectorWriteUnknownException extends RuntimeException {
        public VectorWriteUnknownException(String message) { super(message); }
    }
}