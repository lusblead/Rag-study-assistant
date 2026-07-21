package com.rag.backend.agent.parse.pdf;

import com.rag.backend.agent.model.PageText;
import org.apache.pdfbox.pdmodel.PDDocument;

import java.io.IOException;
import java.util.List;

public interface PdfTextExtractor {
    List<PageText> extract(PDDocument document) throws IOException;
}
