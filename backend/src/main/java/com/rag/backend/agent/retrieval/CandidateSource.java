package com.rag.backend.agent.retrieval;

/** 独立候选召回接缝；threshold 由每个实现内部负责。 */
public interface CandidateSource {
    CandidateSourceType type();

    CandidateBatch retrieve(
            RetrievalScope scope,
            String query,
            int candidateK);
}
