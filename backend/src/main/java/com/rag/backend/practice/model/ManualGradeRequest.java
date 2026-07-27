package com.rag.backend.practice.model;
import java.math.BigDecimal;
public record ManualGradeRequest(BigDecimal score, BigDecimal maxScore, String feedback) {}
