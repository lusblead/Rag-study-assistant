package com.rag.backend.agent.retrieval;

import java.util.List;

/** 所有候选源均失败时的整体失败。 */
public class CandidateCollectionException extends RuntimeException {
    private final List<CandidateSourceDiagnostic> diagnostics;

    public CandidateCollectionException(
            String message,
            List<CandidateSourceDiagnostic> diagnostics) {
        super(message);
        this.diagnostics = List.copyOf(diagnostics);
    }

    public List<CandidateSourceDiagnostic> diagnostics() {
        return diagnostics;
    }
}
