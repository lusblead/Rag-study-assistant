package com.rag.backend.agent.model;

public record TextChunk(Integer index,String title,String content,
                        Integer sourcePage,
                        Integer tokenCount) {
}
