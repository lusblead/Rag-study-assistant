package com.rag.backend.agent.model;

import java.util.Objects;

// 单页 OCR 失败信息；单页失败不会导致整份 PDF 解析失败。
public record OcrPageFailure(int pageNo, String reason) {
    public OcrPageFailure {
        reason = Objects.toString(reason, "");
    }
}
