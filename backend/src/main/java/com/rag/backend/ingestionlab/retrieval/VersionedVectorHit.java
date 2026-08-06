package com.rag.backend.ingestionlab.retrieval;

/** Milvus 候选同时返回 MySQL Chunk 身份和所属版本，供回表后二次校验。 */
public record VersionedVectorHit(
        long mysqlChunkId,
        long documentVersionId,
        double score) {
}
