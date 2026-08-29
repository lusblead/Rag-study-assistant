package com.rag.backend.agent.grounding;

import com.rag.backend.agent.retrieval.RetrievedChunk;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** Prompt、校验与一次修复共用的唯一请求级引用目录。 */
public final class CitationCatalog {
    private final List<CitationSource> sources;
    private final Map<String, CitationSource> bySourceId;

    public CitationCatalog(List<CitationSource> sources) {
        this.sources = List.copyOf(Objects.requireNonNull(sources, "sources"));
        if (this.sources.isEmpty()) {
            throw new IllegalArgumentException("citation catalog must not be empty");
        }
        Map<String, CitationSource> indexed = new LinkedHashMap<>();
        Set<Long> chunkIds = new HashSet<>();
        for (CitationSource source : this.sources) {
            CitationSource previous = indexed.putIfAbsent(
                    source.sourceId(), source);
            if (previous != null) {
                throw new IllegalArgumentException(
                        "citation source IDs must be unique");
            }
            if (!chunkIds.add(source.chunk().chunkId())) {
                throw new IllegalArgumentException(
                        "citation chunk IDs must be unique");
            }
        }
        this.bySourceId = Map.copyOf(indexed);
    }

    public static CitationCatalog from(List<RetrievedChunk> chunks) {
        List<RetrievedChunk> safeChunks = List.copyOf(
                Objects.requireNonNull(chunks, "chunks"));
        List<CitationSource> sources = java.util.stream.IntStream
                .range(0, safeChunks.size())
                .mapToObj(index -> new CitationSource(
                        "S" + (index + 1), safeChunks.get(index)))
                .toList();
        return new CitationCatalog(sources);
    }

    public List<CitationSource> sources() {
        return sources;
    }

    public List<String> sourceIds() {
        return sources.stream().map(CitationSource::sourceId).toList();
    }

    public Set<String> allowedSourceIds() {
        return bySourceId.keySet();
    }

    public Optional<CitationSource> find(String sourceId) {
        return Optional.ofNullable(bySourceId.get(sourceId));
    }

    public String promptText() {
        return sources.stream()
                .map(CitationSource::promptText)
                .collect(Collectors.joining("\n\n"));
    }
}
