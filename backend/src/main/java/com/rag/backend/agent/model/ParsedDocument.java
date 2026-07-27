package com.rag.backend.agent.model;

import java.util.List;
import java.util.Objects;

// 表示文档解析后的结构化结果；保留 title/content/pages 以兼容旧调用方。
public record ParsedDocument(
        String title,
        String content,
        List<PageText> pages,
        String markdown,
        DocumentMetadata metadata,
        List<TextChunk> chunks,
        List<ParsedTable> tables,
        List<ParsedImage> images,
        OcrInfo ocrInfo,
        ParseLog parseLog
) {
    public ParsedDocument {
        title = Objects.toString(title, "");
        content = Objects.toString(content, "");
        pages = pages == null ? List.of() : List.copyOf(pages);
        markdown = Objects.toString(markdown, content);
        metadata = metadata == null ? DocumentMetadata.empty() : metadata;
        chunks = chunks == null ? List.of() : List.copyOf(chunks);
        tables = tables == null ? List.of() : List.copyOf(tables);
        images = images == null ? List.of() : List.copyOf(images);
        ocrInfo = ocrInfo == null ? OcrInfo.none(pages.size()) : ocrInfo;
        parseLog = parseLog == null ? ParseLog.empty() : parseLog;
    }

    public ParsedDocument(String title, String content, List<PageText> pages) {
        this(title, content, pages, content, DocumentMetadata.empty(), List.of(), List.of(), List.of(),
                OcrInfo.none(pages == null ? 0 : pages.size()), ParseLog.empty());
    }

    public String plainText() {
        return content;
    }
}
