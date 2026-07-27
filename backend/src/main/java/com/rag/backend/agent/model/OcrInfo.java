package com.rag.backend.agent.model;

import java.util.List;

// OCR 统计与失败页信息。
public record OcrInfo(
        int totalPages,
        int ocrPages,
        int successPages,
        int failedPages,
        List<OcrPageFailure> failures,
        boolean cacheHit,
        long durationMs
) {
    public OcrInfo {
        failures = failures == null ? List.of() : List.copyOf(failures);
    }

    public static OcrInfo none(int totalPages) {
        return new OcrInfo(totalPages, 0, 0, 0, List.of(), false, 0L);
    }
}
