package com.rag.backend.paper.model;

import java.math.BigDecimal;
import java.util.List;

public record PaperDetail(Paper paper, List<Section> sections, List<String> warnings) {
    public record Section(String sectionKey, String title, String instructions, BigDecimal score,
                          List<PaperQuestion> questions) {}
}
