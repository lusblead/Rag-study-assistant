// 指纹目的：配置先按键排序再哈希，相同语义不受 Map 插入顺序影响。
package com.rag.backend.ingestionlab.identity;

import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

// 管线指纹：规范化配置后哈希，表示处理策略身份。
public record PipelineFingerprint(String value) {
    public PipelineFingerprint {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("fingerprint must be lowercase SHA-256");
        }
    }

    // 按 key 排序再哈希，使身份不受 Map 插入顺序影响。
    public static PipelineFingerprint from(Map<String, ?> settings) {
        String canonical = new TreeMap<>(settings).entrySet().stream()
                .map(entry -> entry.getKey() + "=" + String.valueOf(entry.getValue()))
                .collect(Collectors.joining("\n"));
        return new PipelineFingerprint(StableHash.sha256(canonical));
    }
}