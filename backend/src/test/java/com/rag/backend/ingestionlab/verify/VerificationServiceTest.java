package com.rag.backend.ingestionlab.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.backend.ingestionlab.outbox.DocumentVersionMapper;
import com.rag.backend.ingestionlab.outbox.DocumentVersionRow;
import com.rag.backend.ingestionlab.state.DocumentVersionState;
import com.rag.backend.ingestionlab.state.VersionTransitionService;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 验证用例层的顺序和目标状态。
 * IndexVerifier 的集合算法已有独立测试；这里不重复测试算法细节。
 */
class VerificationServiceTest {

    @Test
    void exactInventoryBecomesReadyAndPersistsTheSameReport() {
        Fixture fixture = new Fixture();
        DocumentVersionRow indexing = fixture.version("INDEXING", 7L, 2);
        DocumentVersionRow verifying = fixture.version("VERIFYING", 8L, 2);
        var report = new IndexVerifier.VerificationReport(
                9L, 2, 2, 2, 2,
                Set.of(), Set.of(), true, "digest-ready");

        when(fixture.mapper.findById(9L)).thenReturn(indexing);
        when(fixture.transitions.transition(
                9L, DocumentVersionState.VERIFYING))
                .thenReturn(verifying);
        when(fixture.verifier.verify(9L, 2)).thenReturn(report);

        fixture.service.verify(9L);

        verify(fixture.resultWriter).write(
                same(verifying),
                eq(DocumentVersionState.READY),
                same(report),
                contains("\"passed\":true"));
    }

    @Test
    void equalCountButDifferentIdsBecomesInconsistent() {
        Fixture fixture = new Fixture();
        DocumentVersionRow verifying =
                fixture.version("VERIFYING", 8L, 2);
        var report = new IndexVerifier.VerificationReport(
                9L, 2, 2, 2, 2,
                Set.of(200L), Set.of(999L),
                false, "digest-inconsistent");

        when(fixture.mapper.findById(9L)).thenReturn(verifying);
        when(fixture.verifier.verify(9L, 2)).thenReturn(report);

        fixture.service.verify(9L);

        verify(fixture.resultWriter).write(
                same(verifying),
                eq(DocumentVersionState.INCONSISTENT),
                same(report),
                contains("\"passed\":false"));
        verifyNoInteractions(fixture.transitions);
    }

    @Test
    void verifierFailureDoesNotPersistReadyOrInconsistent() {
        Fixture fixture = new Fixture();
        DocumentVersionRow verifying =
                fixture.version("VERIFYING", 8L, 2);

        when(fixture.mapper.findById(9L)).thenReturn(verifying);
        when(fixture.verifier.verify(9L, 2))
                .thenThrow(new IllegalStateException("milvus unavailable"));

        assertThrows(IllegalStateException.class,
                () -> fixture.service.verify(9L));

        verifyNoInteractions(fixture.resultWriter);
    }

    /**
     * 把依赖创建集中起来，让每个测试只描述业务差异。
     */
    private static final class Fixture {
        private final DocumentVersionMapper mapper =
                mock(DocumentVersionMapper.class);
        private final VersionTransitionService transitions =
                mock(VersionTransitionService.class);
        private final IndexVerifier verifier = mock(IndexVerifier.class);
        private final VerificationResultWriter resultWriter =
                mock(VerificationResultWriter.class);
        private final VerificationService service =
                new VerificationService(
                        mapper,
                        transitions,
                        verifier,
                        resultWriter,
                        new ObjectMapper());

        private DocumentVersionRow version(
                String state,
                long stateVersion,
                int expectedChunkCount) {
            DocumentVersionRow row = new DocumentVersionRow();
            row.setId(9L);
            row.setState(state);
            row.setStateVersion(stateVersion);
            row.setExpectedChunkCount(expectedChunkCount);
            return row;
        }
    }
}