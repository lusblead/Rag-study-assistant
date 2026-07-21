package com.rag.backend.agent.parse;

import com.rag.backend.agent.model.PageText;
import com.rag.backend.agent.model.ParsedDocument;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Component
public class PdfDocumentParser implements DocumentParser {
    @Override
    public boolean supports(String fileType) {
        return "pdf".equalsIgnoreCase(fileType);
    }

    @Override
    public ParsedDocument parse(Path filePath) {
        try (PDDocument document = Loader.loadPDF(filePath.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            List<PageText> pages = new ArrayList<>();
            StringBuilder all = new StringBuilder();

            for (int page = 1; page <= document.getNumberOfPages(); page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String text = stripper.getText(document).trim();
                if (!text.isBlank()) {
                    pages.add(new PageText(page, text));
                    all.append(text).append("\n\n");
                }
            }

            if (all.toString().isBlank()) {
                throw new RuntimeException("PDF contains no extractable text. Scanned PDFs need OCR.");
            }
            return new ParsedDocument(filePath.getFileName().toString(), all.toString(), pages);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse PDF file", e);
        }
    }
}