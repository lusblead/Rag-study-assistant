package com.rag.backend.agent.model;

import java.util.List;

// 文档解析可观测性日志摘要。
public record ParseLog(
        long startedAtEpochMillis,
        long finishedAtEpochMillis,
        int totalPages,
        int textPages,
        int ocrPages,
        long textExtractionMs,
        long ocrMs,
        long totalMs,
        List<String> warnings
) {
    public ParseLog {
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    public static ParseLog empty() {
        return new ParseLog(0L, 0L, 0, 0, 0, 0L, 0L, 0L, List.of());
    }
}
