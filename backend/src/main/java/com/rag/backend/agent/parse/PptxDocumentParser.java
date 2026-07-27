package com.rag.backend.agent.parse;

import com.rag.backend.agent.model.PageText;
import com.rag.backend.agent.model.ParsedDocument;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Component
// 解析 pptx 演示文稿中的文本。
public class PptxDocumentParser implements DocumentParser {
    @Override
    public boolean supports(String fileType) {
        return "pptx".equalsIgnoreCase(fileType);
    }

    @Override
    public ParsedDocument parse(Path filePath) {
        try (InputStream inputStream = Files.newInputStream(filePath);
             XMLSlideShow slideShow = new XMLSlideShow(inputStream)) {
            List<PageText> pages = new ArrayList<>();
            StringBuilder all = new StringBuilder();

            int page = 1;
            for (XSLFSlide slide : slideShow.getSlides()) {
                String text = extractSlideText(slide).trim();
                if (!text.isBlank()) {
                    pages.add(new PageText(page, text));
                    all.append(text).append("\n\n");
                }
                page++;
            }

            if (all.toString().isBlank()) {
                throw new RuntimeException("PPTX file contains no extractable text");
            }
            return new ParsedDocument(filePath.getFileName().toString(), all.toString(), pages);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse PPTX file", e);
        }
    }

    private String extractSlideText(XSLFSlide slide) {
        StringBuilder builder = new StringBuilder();
        for (XSLFShape shape : slide.getShapes()) {
            if (shape instanceof XSLFTextShape textShape) {
                String text = textShape.getText();
                if (text != null && !text.isBlank()) {
                    builder.append(text).append('\n');
                }
            }
        }
        return builder.toString();
    }
}
