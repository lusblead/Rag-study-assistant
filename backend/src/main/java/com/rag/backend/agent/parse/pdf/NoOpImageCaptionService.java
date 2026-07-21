package com.rag.backend.agent.parse.pdf;

import com.rag.backend.agent.model.ParsedImage;
import org.springframework.stereotype.Service;

@Service
public class NoOpImageCaptionService implements ImageCaptionService {
    @Override
    public String caption(ParsedImage image) {
        return "";
    }
}
