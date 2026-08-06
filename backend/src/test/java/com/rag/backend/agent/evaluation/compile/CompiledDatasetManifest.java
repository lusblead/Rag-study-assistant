package com.rag.backend.agent.evaluation.compile;

// 不放当前时间，否则相同输入无法得到完全相同的 manifest。
public record CompiledDatasetManifest(
        String compilerVersion,
        int caseCount,
        String authorCasesSha256,
        String courseMapSha256,
        String evidenceMapSha256,
        String goldenSha256,
        String rubricSha256
) {
}
