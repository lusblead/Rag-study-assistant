// ID 算法：稳定哈希版本、chunk 业务键和模型，再截成非负 Int64。
package com.rag.backend.ingestionlab.vector;

import com.rag.backend.ingestionlab.identity.StableHash;

import java.nio.ByteBuffer;
import java.util.HexFormat;

// 确定性向量 ID：由版本、业务键和模型生成稳定 Long。
public final class DeterministicVectorId {
    // 该类只有稳定 ID 算法，禁止实例化可避免调用方误以为对象内部保存序列状态。
    private DeterministicVectorId() { }

    // 稳定哈希后截为 Milvus 可接受的正 Long。
    public static long from(long versionId, String chunkBusinessKey,
                            String embeddingModel, int dimension) {
        String canonical = versionId + "\n" + chunkBusinessKey + "\n"
                + embeddingModel + "\n" + dimension;
        byte[] hash = HexFormat.of().parseHex(StableHash.sha256(canonical));
        long value = ByteBuffer.wrap(hash, 0, Long.BYTES).getLong() & Long.MAX_VALUE;
        return value == 0 ? 1 : value;
    }
}