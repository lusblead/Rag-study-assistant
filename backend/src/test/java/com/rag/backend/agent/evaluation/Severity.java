package com.rag.backend.agent.evaluation;

// 风险等级决定报告和门禁优先级，不能只靠 tags 猜测哪些回归必须阻断。
public enum Severity {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL
}
