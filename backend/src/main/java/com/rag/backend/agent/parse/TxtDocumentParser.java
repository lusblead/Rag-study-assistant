package com.rag.backend.agent.parse;

import com.rag.backend.agent.model.PageText;
import com.rag.backend.agent.model.ParsedDocument;
import com.rag.backend.common.TextFileDecoder;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;

@Component
// 使用统一文本编码探测读取 TXT，并把整份文件作为一个无页码 PageText；I/O 失败转为解析异常。
public class TxtDocumentParser implements DocumentParser {
    @Override
    public boolean supports(String fileType) {
        return "txt".equalsIgnoreCase(fileType);
    }

    @Override
    public ParsedDocument parse(Path filePath) {
        try {
            String content = TextFileDecoder.readString(filePath);
            String title = filePath.getFileName().toString();
            return new ParsedDocument(title, content, Collections.singletonList(new PageText(null, content)));
        } catch (IOException e) {
            throw new RuntimeException("TXT 文件解析失败", e);
        }
    }
}
