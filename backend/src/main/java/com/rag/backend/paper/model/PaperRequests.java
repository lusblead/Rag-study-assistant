package com.rag.backend.paper.model;

import java.math.BigDecimal;
import java.util.List;

public final class PaperRequests {
    private PaperRequests() {}
    public record Generate(Long courseId, List<Long> documentIds, String subject, String title,
                           String paperType, String gradeLevel, String difficulty, Integer durationMinutes,
                           BigDecimal totalScore, String templateCode, String requirements) {}
    public record Update(String title, String paperType, String difficulty, Integer durationMinutes, BigDecimal totalScore) {}
    public record AddQuestion(Long questionId, String sectionKey, String sectionTitle,
                              String sectionInstructions, BigDecimal score) {}
    public record Reorder(List<ReorderItem> items) {}
    public record ReorderItem(Long questionId, String sectionKey, String sectionTitle,
                              String sectionInstructions, Integer questionOrder, BigDecimal score) {}
}
