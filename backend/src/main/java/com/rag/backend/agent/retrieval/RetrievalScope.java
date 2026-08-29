package com.rag.backend.agent.retrieval;

import java.util.Objects;
import java.util.Set;

/**
 * 一次检索请求冻结的课程与 ACTIVE 文档版本边界。
 */
public record RetrievalScope(long courseId, Set<Long> activeVersionIds) {
    public RetrievalScope {
        Objects.requireNonNull(activeVersionIds, "activeVersionIds");
        if (activeVersionIds.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("activeVersionIds must not contain null");
        }
        activeVersionIds = Set.copyOf(activeVersionIds);
    }
}
