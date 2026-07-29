package com.rag.backend.agent.parse;

import com.rag.backend.agent.model.PageText;
import com.rag.backend.agent.model.ParsedDocument;
import com.rag.backend.common.TextFileDecoder;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;

@Component
// 使用统一文本编码探测读取 Markdown，并保留原始标记作为一个无页码 PageText，不在此处渲染 HTML。
public class MarkdownDocumentParser implements DocumentParser {
    @Override
    public boolean supports(String fileType) {
        return "md".equalsIgnoreCase(fileType) || "markdown".equalsIgnoreCase(fileType);
    }

    @Override
    public ParsedDocument parse(Path filePath) {
        try {
            String content = TextFileDecoder.readString(filePath);
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
