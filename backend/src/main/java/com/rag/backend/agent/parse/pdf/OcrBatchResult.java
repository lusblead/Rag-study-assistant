package com.rag.backend.agent.parse.pdf;

import com.rag.backend.agent.model.OcrPageFailure;

import java.util.List;
import java.util.Map;

public record OcrBatchResult(
        Map<Integer, String> pageTexts,
        List<OcrPageFailure> failures,
        boolean cacheHit,
        long durationMs
) {
    public OcrBatchResult {
        pageTexts = pageTexts == null ? Map.of() : Map.copyOf(pageTexts);
        failures = failures == null ? List.of() : List.copyOf(failures);
    }
}
