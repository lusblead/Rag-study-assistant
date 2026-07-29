package com.rag.backend.agent.chunk;

import com.rag.backend.agent.model.ParsedDocument;
import com.rag.backend.agent.model.TextChunk;

import java.util.List;

// 把 ParsedDocument 确定性切成有序片段；只负责窗口与重叠，不负责 Embedding 或持久化。
public interface TextChunker {
    List<TextChunk> chunk(ParsedDocument document,int chunkSize,int overlap);
}
