package com.rag.backend.agent.grounding;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/** 使用原始任务和同一证据目录生成一次完整替代回答。 */
@Component
public class GroundingRepairPromptTemplate {
    public String render(
            String originalPrompt,
            String rejectedAnswer,
            GroundingValidationResult validation,
            CitationCatalog catalog) {
        return """
                下面的候选回答没有通过生成后校验。请基于完全相同的课程资料，输出一份完整的替代回答。

                修复规则：
                1. 只能使用本次允许的来源编号：%s。
                2. 每条原子事实主张后必须紧跟一个或多个 [S编号]。
                3. 删除或保守改写证据不支持、与证据冲突或无法可靠判断的细节。
                4. 不得新增来源、重新编号来源或输出 chunkId/documentId。
                5. 只输出完整替代回答，不解释修复过程。

                【校验失败】
                %s

                【被拒绝的候选回答】
                %s

                【原始任务与同一证据目录】
                %s
                """.formatted(
                String.join(", ", catalog.sourceIds()),
                failureSummary(validation),
                rejectedAnswer,
                originalPrompt);
    }

    private String failureSummary(GroundingValidationResult validation) {
        List<String> failures = new ArrayList<>();
        failures.addAll(validation.citationIntegrity().failures().stream()
                .map(failure -> "citation=" + failure.reason()
                        + (failure.claimIndex() == null
                        ? ""
                        : ", claim=" + failure.claimIndex()))
                .toList());
        if (validation.claimSupport() != null) {
            failures.addAll(validation.claimSupport().assessments().stream()
                    .filter(assessment -> assessment.status()
                            != ClaimSupportStatus.SUPPORTED)
                    .map(assessment -> "claim=" + assessment.claim().index()
                            + ", status=" + assessment.status()
                            + ", reason=" + assessment.reasonCode())
                    .toList());
        }
        return failures.stream().distinct().collect(Collectors.joining("\n"));
    }
}
