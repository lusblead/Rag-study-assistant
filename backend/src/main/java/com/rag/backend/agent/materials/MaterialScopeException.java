package com.rag.backend.agent.materials;

import com.rag.backend.common.BizException;

/** Fatal to the entire retrieval, never a degradable candidate-source failure. */
public class MaterialScopeException extends BizException {
    private final String reason;
    public MaterialScopeException(String reason) {
        super(409, "EVIDENCE_MISSING".equals(reason)
                ? "资料版本的证据不完整，已暂停本次回答，请联系维护者。"
                : "本对话使用的资料版本已不可用，请使用可用资料开启新对话。");
        this.reason = reason;
    }
    public String reason() { return reason; }
}
