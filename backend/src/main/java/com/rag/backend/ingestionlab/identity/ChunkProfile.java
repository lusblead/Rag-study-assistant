package com.rag.backend.ingestionlab.identity;

/**
 * 服务端受控的固定窗口切块配置。
 *
 * <p>该不可变对象在管线清单提供者创建时完成校验，使非法配置在创建
 * DocumentVersion、Job 或 Outbox 之前失败。</p>
 */
public record ChunkProfile(int size, int overlap) {
    public ChunkProfile {
        if (size <= 0) {
            throw new IllegalArgumentException("chunk size must be positive");
        }
        if (overlap < 0 || overlap >= size) {
            throw new IllegalArgumentException(
                    "chunk overlap must be non-negative and less than chunk size");
        }
    }
}
