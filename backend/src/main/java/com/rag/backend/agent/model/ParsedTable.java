package com.rag.backend.agent.model;

import java.util.List;
import java.util.Objects;

// PDF 表格解析结果。当前支持规则化 Markdown 表格，后续可替换为专业表格模型。
public record ParsedTable(int pageNo, int tableIndex, List<List<String>> rows, String markdown) {
    public ParsedTable {
        rows = rows == null ? List.of() : List.copyOf(rows);
        markdown = Objects.toString(markdown, "");
    }
}
