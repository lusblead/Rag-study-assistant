package com.rag.backend.agent.parse;

import com.rag.backend.agent.model.ParsedDocument;

import java.nio.file.Path;

// 定义不同文档格式解析器的统一接口。
public interface DocumentParser {
    boolean supports(String fileType);
    ParsedDocument parse(Path filepath);
}
