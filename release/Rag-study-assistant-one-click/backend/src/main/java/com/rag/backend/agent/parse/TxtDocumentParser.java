package com.rag.backend.agent.parse;

import com.rag.backend.agent.model.PageText;
import com.rag.backend.agent.model.ParsedDocument;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;

@Component
public class TxtDocumentParser implements DocumentParser {
    @Override
    public boolean supports(String fileType) {
        return "txt".equalsIgnoreCase(fileType);
    }

    @Override
    public ParsedDocument parse(Path filePath) {
        try {
            String content = Files.readString(filePath, StandardCharsets.UTF_8);
            String title = filePath.getFileName().toString();
            return new ParsedDocument(title, content, Collections.singletonList(new PageText(null, content)));
        } catch (IOException e) {
            throw new RuntimeException("TXT 文件解析失败", e);
        }
    }
}
