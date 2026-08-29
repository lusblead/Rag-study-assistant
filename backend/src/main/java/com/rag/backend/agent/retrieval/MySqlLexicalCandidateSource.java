package com.rag.backend.agent.retrieval;

import com.rag.backend.agent.repository.KnowledgeChunkMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** 独立 MySQL InnoDB FULLTEXT/ngram 候选源；仅在 Hybrid rollout gate 开启时进入问答。 */
@Component
public class MySqlLexicalCandidateSource implements CandidateSource {
    private final KnowledgeChunkMapper mapper;
    private final double scoreThreshold;
    private final LexicalQueryPolicy queryPolicy;

    @Autowired
    public MySqlLexicalCandidateSource(
            KnowledgeChunkMapper mapper,
            @Value("${rag.lexical.score-threshold:0.0}") double scoreThreshold) {
        this(mapper, scoreThreshold, new LexicalQueryPolicy());
    }

    MySqlLexicalCandidateSource(
            KnowledgeChunkMapper mapper,
            double scoreThreshold,
            LexicalQueryPolicy queryPolicy) {
        this.mapper = mapper;
        if (!Double.isFinite(scoreThreshold)) {
            throw new IllegalArgumentException("scoreThreshold must be finite");
        }
        this.scoreThreshold = scoreThreshold;
        this.queryPolicy = queryPolicy;
    }

    @Override
    public CandidateSourceType type() {
        return CandidateSourceType.LEXICAL;
    }

    @Override
    public CandidateBatch retrieve(
            RetrievalScope scope,
            String query,
            int candidateK) {
        validate(scope, query, candidateK);
        if (scope.activeVersionIds().isEmpty()) {
            return CandidateBatch.empty(type());
        }

        List<Long> activeVersionIds = scope.activeVersionIds().stream()
                .sorted(Comparator.naturalOrder())
                .toList();
        LexicalQueryPolicy.QueryPlan plan = queryPolicy.plan(query);
        List<LexicalCandidateRow> rows = switch (plan.mode()) {
            case NATURAL_LANGUAGE -> mapper.selectLexicalNatural(
                    scope.courseId(), activeVersionIds, plan.boundQuery(),
                    scoreThreshold, candidateK);
            case BOOLEAN_PHRASE -> mapper.selectLexicalBooleanPhrase(
                    scope.courseId(), activeVersionIds, plan.boundQuery(),
                    scoreThreshold, candidateK);
        };

        if (rows == null) {
            throw new CandidateSourceException(
                    CandidateSourceFailureType.INVALID_BATCH,
                    "Lexical mapper returned null rows");
        }
        List<RetrievalCandidate> candidates = new ArrayList<>(rows.size());
        for (int index = 0; index < rows.size()
                && candidates.size() < candidateK; index++) {
            LexicalCandidateRow row = rows.get(index);
            if (row == null || row.getChunkId() == null
                    || row.getRawScore() == null
                    || !Double.isFinite(row.getRawScore())) {
                throw new CandidateSourceException(
                        CandidateSourceFailureType.INVALID_BATCH,
                        "Lexical mapper returned an invalid candidate row");
            }
            double rawScore = row.getRawScore();
            if (rawScore <= 0.0 || rawScore < scoreThreshold) {
                continue;
            }
            RetrievedChunk chunk = new RetrievedChunk(
                    row.getChunkId(),
                    row.getDocumentId(),
                    row.getDocumentName(),
                    row.getTitle(),
                    row.getContent(),
                    row.getSourcePage(),
                    rawScore).withLexicalScore(rawScore);
            candidates.add(new RetrievalCandidate(
                    chunk, rawScore, candidates.size()));
        }
        return new CandidateBatch(type(), candidates);
    }

    private void validate(
            RetrievalScope scope,
            String query,
            int candidateK) {
        if (scope == null) {
            throw new IllegalArgumentException("scope must not be null");
        }
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("query must not be blank");
        }
        if (candidateK <= 0) {
            throw new IllegalArgumentException("candidateK must be > 0");
        }
    }
}
