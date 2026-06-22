package com.rag.backend.agent.model;

import java.util.List;

// 承载模型生成题目的原始结构化数据。
public class GeneratedQuestionItem {
    private String type;
    private String stem;
    private List<String> options;
    private String answer;
    private String explanation;
    private String difficulty;
    private String knowledgePoint;
    private Long sourceChunkId;

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getStem() { return stem; }
    public void setStem(String stem) { this.stem = stem; }

    public List<String> getOptions() { return options; }
    public void setOptions(List<String> options) { this.options = options; }

    public String getAnswer() { return answer; }
    public void setAnswer(String answer) { this.answer = answer; }

    public String getExplanation() { return explanation; }
    public void setExplanation(String explanation) { this.explanation = explanation; }

    public String getDifficulty() { return difficulty; }
    public void setDifficulty(String difficulty) { this.difficulty = difficulty; }

    public String getKnowledgePoint() { return knowledgePoint; }
    public void setKnowledgePoint(String knowledgePoint) { this.knowledgePoint = knowledgePoint; }

    public Long getSourceChunkId() { return sourceChunkId; }
    public void setSourceChunkId(Long sourceChunkId) { this.sourceChunkId = sourceChunkId; }
}