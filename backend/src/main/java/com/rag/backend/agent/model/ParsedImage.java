package com.rag.backend.agent.model;

import java.util.Objects;

// PDF 图片提取结果。保留页码、编号和尺寸，供后续 Vision 模型理解。
public record ParsedImage(
        int pageNo,
        int imageIndex,
        String name,
        String format,
        int width,
        int height,
        String position,
        String caption
) {
    public ParsedImage {
        name = Objects.toString(name, "");
        format = Objects.toString(format, "unknown");
        position = Objects.toString(position, "unknown");
        caption = Objects.toString(caption, "");
    }
}
