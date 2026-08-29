package com.rag.backend.agent.retrieval;

import com.rag.backend.agent.repository.KnowledgeChunkMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MySqlLexicalCandidateSourceTest {

    @Test
    void inactiveVersionIsInvisibleAndDoesNotQueryMySql() {
        KnowledgeChunkMapper mapper = mock(KnowledgeChunkMapper.class);
        MySqlLexicalCandidateSource source = new MySqlLexicalCandidateSource(
                mapper, 0.0, new LexicalQueryPolicy());

        CandidateBatch result = source.retrieve(
                new RetrievalScope(7L, Set.of()), "网关规则", 20);

        assertTrue(result.candidates().isEmpty());
        verifyNoInteractions(mapper);
    }

    @Test
    void activationSwitchUsesOnlyTheNewFrozenVersionScope() {
        KnowledgeChunkMapper mapper = mock(KnowledgeChunkMapper.class);
        when(mapper.selectLexicalNatural(
                anyLong(), anyList(), anyString(), anyDouble(), anyInt()))
                .thenAnswer(invocation -> {
                    List<Long> versions = invocation.getArgument(1);
                    if (versions.equals(List.of(101L))) {
                        return List.of(row(11L, 0.9));
                    }
                    if (versions.equals(List.of(202L))) {
                        return List.of(row(22L, 0.8));
                    }
                    return List.of();
                });
        MySqlLexicalCandidateSource source = new MySqlLexicalCandidateSource(
                mapper, 0.0, new LexicalQueryPolicy());

        CandidateBatch before = source.retrieve(
                new RetrievalScope(7L, Set.of(101L)), "网关规则", 20);
        CandidateBatch after = source.retrieve(
                new RetrievalScope(7L, Set.of(202L)), "网关规则", 20);

        assertEquals(List.of(11L), chunkIds(before));
        assertEquals(List.of(22L), chunkIds(after));
        verify(mapper).selectLexicalNatural(
                7L, List.of(101L), "网关规则", 0.0, 20);
        verify(mapper).selectLexicalNatural(
                7L, List.of(202L), "网关规则", 0.0, 20);
    }

    @Test
    void deleteAndReplacementReadCurrentRowsWithoutSourceCache() {
        KnowledgeChunkMapper mapper = mock(KnowledgeChunkMapper.class);
        AtomicReference<List<LexicalCandidateRow>> currentRows =
                new AtomicReference<>(List.of(row(11L, 0.9)));
        when(mapper.selectLexicalNatural(
                anyLong(), anyList(), anyString(), anyDouble(), anyInt()))
                .thenAnswer(ignored -> currentRows.get());
        MySqlLexicalCandidateSource source = new MySqlLexicalCandidateSource(
                mapper, 0.0, new LexicalQueryPolicy());
        RetrievalScope scope = new RetrievalScope(7L, Set.of(101L));

        CandidateBatch beforeDelete = source.retrieve(scope, "网关规则", 20);
        currentRows.set(List.of());
        CandidateBatch afterDelete = source.retrieve(scope, "网关规则", 20);
        currentRows.set(List.of(row(33L, 0.85)));
        CandidateBatch afterReplacement = source.retrieve(scope, "网关规则", 20);

        assertEquals(List.of(11L), chunkIds(beforeDelete));
        assertTrue(afterDelete.candidates().isEmpty());
        assertEquals(List.of(33L), chunkIds(afterReplacement));
    }

    @Test
    void sourceEnforcesPositiveThresholdAndStableOrder() {
        KnowledgeChunkMapper mapper = mock(KnowledgeChunkMapper.class);
        when(mapper.selectLexicalNatural(
                anyLong(), anyList(), anyString(), anyDouble(), anyInt()))
                .thenReturn(List.of(
                        row(11L, 0.8),
                        row(12L, 0.0),
                        row(13L, 0.6),
                        row(14L, 0.4)));
        MySqlLexicalCandidateSource source = new MySqlLexicalCandidateSource(
                mapper, 0.5, new LexicalQueryPolicy());

        CandidateBatch result = source.retrieve(
                new RetrievalScope(7L, Set.of(101L)), "网关规则", 3);

        assertEquals(List.of(11L, 13L), chunkIds(result));
        assertEquals(List.of(0L, 1L), result.candidates().stream()
                .map(RetrievalCandidate::stableOrder)
                .toList());
        verify(mapper).selectLexicalNatural(
                eq(7L), eq(List.of(101L)), eq("网关规则"), eq(0.5), eq(3));
    }

    @Test
    void codeAndNumberQueryUsesBooleanPhraseMapper() {
        KnowledgeChunkMapper mapper = mock(KnowledgeChunkMapper.class);
        when(mapper.selectLexicalBooleanPhrase(
                anyLong(), anyList(), anyString(), anyDouble(), anyInt()))
                .thenReturn(List.of(row(44L, 0.7)));
        MySqlLexicalCandidateSource source = new MySqlLexicalCandidateSource(
                mapper, 0.0, new LexicalQueryPolicy());

        CandidateBatch result = source.retrieve(
                new RetrievalScope(7L, Set.of(101L)), "HTTP-404", 5);

        assertEquals(List.of(44L), chunkIds(result));
        verify(mapper).selectLexicalBooleanPhrase(
                7L, List.of(101L), "\"HTTP 404\"", 0.0, 5);
    }

    private static List<Long> chunkIds(CandidateBatch batch) {
        return batch.candidates().stream()
                .map(candidate -> candidate.chunk().chunkId())
                .toList();
    }

    private static LexicalCandidateRow row(long chunkId, double score) {
        LexicalCandidateRow row = new LexicalCandidateRow();
        row.setChunkId(chunkId);
        row.setDocumentId(3L);
        row.setDocumentName("doc.md");
        row.setTitle("title");
        row.setContent("content-" + chunkId);
        row.setSourcePage(1);
        row.setRawScore(score);
        return row;
    }
}
