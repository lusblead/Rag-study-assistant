package com.rag.backend.agent.parse;

import com.rag.backend.common.BizException;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class DocumentParserFactory {
    private final List<DocumentParser> parsers;

    public DocumentParserFactory(List<DocumentParser> parsers) {
        this.parsers = parsers;
    }

    public DocumentParser getParser(String fileType){
        return parsers.stream()
                .filter(parser ->parser.supports(fileType))
                .findFirst()
                .orElseThrow(() -> new BizException(400, "不支持的文件类型：" + fileType));
    }
}
