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
public class MarkdownDocumentParser implements DocumentParser {
    @Override
    public boolean supports(String fileType) {
        return "md".equalsIgnoreCase(fileType) || "markdown".equalsIgnoreCase(fileType);
    }

    @Override
    public ParsedDocument parse(Path filePath) {
        try {
            String content = Files.readString(filePath, StandardCharsets.UTF_8);
            return new ParsedDocument(
                    filePath.getFileName().toString(),
                    content,
                    Collections.singletonList(new PageText(null, content))
            );
        } catch (IOException e) {
            throw new RuntimeException("Failed to parse Markdown file", e);
        }
    }
}