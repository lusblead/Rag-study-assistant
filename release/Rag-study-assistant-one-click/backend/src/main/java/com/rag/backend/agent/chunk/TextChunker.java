package com.rag.backend.agent.chunk;

import com.rag.backend.agent.model.ParsedDocument;
import com.rag.backend.agent.model.TextChunk;

import java.util.List;

public interface TextChunker {
    List<TextChunk> chunk(ParsedDocument document,int chunkSize,int overlap);
}
