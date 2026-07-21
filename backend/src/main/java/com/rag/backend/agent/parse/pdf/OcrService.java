package com.rag.backend.agent.parse.pdf;

import org.apache.pdfbox.rendering.PDFRenderer;

import java.util.Collection;

public interface OcrService {
    OcrBatchResult recognizePages(PDFRenderer renderer, Collection<Integer> pageNumbers, String documentHash);
}
