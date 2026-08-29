package com.rag.backend.agent.grounding;

import java.util.List;
import java.util.Objects;

/** 从回答中抽取的一条原子事实主张及其就近引用。 */
public record AtomicClaim(int index, String text, List<String> citationSourceIds) {
    public AtomicClaim {
        if (index <= 0) {
            throw new IllegalArgumentException("claim index must be positive");
        }
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("claim text must not be blank");
        }
        citationSourceIds = List.copyOf(Objects.requireNonNull(
                citationSourceIds, "citationSourceIds"));
    }
}
