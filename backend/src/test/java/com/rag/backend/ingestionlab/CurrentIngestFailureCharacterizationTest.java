// 固定旧实现的部分成功与重复插入，作为改造前证据。
package com.rag.backend.ingestionlab;

import com.rag.backend.agent.chunk.TextChunker;
import com.rag.backend.agent.embedding.EmbeddingClient;
import com.rag.backend.agent.ingest.DocumentIngestService;
import com.rag.backend.agent.model.KnowledgeChunk;
import com.rag.backend.agent.model.ParsedDocument;
import com.rag.backend.agent.model.TextChunk;
import com.rag.backend.agent.parse.DocumentParser;
import com.rag.backend.agent.parse.DocumentParserFactory;
import com.rag.backend.agent.repository.KnowledgeChunkRepository;
import com.rag.backend.agent.vector.VectorStoreService;
import com.rag.backend.agent.model.VectorSearchResult;
import com.rag.backend.common.BizException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

// 通过真实 DocumentIngestService 和可控外部端口，固定当前同步链的部分成功与重复写入语义。
class CurrentIngestFailureCharacterizationTest {

    @Test
        // 复现部分成功或重复写，固定旧链路风险。 故障窗口崩溃后重放，检查三条核心不变量。
    void secondEmbeddingFailureLeavesAPartialMysqlResult(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("two-chunks.txt");
        Files.writeString(file, "content is irrelevant because the chunker is controlled");

        InMemoryRepository mysql = new InMemoryRepository();
        RecordingVectorStore milvus = new RecordingVectorStore();
        AtomicInteger embeddingCalls = new AtomicInteger();
        EmbeddingClient embedding = text -> {
            if (embeddingCalls.incrementAndGet() == 2) {
                throw new IllegalStateException("injected embedding outage");
            }
            return List.of(1.0, 0.0);
        };

        DocumentParser parser = new DocumentParser() {
            @Override public boolean supports(String fileType) { return true; }
            @Override public ParsedDocument parse(Path path) {
                return new ParsedDocument("demo", "demo", List.of());
            }
        };
        TextChunker twoChunks = (document, size, overlap) -> List.of(
                new TextChunk(0, "demo", "first", 1, 5),
                new TextChunk(1, "demo", "second", 1, 6)
        );

        DocumentIngestService service = new DocumentIngestService(
                new DocumentParserFactory(List.of(parser)),
                twoChunks,
                mysql,
                embedding,
                milvus
        );

        BizException error = assertThrows(BizException.class,
                () -> service.ingest(1L, 10L, file, "txt"));

        assertTrue(error.getMessage().contains("embedding failed"));
        assertEquals(2, mysql.rows.size(), "两个 chunk 都已经插入 MySQL");
        assertEquals(KnowledgeChunk.STATUS_DONE, mysql.rows.get(1L).getEmbeddingStatus());
        assertEquals(KnowledgeChunk.STATUS_FAILED, mysql.rows.get(2L).getEmbeddingStatus());
        assertEquals(List.of(1L), milvus.upsertedIds,
                "只有第一个向量成功；这就是可观察到的部分状态");
    }

    @Test
        // 复现部分成功或重复写，固定旧链路风险。 验证重放读取制品而不重复昂贵调用。
    void replayingCurrentMethodCreatesNewMysqlRows() throws Exception {
        InMemoryRepository mysql = new InMemoryRepository();
        RecordingVectorStore milvus = new RecordingVectorStore();
        DocumentParser parser = new DocumentParser() {
            @Override public boolean supports(String fileType) { return true; }
            @Override public ParsedDocument parse(Path path) {
                return new ParsedDocument("demo", "demo", List.of());
            }
        };
        TextChunker oneChunk = (document, size, overlap) -> List.of(
                new TextChunk(0, "demo", "same logical chunk", 1, 18));
        DocumentIngestService service = new DocumentIngestService(
                new DocumentParserFactory(List.of(parser)), oneChunk, mysql,
                text -> List.of(1.0, 0.0), milvus);

        Path fake = Path.of("not-read-by-controlled-parser.txt");
        service.ingest(1L, 10L, fake, "txt");
        service.ingest(1L, 10L, fake, "txt");

        assertEquals(2, mysql.rows.size(),
                "当前知识片段表没有 (version,businessKey) 唯一约束");
        assertNotEquals(mysql.rows.get(1L).getId(), mysql.rows.get(2L).getId());
    }

    // Fake MySQL 仓库：分配自增 ID 并保存每个 Chunk 的状态，让断言能观察失败前已提交的关系库事实。
    private static final class InMemoryRepository implements KnowledgeChunkRepository {
        private long nextId;
        private final Map<Long, KnowledgeChunk> rows = new LinkedHashMap<>();

        @Override public KnowledgeChunk save(KnowledgeChunk chunk) {
            chunk.setId(++nextId);
            rows.put(chunk.getId(), chunk);
            return chunk;
        }
        @Override public KnowledgeChunk findById(long id) { return rows.get(id); }
        @Override public List<KnowledgeChunk> findByCourseId(long courseId, int limit) {
            return rows.values().stream()
                    .filter(c -> c.getCourseId().equals(courseId))
                    .sorted(Comparator.comparing(KnowledgeChunk::getId))
                    .limit(limit).toList();
        }
        @Override public void deleteByDocumentId(long documentId) {
            rows.values().removeIf(c -> c.getDocumentId().equals(documentId));
        }
        @Override public void deleteByCourseId(long courseId) {
            rows.values().removeIf(c -> c.getCourseId().equals(courseId));
        }
        @Override public void updateVectorStatus(Long id, String vectorId, String status) {
            KnowledgeChunk chunk = rows.get(id);
            chunk.setMilvusVectorId(vectorId);
            chunk.setEmbeddingStatus(status);
        }
    }

    // Fake 向量库：记录收到的 MySQL Chunk ID；与 InMemoryRepository 对照即可看见两个存储是否发生偏差。
    private static final class RecordingVectorStore implements VectorStoreService {
        private final List<Long> upsertedIds = new ArrayList<>();
        @Override public String upsert(KnowledgeChunk chunk, List<Double> embedding) {
            upsertedIds.add(chunk.getId());
            return chunk.getId().toString();
        }
        @Override public List<VectorSearchResult> search(Long courseId,
                                                         List<Double> vector,
                                                         int topK) {
            return List.of();
        }
        @Override public void deleteByDocumentId(Long documentId) { }
        @Override public void deleteByCourseId(Long courseId) { }
    }
}