package com.rag.backend.agent.model;

import com.rag.backend.agent.retrieval.RetrievedChunk;

import java.util.List;
import java.util.stream.Collectors;

public record RagPromptContext(String question, List<RetrievedChunk> chunks) {
    public String referencesText(){
        return chunks.stream()
                .map(chunk->"来源片段ID："+ chunk.chunkId()+"\n"+chunk.content())
                .collect(Collectors.joining("\n\n"));
    }
}
