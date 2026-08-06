package com.rag.backend.agent.evaluation.compile;

import com.rag.backend.agent.evaluation.model.EvidenceSpan;

import java.util.Map;

/**
 * 第 02 章学习者核心任务：证据唯一定位与歧义拒绝。
 *
 * 职责：把“人工 evidenceId → 冻结索引映射 → 稳定 EvidenceSpan”的查找抽成
 * 独立、可测试的确定性方法，替代在 EvalCaseCompiler 内联判断。
 */
public final class EvidenceSpanResolver {

    private EvidenceSpanResolver() {
    }

    /**
     * 从 evidenceById 中按 evidenceId 唯一定位 EvidenceSpan。
     *
     * @param evidenceById  evidenceId → 冻结索引映射（key 唯一）
     * @param evidenceId    人工标注引用的证据 ID
     * @param courseKey     当前 case 的课程键，用于拒绝跨课程证据
     * @return 唯一定位到的 EvidenceSpan
     * @throws IllegalArgumentException 当 evidenceId 为空、不存在、或属于其他课程时拒绝
     */
    public static EvidenceSpan resolveUnique(
            Map<String, EvidenceIndexMapping> evidenceById,
            String evidenceId,
            String courseKey) {
        // TODO(学习者): 完成核心方法。
        // 1. evidenceId 为 null/blank → IllegalArgumentException
        if(evidenceId.isEmpty()) {
            throw new IllegalArgumentException("evidenceId is empty");
        }
        // 2. evidenceById 中不存在 → IllegalArgumentException（提示 cannot map evidenceId）
        if(!evidenceById.containsKey(evidenceId)) {
            throw new IllegalArgumentException("cannot map evidenceId: " + evidenceId);
        }
        // 3. mapping.courseKey() 与 courseKey 不一致 → IllegalArgumentException（拒绝跨课程证据）
        if(!evidenceById.get(evidenceId).courseKey().equals(courseKey)) {
            throw new IllegalArgumentException("拒绝跨课程证据" + evidenceId);
        }
        // 4. 返回 mapping.span()
        return evidenceById.get(evidenceId).span();
    }
}
