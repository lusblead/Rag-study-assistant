// 建立跨进程、跨重试稳定的业务身份。
package com.rag.backend.ingestionlab.identity;

import com.rag.backend.agent.model.TextChunk;

// chunk 业务键：用稳定位置和内容摘要识别同一逻辑块。
public record ChunkBusinessKey(String value, String contentHash) {
    // 只用稳定业务属性，自增 id 不参与幂等身份。
    public static ChunkBusinessKey from(TextChunk chunk) {
        String contentHash = StableHash.sha256(chunk.content());
        String page = chunk.sourcePage() == null ? "na" : chunk.sourcePage().toString();
        String value = "%s:%06d:%s".formatted(
                page, chunk.index(), contentHash.substring(0, 20));
        return new ChunkBusinessKey(value, contentHash);
    }
}