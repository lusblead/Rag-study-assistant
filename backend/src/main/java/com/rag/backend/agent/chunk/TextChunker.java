package com.rag.backend.agent.chunk;

import com.rag.backend.agent.model.ParsedDocument;
import com.rag.backend.agent.model.TextChunk;

import java.util.List;

// 定义文本切片器的统一接口。
public interface TextChunker {
    List<TextChunk> chunk(ParsedDocument document,int chunkSize,int overlap);
}
