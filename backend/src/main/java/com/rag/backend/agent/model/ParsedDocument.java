package com.rag.backend.agent.model;

public record ParsedDocument(String title,String content,List<PageText> pages) {
}
