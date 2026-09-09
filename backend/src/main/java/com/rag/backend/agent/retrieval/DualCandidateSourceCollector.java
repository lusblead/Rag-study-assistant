package com.rag.backend.agent.retrieval;

import com.rag.backend.agent.materials.MaterialScopeException;

import java.net.SocketTimeoutException;
import java.sql.SQLTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.function.LongSupplier;

/**
 * 只收集 Dense 与 Lexical 的分离批次，不做去重、拼接、融合或排序。
 */
public class DualCandidateSourceCollector {
    private final List<CandidateSource> sources;
    private final LongSupplier nanoTime;

    public DualCandidateSourceCollector(
            CandidateSource denseSource,
            CandidateSource lexicalSource) {
        this(denseSource, lexicalSource, System::nanoTime);
    }

    DualCandidateSourceCollector(
            CandidateSource denseSource,
            CandidateSource lexicalSource,
            LongSupplier nanoTime) {
        requireType(denseSource, CandidateSourceType.DENSE);
        requireType(lexicalSource, CandidateSourceType.LEXICAL);
        this.sources = List.of(denseSource, lexicalSource);
        this.nanoTime = nanoTime;
    }

    public CandidateCollectionResult collect(
            RetrievalScope scope,
            String query,
            int candidateK) {
        return collect(scope, query, candidateK, candidateK);
    }

    public CandidateCollectionResult collect(
            RetrievalScope scope,
            String query,
            int denseCandidateK,
            int lexicalCandidateK) {
        if (denseCandidateK <= 0 || lexicalCandidateK <= 0) {
            throw new IllegalArgumentException(
                    "source candidate limits must be > 0");
        }
        List<CandidateBatch> batches = new ArrayList<>(sources.size());
        List<CandidateSourceDiagnostic> diagnostics =
                new ArrayList<>(sources.size());

        for (CandidateSource source : sources) {
            long started = nanoTime.getAsLong();
            try {
                int sourceK = source.type() == CandidateSourceType.DENSE
                        ? denseCandidateK
                        : lexicalCandidateK;
                CandidateBatch batch = source.retrieve(scope, query, sourceK);
                if (batch == null || batch.source() != source.type()) {
                    throw new CandidateSourceException(
                            CandidateSourceFailureType.INVALID_BATCH,
                            "Candidate source returned an invalid batch");
                }
                batches.add(batch);
                diagnostics.add(new CandidateSourceDiagnostic(
                        source.type(), null, null,
                        elapsed(started), batch.candidates().size()));
            } catch (MaterialScopeException failure) {
                throw failure;
            } catch (RuntimeException failure) {
                diagnostics.add(new CandidateSourceDiagnostic(
                        source.type(), source.type(), classify(failure),
                        elapsed(started), 0));
            }
        }

        if (batches.isEmpty()) {
            throw new CandidateCollectionException(
                    "All candidate sources failed", diagnostics);
        }
        boolean degraded = diagnostics.stream()
                .anyMatch(diagnostic -> !diagnostic.succeeded());
        return new CandidateCollectionResult(batches, degraded, diagnostics);
    }

    private long elapsed(long started) {
        return Math.max(0L, nanoTime.getAsLong() - started);
    }

    private CandidateSourceFailureType classify(RuntimeException failure) {
        if (failure instanceof CandidateSourceException sourceFailure) {
            return sourceFailure.failureType();
        }
        Throwable current = failure;
        while (current != null) {
            if (current instanceof TimeoutException
                    || current instanceof SocketTimeoutException
                    || current instanceof SQLTimeoutException) {
                return CandidateSourceFailureType.TIMEOUT;
            }
            current = current.getCause();
        }
        return CandidateSourceFailureType.SOURCE_ERROR;
    }

    private void requireType(
            CandidateSource source,
            CandidateSourceType expected) {
        if (source == null || source.type() != expected) {
            throw new IllegalArgumentException(
                    "Expected candidate source type " + expected);
        }
    }
}
