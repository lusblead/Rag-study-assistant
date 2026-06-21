package com.rag.backend.agent.ingest;

import com.rag.backend.agent.chunk.TextChunker;
import com.rag.backend.agent.embedding.EmbeddingClient;
import com.rag.backend.agent.model.KnowledgeChunk;
import com.rag.backend.agent.model.ParsedDocument;
import com.rag.backend.agent.model.TextChunk;
import com.rag.backend.agent.parse.DocumentParser;
import com.rag.backend.agent.parse.DocumentParserFactory;
import com.rag.backend.agent.repository.KnowledgeChunkRepository;
import com.rag.backend.agent.vector.VectorStoreService;
import com.rag.backend.common.BizException;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.List;

@Service
public class DocumentIngestService {
    private final DocumentParserFactory parserFactory;
    private final TextChunker textChunker;
    private final KnowledgeChunkRepository chunkRepository;
    private final EmbeddingClient embeddingClient;
    private final VectorStoreService vectorStoreService;

    public DocumentIngestService(DocumentParserFactory parserFactory,
                                 TextChunker textChunker,
                                 KnowledgeChunkRepository chunkRepository,
                                 EmbeddingClient embeddingClient,
                                 VectorStoreService vectorStoreService) {
        this.parserFactory = parserFactory;
        this.textChunker = textChunker;
        this.chunkRepository = chunkRepository;
        this.embeddingClient = embeddingClient;
        this.vectorStoreService = vectorStoreService;
    }

    public int ingest(Long courseId, Long documentId, Path filePath, String fileType) {
        DocumentParser parser = parserFactory.getParser(fileType);
        ParsedDocument parsed = parser.parse(filePath);
        List<TextChunk> chunks = textChunker.chunk(parsed, 800, 120);

        for (TextChunk chunk : chunks) {
            KnowledgeChunk entity = new KnowledgeChunk();
            entity.setCourseId(courseId);
            entity.setDocumentId(documentId);
            entity.setChunkIndex(chunk.index());
            entity.setTitle(chunk.title());
            entity.setContent(chunk.content());
            entity.setSourcePage(chunk.sourcePage());
            entity.setTokenCount(chunk.tokenCount());
            entity.setEmbeddingStatus(KnowledgeChunk.STATUS_PENDING);

            KnowledgeChunk saved = chunkRepository.save(entity);

            try {
                List<Double> embedding = embeddingClient.embed(chunk.content());
                String vectorId = vectorStoreService.upsert(saved, embedding);
                chunkRepository.updateVectorStatus(saved.getId(), vectorId, KnowledgeChunk.STATUS_DONE);
            } catch (Exception e) {
                chunkRepository.updateVectorStatus(saved.getId(), null, KnowledgeChunk.STATUS_FAILED);
                throw new BizException(500, "Document chunk embedding failed, chunkId="
                        + saved.getId() + ": " + e.getMessage());
            }
        }

        return chunks.size();
    }
}
