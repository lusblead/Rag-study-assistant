package com.rag.backend.agent.evidence;

import org.springframework.stereotype.Component;

/** 将非 ANSWER 决策渲染为固定、安全的本地响应，不调用生成模型。 */
@Component
public class EvidenceDecisionRenderer {
    public String render(EvidenceDecisionResult decision) {
        return switch (decision.decision()) {
            case ANSWER -> throw new IllegalArgumentException(
                    "ANSWER must be rendered by the generation model");
            case CLARIFY -> "为了避免猜测，" + decision.missingInformation();
            case REFUSE -> refusal(decision.reasonCode());
        };
    }

    private String refusal(EvidenceDecisionReason reason) {
        return switch (reason) {
            case CONFLICTING_EVIDENCE ->
                    "当前课程资料中的相关证据存在无法消解的冲突，暂时无法给出确定答案。";
            case UNTRACEABLE_EVIDENCE ->
                    "当前找到的内容缺少可核验来源，暂时无法据此回答。";
            default ->
                    "当前知识库中没有找到足够依据，暂时无法回答这个问题。";
        };
    }
}
