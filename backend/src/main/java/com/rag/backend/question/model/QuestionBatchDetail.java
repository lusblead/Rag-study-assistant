package com.rag.backend.question.model;

import java.util.List;

public class QuestionBatchDetail {
    private QuestionBatch batch;
    private List<Question> questions;
    private List<Long> documentIds;
    private List<Long> chunkIds;

    public QuestionBatch getBatch() { return batch; }
    public void setBatch(QuestionBatch batch) { this.batch = batch; }
    public List<Question> getQuestions() { return questions; }
    public void setQuestions(List<Question> questions) { this.questions = questions; }
    public List<Long> getDocumentIds() { return documentIds; }
    public void setDocumentIds(List<Long> documentIds) { this.documentIds = documentIds; }
    public List<Long> getChunkIds() { return chunkIds; }
    public void setChunkIds(List<Long> chunkIds) { this.chunkIds = chunkIds; }
}
