package com.rag.backend.agent.evaluation.compile;

// 调用者得到数量和两个可复核摘要，不需要重新猜测输出文件是否完整。
public record CompileResult(
        int caseCount,
        String goldenSha256,
        String rubricSha256
) {
}
