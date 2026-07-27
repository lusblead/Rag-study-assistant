package com.rag.backend.agent.parse.pdf;

public interface OcrDecisionPolicy {
    boolean needsOcr(String text);
}
