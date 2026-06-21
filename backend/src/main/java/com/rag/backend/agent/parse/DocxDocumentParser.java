package com.rag.backend.agent.parse;

import com.rag.backend.agent.model.PageText;
import com.rag.backend.agent.model.ParsedDocument;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;

@Component
public class DocxDocumentParser implements DocumentParser {
    @Override
    public boolean supports(String fileType) {
        return "docx".equalsIgnoreCase(fileType);
    }

    @Override
    public ParsedDocument parse(Path filePath) {
        try (InputStream inputStream = Files.newInputStream(filePath);
             XWPFDocument document = new XWPFDocument(inputStream);
             XWPFWordExtractor extractor = new XWPFWordExtractor(document)) {
            String content = extractor.getText();
            if (content == null || content.isBlank()) {
                throw new RuntimeException("DOCX file contains no extractable text");
            }
            return new ParsedDocument(
                    filePath.getFileName().toString(),
                    content,
                    Collections.singletonList(new PageText(null, content))
            );
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse DOCX file", e);
        }
    }
}
