// 测试使用计数 Parser 与临时目录，证明第二次调用读取同一解析制品而不会再次解析。
package com.rag.backend.ingestionlab.artifact;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.backend.agent.model.PageText;
import com.rag.backend.agent.model.ParsedDocument;
import com.rag.backend.agent.parse.DocumentParser;
import com.rag.backend.agent.parse.DocumentParserFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

// 验证同输入复用、不同 sourceHash 拒绝复用，并观察真实 Artifact 文件行为。
class ReplayableParseStageTest {

    @Test
        // 验证重放读取制品而不重复昂贵调用。
    void replayUsesVerifiedSnapshotInsteadOfParsingAgain(@TempDir Path dir) {
        AtomicInteger calls = new AtomicInteger();
        DocumentParser parser = new DocumentParser() {
            @Override public boolean supports(String type) { return "pdf".equals(type); }
            @Override public ParsedDocument parse(Path path) {
                calls.incrementAndGet();
                return new ParsedDocument("demo", "page one",
                        List.of(new PageText(1, "page one")));
            }
        };
        ReplayableParseStage stage = new ReplayableParseStage(
                new DocumentParserFactory(List.of(parser)),
                new FileArtifactStore(dir.resolve("artifacts")),
                new ObjectMapper());

        var first = stage.execute(9L, dir.resolve("source.pdf"), "pdf",
                "source-hash", "pipeline-hash");
        var replay = stage.execute(9L, dir.resolve("source.pdf"), "pdf",
                "source-hash", "pipeline-hash");

        assertFalse(first.replayed());
        assertTrue(replay.replayed());
        assertEquals(1, calls.get());
        assertEquals(first.outputDigest(), replay.outputDigest());
    }

    @Test
        // 验证步骤同输入复用、不同输入拒绝覆盖。
    void refusesToReuseSnapshotForDifferentInput(@TempDir Path dir) {
        DocumentParser parser = new DocumentParser() {
            @Override public boolean supports(String type) { return true; }
            @Override public ParsedDocument parse(Path path) {
                return new ParsedDocument("demo", "text", List.of());
            }
        };
        ReplayableParseStage stage = new ReplayableParseStage(
                new DocumentParserFactory(List.of(parser)),
                new FileArtifactStore(dir), new ObjectMapper());

        stage.execute(9L, dir.resolve("a.pdf"), "pdf", "hash-a", "pipeline");
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> stage.execute(9L, dir.resolve("b.pdf"), "pdf",
                        "hash-b", "pipeline"));
        assertTrue(error.getMessage().contains("input mismatch"));
    }
}