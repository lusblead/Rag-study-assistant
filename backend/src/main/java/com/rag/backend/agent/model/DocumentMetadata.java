package com.rag.backend.agent.model;

import java.util.Objects;

// 文档级元数据，用于后续知识库索引与检索过滤。
public record DocumentMetadata(
        String title,
        String author,
        int pageCount,
        String creationTime,
        String modificationTime,
        long fileSize,
        String language,
        String source,
        String hash
) {
    public DocumentMetadata {
        title = Objects.toString(title, "");
        author = Objects.toString(author, "");
        creationTime = Objects.toString(creationTime, "");
        modificationTime = Objects.toString(modificationTime, "");
        language = Objects.toString(language, "unknown");
        source = Objects.toString(source, "");
        hash = Objects.toString(hash, "");
    }

    public static DocumentMetadata empty() {
        return new DocumentMetadata("", "", 0, "", "", 0L, "unknown", "", "");
    }
}
