package com.rag.backend;

import com.rag.backend.agent.chat.RagChatService;
import com.rag.backend.agent.chunk.FixedWindowTextChunker;
import com.rag.backend.agent.chunk.TextCleaner;
import com.rag.backend.agent.embedding.MockEmbeddingClient;
import com.rag.backend.agent.ingest.DocumentIngestService;
import com.rag.backend.agent.llm.MockChatClient;
import com.rag.backend.agent.model.KnowledgeChunk;
import com.rag.backend.agent.model.RagChatResponse;
import com.rag.backend.agent.parse.DocumentParserFactory;
import com.rag.backend.agent.parse.TxtDocumentParser;
import com.rag.backend.agent.prompt.RagPromptTemplate;
import com.rag.backend.agent.repository.KnowledgeChunkRepository;
import com.rag.backend.agent.retrieval.MilvusKnowledgeRetriever;
import com.rag.backend.agent.vector.MockVectorStoreService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackendApplicationTests {

    @Test
    void txtMinimumLoopIngestsRetrievesAndAnswers(@TempDir Path tempDir) throws Exception {
        Path txtFile = tempDir.resolve("rag-notes.txt");
        Files.writeString(txtFile, """
                RAG means retrieval augmented generation.
                A study assistant first retrieves course notes, then asks an LLM to answer with references.
                Java streams support map, filter, and collect operations.
                """, StandardCharsets.UTF_8);

        InMemoryKnowledgeChunkRepository repository = new InMemoryKnowledgeChunkRepository();
        MockEmbeddingClient embeddingClient = new MockEmbeddingClient();
        MockVectorStoreService vectorStoreService = new MockVectorStoreService();

        DocumentIngestService ingestService = new DocumentIngestService(
                new DocumentParserFactory(List.of(new TxtDocumentParser())),
                new FixedWindowTextChunker(new TextCleaner()),
                repository,
                embeddingClient,
                vectorStoreService
        );

        int chunkCount = ingestService.ingest(1L, 10L, txtFile, "txt");

        assertEquals(1, chunkCount);
        KnowledgeChunk savedChunk = repository.firstChunk();
        assertNotNull(savedChunk);
        assertEquals(KnowledgeChunk.STATUS_DONE, savedChunk.getEmbeddingStatus());
        assertNotNull(savedChunk.getMilvusVectorId());
        assertTrue(savedChunk.getContent().contains("retrieval augmented generation"));

        MilvusKnowledgeRetriever retriever = new MilvusKnowledgeRetriever(
                embeddingClient,
                vectorStoreService,
                repository
        );
        RagChatService chatService = new RagChatService(
                retriever,
                new RagPromptTemplate(),
                new MockChatClient(),
                5
        );

        RagChatResponse response = chatService.chat(1L, "What does RAG mean?");

        assertNotNull(response.answer());
        assertFalse(response.answer().isBlank());
        assertFalse(response.references().isEmpty());
        assertEquals(savedChunk.getId(), response.references().get(0).chunkId());
        assertTrue(response.references().get(0).content().contains("study assistant"));
    }

    private static final class InMemoryKnowledgeChunkRepository implements KnowledgeChunkRepository {
        private final AtomicLong ids = new AtomicLong(0);
        private final Map<Long, KnowledgeChunk> chunks = new ConcurrentHashMap<>();

        @Override
        public KnowledgeChunk save(KnowledgeChunk chunk) {
            chunk.setId(ids.incrementAndGet());
            chunks.put(chunk.getId(), chunk);
            return chunk;
        }

        @Override
        public KnowledgeChunk findById(long id) {
            return chunks.get(id);
        }

        @Override
        public void deleteByDocumentId(long documentId) {
            chunks.values().removeIf(chunk -> documentId == chunk.getDocumentId());
        }

        @Override
        public void updateVectorStatus(Long chunkId, String milvusVectorId, String embeddingStatus) {
            KnowledgeChunk chunk = chunks.get(chunkId);
            chunk.setMilvusVectorId(milvusVectorId);
            chunk.setEmbeddingStatus(embeddingStatus);
        }

        private KnowledgeChunk firstChunk() {
            return chunks.values().stream()
                    .min(Comparator.comparing(KnowledgeChunk::getId))
                    .orElse(null);
        }
    }
}
