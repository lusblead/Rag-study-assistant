package com.rag.backend.ingestionlab.delete;

/** 文档删除只返回持久任务身份，不等待跨存储物理清理完成。 */
public record DeleteSubmissionResponse(
        long documentId,
        String jobId,
        boolean reused,
        boolean alreadyDeleted) {
    public static DeleteSubmissionResponse from(
            DeleteRequestService.Submission value) {
        return new DeleteSubmissionResponse(
                value.documentId(), value.jobId(), value.reused(),
                value.alreadyDeleted());
    }
}
