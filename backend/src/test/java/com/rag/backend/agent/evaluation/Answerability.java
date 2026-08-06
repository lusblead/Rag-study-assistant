package com.rag.backend.agent.evaluation;

// 标记黄金样本能否仅依据当前知识库回答，供评测区分应答样本与应拒答样本。
public enum Answerability {
    ANSWERABLE,
    UNANSWERABLE
}
