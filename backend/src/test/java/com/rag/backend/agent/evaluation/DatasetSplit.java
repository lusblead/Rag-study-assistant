package com.rag.backend.agent.evaluation;

// DEVELOPMENT 可反复调参；FROZEN 只用于最终比较，查看结果后继续调参就必须升级数据版本。
public enum DatasetSplit {
    DEVELOPMENT,
    SMOKE,
    REGRESSION,
    FROZEN,
    ADVERSARIAL
}
