package com.rag.backend.paper.model;

import com.rag.backend.question.model.Question;
import java.math.BigDecimal;

public class PaperQuestion {
    private Long id; private Long paperId; private Long questionId; private String sectionKey;
    private String sectionTitle; private String sectionInstructions; private Integer questionOrder;
    private BigDecimal score; private Question question;
    public Long getId(){return id;} public void setId(Long v){id=v;}
    public Long getPaperId(){return paperId;} public void setPaperId(Long v){paperId=v;}
    public Long getQuestionId(){return questionId;} public void setQuestionId(Long v){questionId=v;}
    public String getSectionKey(){return sectionKey;} public void setSectionKey(String v){sectionKey=v;}
    public String getSectionTitle(){return sectionTitle;} public void setSectionTitle(String v){sectionTitle=v;}
    public String getSectionInstructions(){return sectionInstructions;} public void setSectionInstructions(String v){sectionInstructions=v;}
    public Integer getQuestionOrder(){return questionOrder;} public void setQuestionOrder(Integer v){questionOrder=v;}
    public BigDecimal getScore(){return score;} public void setScore(BigDecimal v){score=v;}
    public Question getQuestion(){return question;} public void setQuestion(Question v){question=v;}
}
