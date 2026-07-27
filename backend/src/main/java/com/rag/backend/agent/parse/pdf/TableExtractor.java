package com.rag.backend.agent.parse.pdf;

import com.rag.backend.agent.model.ParsedTable;

import java.util.List;

public interface TableExtractor {
    List<ParsedTable> extract(int pageNo, String cleanedText);
}
