package com.rag.backend.agent.model;

import java.util.Objects;

// 页面布局块：标题、正文、页眉页脚、表格、图片占位等。
public record LayoutBlock(int pageNo, String type, String text, int level) {
    public static final String TYPE_TITLE = "title";
    public static final String TYPE_PARAGRAPH = "paragraph";
    public static final String TYPE_HEADER = "header";
    public static final String TYPE_FOOTER = "footer";
    public static final String TYPE_TABLE = "table";
    public static final String TYPE_IMAGE = "image";

    public LayoutBlock {
        type = Objects.toString(type, TYPE_PARAGRAPH);
        text = Objects.toString(text, "");
    }
}
