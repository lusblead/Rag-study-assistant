package com.rag.backend.agent.parse;

import com.rag.backend.common.BizException;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
// 根据文件类型选择合适的文档解析器。
public class DocumentParserFactory {
    private final List<DocumentParser> parsers;

    public DocumentParserFactory(List<DocumentParser> parsers) {
        this.parsers = parsers;
    }

    public DocumentParser getParser(String fileType) {
        return parsers.stream()
                .filter(parser -> parser.supports(fileType))
                .findFirst()
                .orElseThrow(() -> new BizException(400, "Unsupported file type: " + fileType));
    }
}
