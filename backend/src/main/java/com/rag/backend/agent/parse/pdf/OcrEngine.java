package com.rag.backend.agent.parse.pdf;

import java.awt.image.BufferedImage;

public interface OcrEngine {
    String recognize(BufferedImage image, int pageNo) throws Exception;
}
