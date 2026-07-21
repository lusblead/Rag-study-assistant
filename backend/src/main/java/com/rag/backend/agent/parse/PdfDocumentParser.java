package com.rag.backend.agent.parse;

import com.rag.backend.agent.model.ParsedDocument;
import com.rag.backend.agent.parse.pdf.PdfParser;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

@Component
public class PdfDocumentParser implements DocumentParser {
    private final PdfParser pdfParser;

    public PdfDocumentParser(PdfParser pdfParser) {
        this.pdfParser = pdfParser;
    }

    @Override
    public boolean supports(String fileType) {
        return "pdf".equalsIgnoreCase(fileType);
    }

    @Override
    public ParsedDocument parse(Path filePath) {
        return pdfParser.parse(filePath);
    }
}
