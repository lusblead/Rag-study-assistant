package com.rag.backend.paper.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public class Paper {
    private Long id; private Long courseId; private String subject; private String title;
    private String paperType; private String gradeLevel; private String difficulty;
    private Integer durationMinutes; private BigDecimal totalScore; private String templateCode;
    private String requirements; private String warnings; private LocalDateTime createdAt; private LocalDateTime updatedAt;
    public Long getId(){return id;} public void setId(Long v){id=v;}
    public Long getCourseId(){return courseId;} public void setCourseId(Long v){courseId=v;}
    public String getSubject(){return subject;} public void setSubject(String v){subject=v;}
    public String getTitle(){return title;} public void setTitle(String v){title=v;}
    public String getPaperType(){return paperType;} public void setPaperType(String v){paperType=v;}
    public String getGradeLevel(){return gradeLevel;} public void setGradeLevel(String v){gradeLevel=v;}
    public String getDifficulty(){return difficulty;} public void setDifficulty(String v){difficulty=v;}
    public Integer getDurationMinutes(){return durationMinutes;} public void setDurationMinutes(Integer v){durationMinutes=v;}
    public BigDecimal getTotalScore(){return totalScore;} public void setTotalScore(BigDecimal v){totalScore=v;}
    public String getTemplateCode(){return templateCode;} public void setTemplateCode(String v){templateCode=v;}
    public String getRequirements(){return requirements;} public void setRequirements(String v){requirements=v;}
    public String getWarnings(){return warnings;} public void setWarnings(String v){warnings=v;}
    public LocalDateTime getCreatedAt(){return createdAt;} public void setCreatedAt(LocalDateTime v){createdAt=v;}
    public LocalDateTime getUpdatedAt(){return updatedAt;} public void setUpdatedAt(LocalDateTime v){updatedAt=v;}
}
