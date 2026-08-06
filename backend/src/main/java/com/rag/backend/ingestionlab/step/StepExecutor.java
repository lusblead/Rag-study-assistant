// 执行器目的：在 Worker 已持有 Job Lease 的前提下，复用同输入 DONE 输出或提交本次 Stage 结果。
package com.rag.backend.ingestionlab.step;

import com.rag.backend.ingestionlab.job.JobLeaseService;
import com.rag.backend.ingestionlab.vector.VectorFailureClassifier;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.function.Supplier;

// 步骤幂等器：同输入复用，不同输入拒绝静默覆盖。
public final class StepExecutor {
    // Mapper 同时提供步骤唯一行、RUNNING 条件更新和 DONE 输出提交，执行器必须检查每次更新结果。
    private final IngestStepMapper mapper;

    // 执行器只依赖步骤 Mapper；具体 Parse/Chunk/Vector 动作由 run 的 Supplier 从编排器传入。
    public StepExecutor(IngestStepMapper mapper) { this.mapper = mapper; }

    // 成功且输入摘要相同就复用，否则执行或报告冲突。
    public StepResult run(
            JobLeaseService.Lease lease,
            String stepName,
            String inputDigest,
            Supplier<StepResult> action) {
        String jobId = lease.jobId();
        mapper.insertIfAbsent(jobId, stepName, inputDigest);
        IngestStepRow row = mapper.find(jobId, stepName);
        if (row == null) {
            throw new IllegalStateException(
                    "Step row missing after insert: "
                            + jobId + "/" + stepName);
        }
        if (!inputDigest.equals(row.getInputDigest())) {
            throw new IllegalStateException(
                    "Step input changed: " + stepName);
        }
        if ("DONE".equals(row.getState())) {
            return new StepResult(
                    row.getOutputRef(),
                    row.getOutputDigest(),
                    row.getProcessedCount(),
                    true);
        }

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        if (mapper.markRunning(
                jobId,
                stepName,
                inputDigest,
                lease.owner(),
                lease.stateVersion(),
                now) != 1) {
            throw new JobLeaseService.LeaseLostException(jobId);
        }

        try {
            StepResult result = action.get();
            if (mapper.markDone(
                    jobId,
                    stepName,
                    inputDigest,
                    result.outputRef(),
                    result.outputDigest(),
                    result.processedCount(),
                    lease.owner(),
                    lease.stateVersion(),
                    LocalDateTime.now(ZoneOffset.UTC)) != 1) {
                throw new JobLeaseService.LeaseLostException(jobId);
            }
            return result;
        } catch (RuntimeException error) {
            String errorCode = classifyStepFailure(error);
            int changed = mapper.markFailed(
                    jobId,
                    stepName,
                    inputDigest,
                    lease.owner(),
                    lease.stateVersion(),
                    LocalDateTime.now(ZoneOffset.UTC),
                    errorCode);
            if (changed != 1) {
                // Lease 已丢失时不能让旧 Worker 覆盖新执行者的步骤状态。
                throw new JobLeaseService.LeaseLostException(jobId);
            }
            throw error;
        }
    }

    /**
     * 按异常类型返回稳定错误码，不用异常消息文本猜测类别。
     * Vector 相关委托 VectorFailureClassifier；
     * Parse/Chunk 按异常类名分类。
     */
    private String classifyStepFailure(RuntimeException error) {
        Throwable root = error.getCause() != null ? error.getCause() : error;

        VectorFailureClassifier.VectorFailure vf =
                VectorFailureClassifier.classify(root);
        if (!"DOWNSTREAM_UNAVAILABLE".equals(vf.errorCode())) {
            return "STEP_VECTOR_" + vf.errorCode();
        }

        String className = root.getClass().getSimpleName();
        if (className.contains("Parse") || className.contains("Parser")) {
            return "STEP_PARSE_FAILED";
        }
        if (className.contains("Chunk") || className.contains("Chunker")) {
            return "STEP_CHUNK_FAILED";
        }
        if (className.contains("Vector") || className.contains("Embedding")
                || className.contains("Milvus")) {
            return "STEP_VECTOR_FAILED";
        }
        if (root instanceof IllegalArgumentException) {
            return "STEP_VALIDATION";
        }
        return "STEP_FAILED";
    }

    // StepResult 是 Stage 与步骤表之间的提交契约：输出引用、摘要、数量和是否来自重放。
    public record StepResult(String outputRef, String outputDigest,
                             int processedCount, boolean replayed) {
        // 新执行产生的结果默认 replayed=false；读取 DONE 行时使用四参数构造器标记为 true。
        public StepResult(String ref, String digest, int count) {
            this(ref, digest, count, false);
        }
    }
}