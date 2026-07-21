package com.rag.backend.agent.parse.pdf;

import com.rag.backend.agent.model.PageText;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@Component
public class PdfBoxTextExtractor implements PdfTextExtractor {
    @Override
    public List<PageText> extract(PDDocument document) throws IOException {
        PDFTextStripper stripper = new PDFTextStripper();
        List<PageText> pages = new ArrayList<>();
        for (int page = 1; page <= document.getNumberOfPages(); page++) {
            stripper.setStartPage(page);
            stripper.setEndPage(page);
            String text = stripper.getText(document);
            pages.add(new PageText(page, text == null ? "" : text.trim()));
        }
        return pages;
    }
}
