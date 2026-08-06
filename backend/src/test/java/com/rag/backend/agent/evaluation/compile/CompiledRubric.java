package com.rag.backend.agent.evaluation.compile;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Set;

// 保存检索 record 无法表达、但答案评测必须使用的信息。
public record CompiledRubric(
        String caseId,
        String expectedOutcome,
        Set<String> requiredEvidenceIds,
        List<Set<String>> acceptableEvidenceSets,
        Set<String> forbiddenEvidenceIds,
        List<String> forbiddenBehaviors,
        JsonNode graders
) {
}
