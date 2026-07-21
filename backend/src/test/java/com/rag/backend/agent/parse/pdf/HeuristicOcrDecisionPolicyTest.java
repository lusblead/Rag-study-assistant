package com.rag.backend.agent.parse.pdf;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HeuristicOcrDecisionPolicyTest {
    private final HeuristicOcrDecisionPolicy policy = new HeuristicOcrDecisionPolicy();

    @Test
    void detectsBlankAndPageNumberAsNeedingOcr() {
        assertThat(policy.needsOcr("   ")).isTrue();
        assertThat(policy.needsOcr("第 12 页")).isTrue();
        assertThat(policy.needsOcr("Page 3")).isTrue();
    }

    @Test
    void keepsUsefulTextPdfContent() {
        assertThat(policy.needsOcr("第一章 机器学习基础\n这是包含足够中文字符的正文内容。")).isFalse();
        assertThat(policy.needsOcr("This is a regular text-based PDF page with useful words.")).isFalse();
    }
}
