package com.rag.backend.agent.model;

import java.util.List;

// 表示文档解析后的标题和分页文本。
public record ParsedDocument(String title, String content, List<PageText> pages) {
}
