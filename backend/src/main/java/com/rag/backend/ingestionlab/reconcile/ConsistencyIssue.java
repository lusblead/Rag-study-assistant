// ConsistencyIssue 把 Version、问题类型和 subject 固定为 issueKey，重复扫描只更新同一逻辑问题。
package com.rag.backend.ingestionlab.reconcile;

import com.rag.backend.ingestionlab.identity.StableHash;

// 一致性问题：稳定 issueKey 避免每轮扫描重复告警。
public record ConsistencyIssue(
        String issueKey,
        long documentVersionId,
        String issueType,
        String subjectKey,
        String expectedValue,
        String actualValue,
        String suggestedAction) {

    // 按版本、类型和 subject 生成稳定 issueKey。
    public static ConsistencyIssue of(long versionId, String type,
                                      String subjectKey, String expected,
                                      String actual, String action) {
        String issueKey = StableHash.sha256(
                versionId + "|" + type + "|" + subjectKey);
        return new ConsistencyIssue(issueKey, versionId, type, subjectKey,
                expected, actual, action);
    }
}