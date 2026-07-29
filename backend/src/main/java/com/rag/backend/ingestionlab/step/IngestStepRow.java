// 行模型对应 ingest_steps，StepExecutor 通过它判断输入冲突、DONE 复用和处理数量。
package com.rag.backend.ingestionlab.step;

// IngestStepRow：数据库映射模型，保存可恢复协议的持久化事实。
public class IngestStepRow {
    private String jobId;
    private String stepName;
    private String state;
    private String inputDigest;
    private String outputRef;
    private String outputDigest;
    private Integer processedCount;

    public String getJobId() { return jobId; }
    public void setJobId(String value) { this.jobId = value; }
    public String getStepName() { return stepName; }
    public void setStepName(String value) { this.stepName = value; }
    public String getState() { return state; }
    public void setState(String value) { this.state = value; }
    public String getInputDigest() { return inputDigest; }
    public void setInputDigest(String value) { this.inputDigest = value; }
    public String getOutputRef() { return outputRef; }
    public void setOutputRef(String value) { this.outputRef = value; }
    public String getOutputDigest() { return outputDigest; }
    public void setOutputDigest(String value) { this.outputDigest = value; }
    public Integer getProcessedCount() { return processedCount; }
    public void setProcessedCount(Integer value) { this.processedCount = value; }
}