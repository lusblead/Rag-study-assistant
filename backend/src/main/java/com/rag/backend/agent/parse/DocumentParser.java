package com.rag.backend.agent.parse;

import com.rag.backend.agent.model.ParsedDocument;

import java.nio.file.Path;

public interface DocumentParser {
    boolean supports(String fileType);
    ParsedDocument parse(Path filepath);
}
