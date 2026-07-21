package com.rag.backend.agent.model;

import java.util.List;

public record ParsedDocument(String title, String content, List<PageText> pages) {
}
