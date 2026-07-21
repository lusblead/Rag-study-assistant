package com.rag.backend.agent.parse.pdf;

import com.rag.backend.agent.model.ParsedImage;
import org.apache.pdfbox.pdmodel.PDDocument;

import java.io.IOException;
import java.util.List;

public interface PdfImageExtractor {
    List<ParsedImage> extract(PDDocument document) throws IOException;
}
