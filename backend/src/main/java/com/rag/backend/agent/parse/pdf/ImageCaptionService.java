package com.rag.backend.agent.parse.pdf;

import com.rag.backend.agent.model.ParsedImage;

// 预留给视觉语言模型的图片理解接口。
public interface ImageCaptionService {
    String caption(ParsedImage image);
}
