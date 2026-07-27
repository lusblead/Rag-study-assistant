package com.rag.backend.agent.parse.pdf;

import com.rag.backend.agent.model.LayoutBlock;
import com.rag.backend.agent.model.ParsedImage;
import com.rag.backend.agent.model.ParsedTable;

import java.util.List;

public interface MarkdownBuilder {
    String buildPageMarkdown(int pageNo, List<LayoutBlock> blocks, List<ParsedTable> tables, List<ParsedImage> images);

    String buildDocumentMarkdown(String title, List<String> pageMarkdowns);
}
