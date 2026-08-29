package com.rag.backend.agent.grounding;

import org.springframework.stereotype.Component;

/** 第二次校验仍失败时，丢弃模型候选并返回不含未验证细节的安全文本。 */
@Component
public class GroundingFailureRenderer {
    public String render(GroundingValidationResult validation) {
        ClaimSupportResult claims = validation.claimSupport();
        if (claims != null && claims.count(
                ClaimSupportStatus.CONTRADICTED) > 0) {
            return "当前资料与生成内容存在冲突，我无法可靠确认答案。请核对资料版本或进一步明确问题。";
        }
        if (claims != null && claims.count(
                ClaimSupportStatus.UNCERTAIN) > 0) {
            return "当前证据不足以可靠验证生成内容，我暂时不能给出确定答案。";
        }
        if (claims != null && claims.count(
                ClaimSupportStatus.UNSUPPORTED) > 0) {
            return "当前课程资料没有提供足够依据来支持完整答案。";
        }
        return "生成结果的引用未能通过校验，我暂时不能提供该答案。";
    }
}
