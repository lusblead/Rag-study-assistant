package com.rag.backend.agent.model;

import java.util.List;
import java.util.Objects;

// 表示文档解析出的单页结构化内容；保留 pageNo/text 以兼容旧调用方。
public record PageText(
        Integer pageNo,
        String text,
        String markdown,
        List<ParsedImage> images,
        List<ParsedTable> tables,
        String ocrStatus,
        List<LayoutBlock> layoutBlocks
) {
    public static final String OCR_NOT_REQUIRED = "NOT_REQUIRED";
    public static final String OCR_SUCCESS = "SUCCESS";
    public static final String OCR_FAILED = "FAILED";
    public static final String OCR_SKIPPED = "SKIPPED";

    public PageText {
        text = Objects.toString(text, "");
        markdown = Objects.toString(markdown, text);
        images = images == null ? List.of() : List.copyOf(images);
        tables = tables == null ? List.of() : List.copyOf(tables);
        ocrStatus = Objects.toString(ocrStatus, OCR_NOT_REQUIRED);
        layoutBlocks = layoutBlocks == null ? List.of() : List.copyOf(layoutBlocks);
    }

    public PageText(Integer pageNo, String text) {
        this(pageNo, text, text, List.of(), List.of(), OCR_NOT_REQUIRED, List.of());
    }
}
