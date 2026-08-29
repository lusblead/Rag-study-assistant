package com.rag.backend.agent.evidence;

/** 当前问答场景对证据的约束。 */
public record EvidenceConstraints(boolean mustCite) {
    public static EvidenceConstraints courseChat() {
        return new EvidenceConstraints(true);
    }
}
