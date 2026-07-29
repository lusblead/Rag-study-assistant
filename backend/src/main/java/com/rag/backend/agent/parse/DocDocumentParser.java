package com.rag.backend.agent.parse;

import com.rag.backend.agent.model.PageText;
import com.rag.backend.agent.model.ParsedDocument;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;

@Component
// 通过 POI HWPF 提取旧版 DOC 文本；空文档直接失败，成功时作为一个无页码 PageText 返回。
public class DocDocumentParser implements DocumentParser {
    @Override
    public boolean supports(String fileType) {
        return "doc".equalsIgnoreCase(fileType);
    }

    @Override
    public ParsedDocument parse(Path filePath) {
        try (InputStream inputStream = Files.newInputStream(filePath);
             HWPFDocument document = new HWPFDocument(inputStream);
             WordExtractor extractor = new WordExtractor(document)) {
            String content = extractor.getText();
            if (content == null || content.isBlank()) {
                throw new RuntimeException("DOC file contains no extractable text");
            }
            return new ParsedDocument(
                    filePath.getFileName().toString(),
                    content,
                    Collections.singletonList(new PageText(null, content))
            );
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse DOC file", e);
        }
    }
}
