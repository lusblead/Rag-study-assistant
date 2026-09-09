package com.rag.backend.agent.retrieval;

import java.util.Objects;
import java.util.Set;

/**
 * 本轮课程和版本边界；会话路径可以包含已合法绑定且仍可读的 SUPERSEDED 版本。
 * activeVersionIds 沿用原字段名，不代表每个绑定版本现在仍为 ACTIVE。
 */
public record RetrievalScope(long courseId, Set<Long> activeVersionIds, boolean sessionBound) {
    public RetrievalScope(long courseId, Set<Long> activeVersionIds) {
        this(courseId, activeVersionIds, false);
    }
    public RetrievalScope {
        Objects.requireNonNull(activeVersionIds, "activeVersionIds");
        if (activeVersionIds.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("activeVersionIds must not contain null");
        }
        activeVersionIds = Set.copyOf(activeVersionIds);
    }
}
