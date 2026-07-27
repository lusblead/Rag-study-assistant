package com.rag.backend.agent.parse.pdf;

import com.rag.backend.agent.model.LayoutBlock;
import com.rag.backend.agent.model.ParsedImage;

import java.util.List;

public interface LayoutAnalyzer {
    List<LayoutBlock> analyze(int pageNo, String text, List<ParsedImage> images);
}
