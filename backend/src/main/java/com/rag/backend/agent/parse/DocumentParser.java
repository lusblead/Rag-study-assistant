package com.rag.backend.agent.parse;

import com.rag.backend.agent.model.ParsedDocument;

import java.nio.file.Path;

// supports 参与格式路由，parse 统一产出 ParsedDocument；切片、Embedding 和入库不属于解析器职责。
public interface DocumentParser {
    boolean supports(String fileType);
    ParsedDocument parse(Path filepath);
}
