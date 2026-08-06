package com.rag.backend.agent.evaluation.compile;

import java.nio.file.Path;

// 每次编译写入一个全新的运行目录；旧 manifest 不会被新失败运行冒充。
public record CompileRequest(
        Path authorCases,
        Path courseMap,
        Path evidenceMap,
        Path goldenOutput,
        Path rubricOutput,
        Path manifestOutput
) {
}
