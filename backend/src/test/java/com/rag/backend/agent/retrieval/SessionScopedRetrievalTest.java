package com.rag.backend.agent.retrieval;

import com.rag.backend.agent.materials.MaterialScopeException;
import com.rag.backend.agent.model.KnowledgeChunk;
import com.rag.backend.agent.repository.KnowledgeChunkRepository;
import com.rag.backend.agent.rerank.KnowledgeReranker;
import com.rag.backend.ingestionlab.retrieval.ActiveVersionResolver;
import com.rag.backend.ingestionlab.retrieval.VersionedVectorHit;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SessionScopedRetrievalTest {
    @Test void missingPinnedBodyIsFatalEvenWhenLexicalSucceeds() {
        var chunks = mock(KnowledgeChunkRepository.class);
        var dense = new DenseCandidateSource(q -> List.of(1.0),
                (c,v,q,k) -> List.of(new VersionedVectorHit(5,100,0.9)), chunks, null, 0);
        var lexical = mock(CandidateSource.class);
        when(lexical.type()).thenReturn(CandidateSourceType.LEXICAL);
        var scope = new RetrievalScope(1,Set.of(100L),true);
        var collector = new DualCandidateSourceCollector(dense,lexical);
        assertEquals("EVIDENCE_MISSING", assertThrows(MaterialScopeException.class,
                () -> collector.collect(scope,"query",5)).reason());
        verify(lexical, never()).retrieve(any(),anyString(),anyInt());
    }

    @Test void temporarySourceFailureMayDegradeButKeepsOriginalScope() {
        var dense = mock(CandidateSource.class);
        var lexical = mock(CandidateSource.class);
        when(dense.type()).thenReturn(CandidateSourceType.DENSE);
        when(lexical.type()).thenReturn(CandidateSourceType.LEXICAL);
        var scope = new RetrievalScope(1,Set.of(100L),true);
        when(dense.retrieve(scope,"query",5)).thenThrow(new IllegalStateException("temporary"));
        when(lexical.retrieve(scope,"query",5)).thenReturn(CandidateBatch.empty(CandidateSourceType.LEXICAL));
        var result = new DualCandidateSourceCollector(dense,lexical).collect(scope,"query",5);
        assertTrue(result.degraded());
        verify(lexical).retrieve(scope,"query",5);
    }

    @Test void explicitScopeDoesNotResolveActiveVersionsAgainAcrossSupplementaryCalls() {
        var resolver = mock(ActiveVersionResolver.class);
        var dense = mock(CandidateSource.class);
        var rerank = mock(KnowledgeReranker.class);
        when(dense.type()).thenReturn(CandidateSourceType.DENSE);
        var chunk = new RetrievedChunk(1L,10L,"course","evidence",1.0).withDocumentVersionId(100L);
        var scope = new RetrievalScope(1,Set.of(100L),true);
        when(dense.retrieve(eq(scope),anyString(),eq(5))).thenReturn(new CandidateBatch(CandidateSourceType.DENSE,
                List.of(new RetrievalCandidate(chunk,1.0,0))));
        when(rerank.rerankWithResult(anyString(),anyList(),eq(5))).thenAnswer(invocation ->
                com.rag.backend.agent.rerank.RerankExecutionResult.unobserved(
                        invocation.getArgument(1), invocation.getArgument(1), 0));
        var retriever = new MilvusKnowledgeRetriever(resolver,dense,null,rerank,5,false,60,1,1);
        var a = retriever.retrieveInScope(scope,"query",5);
        var b = retriever.retrieveInScope(scope,"supplement",5);
        assertEquals(100L,a.chunks().getFirst().documentVersionId());
        assertEquals(a.chunks(),b.chunks());
        verifyNoInteractions(resolver);
    }

    @Test void denseRejectsWrongCourseAndPreservesVersionInScores() {
        var chunks = mock(KnowledgeChunkRepository.class);
        var chunk = new KnowledgeChunk();
        chunk.setId(1L); chunk.setDocumentId(10L); chunk.setCourseId(2L);
        chunk.setDocumentVersionId(100L); chunk.setContent("evidence");
        when(chunks.findById(1L)).thenReturn(chunk);
        var dense = new DenseCandidateSource(q -> List.of(1.0),
                (c,v,q,k) -> List.of(new VersionedVectorHit(1,100,0.9)), chunks,null,0);
        var scope = new RetrievalScope(1,Set.of(100L),true);
        assertThrows(MaterialScopeException.class, () -> dense.retrieve(scope,"query",5));
        chunk.setCourseId(1L);
        var result = dense.retrieve(scope,"query",5).candidates().getFirst().chunk();
        assertEquals(100L,result.withFusionScore(0.8).withRerankScore(0.7).documentVersionId());
    }
}
