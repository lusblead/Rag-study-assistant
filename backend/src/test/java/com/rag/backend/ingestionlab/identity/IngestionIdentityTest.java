// 建立跨进程、跨重试稳定的业务身份。
package com.rag.backend.ingestionlab.identity;

import com.rag.backend.agent.model.TextChunk;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

// 证明内容哈希和管线指纹跨顺序稳定，同时对关键输入变化敏感。
class IngestionIdentityTest {

    @Test
        // 验证稳定身份对相同输入一致、对关键变化敏感。
    void sameBytesHaveSameHashAndOneByteChangeDoesNot(@TempDir Path dir) throws Exception {
        Path a = dir.resolve("a.txt");
        Path b = dir.resolve("b.txt");
        Files.writeString(a, "RAG");
        Files.writeString(b, "RAG");
        assertEquals(StableHash.sha256(a), StableHash.sha256(b));

        Files.writeString(b, "RAG!");
        assertNotEquals(StableHash.sha256(a), StableHash.sha256(b));
    }

    @Test
        // 验证稳定身份对相同输入一致、对关键变化敏感。
    void fingerprintDoesNotDependOnMapInsertionOrder() {
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("model", "bge-m3");
        first.put("dimension", 1024);
        Map<String, Object> second = new LinkedHashMap<>();
        second.put("dimension", 1024);
        second.put("model", "bge-m3");

        assertEquals(PipelineFingerprint.from(first), PipelineFingerprint.from(second));
    }

    @Test
        // 验证稳定身份对相同输入一致、对关键变化敏感。
    void changingOnePipelineParameterCreatesANewIdentity() {
        PipelineFingerprint v1 = PipelineFingerprint.from(Map.of(
                "model", "bge-m3", "overlap", 120));
        PipelineFingerprint v2 = PipelineFingerprint.from(Map.of(
                "model", "bge-m3", "overlap", 80));
        assertNotEquals(v1, v2);
    }

    @Test
        // 验证稳定身份对相同输入一致、对关键变化敏感。 验证重放读取制品而不重复昂贵调用。
    void replayCreatesTheSameChunkBusinessKey() {
        TextChunk firstRun = new TextChunk(7, "title", "same content", 3, 12);
        TextChunk replay = new TextChunk(7, "title", "same content", 3, 12);
        assertEquals(ChunkBusinessKey.from(firstRun), ChunkBusinessKey.from(replay));
    }
}