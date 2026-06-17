package com.rag.backend.agent.parse;

import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class DocumentParseFactory {
    private final List<DocumentParser> parsers;

    public DocumentParseFactory(List<DocumentParser> parsers) {
        this.parsers = parsers;
    }

    public DocumentParser getParser(String fileType){
        return parsers.stream()
                .filter(parser ->parser.supports(fileType))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("不支持的文件类型： " + fileType));
    }
}
