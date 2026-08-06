package com.rag.backend.ingestionlab.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.backend.document.DocumentService;
import com.rag.backend.document.model.CourseDocument;
import com.rag.backend.ingestionlab.activate.VersionActivationService;
import com.rag.backend.ingestionlab.artifact.ReplayableChunkStage;
import com.rag.backend.ingestionlab.artifact.ReplayableParseStage;
import com.rag.backend.ingestionlab.identity.PipelineManifestProvider;
import com.rag.backend.ingestionlab.outbox.DocumentVersionMapper;
import com.rag.backend.ingestionlab.outbox.DocumentVersionRow;
import com.rag.backend.ingestionlab.state.VersionTransitionService;
import com.rag.backend.ingestionlab.step.StepExecutor;
import com.rag.backend.ingestionlab.vector.ChunkWriteRepository;
import com.rag.backend.ingestionlab.vector.VectorWriteStage;
import com.rag.backend.ingestionlab.verify.IndexVerifier;
import com.rag.backend.ingestionlab.verify.VerificationService;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 验证 Worker 从持久 Version 状态恢复，而不是每次从解析步骤重新开始。 */
class IngestJobOrchestratorResumeTest {

    @Test
    void readyVersionOnlyActivatesAndUpdatesDocumentStatus() {
        Fixture fixture = new Fixture();
        fixture.stubJobAndDocument();
        when(fixture.versions.findById(71L)).thenReturn(
                version("READY", 3),
                version("READY", 3),
                version("ACTIVE", 3));

        fixture.orchestrator.runOwned(fixture.leaseSession());

        verify(fixture.activation).activate(9L, 71L);
        verify(fixture.documents).updateParseStatus(
                9L, CourseDocument.STATUS_PARSED, 3);
        fixture.verifyNoBuildStages();
        verifyNoInteractions(fixture.verification);
    }

    @Test
    void indexingVersionVerifiesAndActivatesWithoutReadingOldArtifacts() {
        Fixture fixture = new Fixture();
        fixture.stubJobAndDocument();
        when(fixture.versions.findById(71L)).thenReturn(
                version("INDEXING", 3),
                version("INDEXING", 3),
                version("READY", 3),
                version("ACTIVE", 3));
        when(fixture.verification.verify(71L)).thenReturn(
                new IndexVerifier.VerificationReport(
                        71L, 3, 3, 3, 3,
                        Set.of(), Set.of(), true, "digest"));

        fixture.orchestrator.runOwned(fixture.leaseSession());

        verify(fixture.verification).verify(71L);
        verify(fixture.activation).activate(9L, 71L);
        verify(fixture.documents).updateParseStatus(
                9L, CourseDocument.STATUS_PARSED, 3);
        fixture.verifyNoBuildStages();
    }

    @Test
    void activeVersionOnlyRepairsDocumentProjectionAfterCrash() {
        Fixture fixture = new Fixture();
        fixture.stubJobAndDocument();
        when(fixture.versions.findById(71L)).thenReturn(
                version("ACTIVE", 3));

        fixture.orchestrator.runOwned(fixture.leaseSession());

        verify(fixture.documents).updateParseStatus(
                9L, CourseDocument.STATUS_PARSED, 3);
        verify(fixture.activation, never()).activate(anyLong(), anyLong());
        fixture.verifyNoBuildStages();
        verifyNoInteractions(fixture.verification);
    }

    @Test
    void tombstonedDocumentStopsBeforeAnyBuildSideEffect() {
        Fixture fixture = new Fixture();
        fixture.stubJobAndDocument();
        when(fixture.versions.findById(71L)).thenReturn(
                version("BUILDING", 3));
        CourseDocument deleting = new CourseDocument();
        deleting.setId(9L);
        deleting.setCourseId(3L);
        deleting.setLifecycleStatus("DELETING");
        when(fixture.documents.getById(9L)).thenReturn(deleting);

        assertThrows(
                IngestJobOrchestrator.DocumentTombstonedException.class,
                () -> fixture.orchestrator.runOwned(fixture.leaseSession()));

        fixture.verifyNoBuildStages();
        verify(fixture.documents, never()).updateParseStatus(
                anyLong(), any(), any());
        verifyNoInteractions(fixture.verification, fixture.activation);
    }

    private static DocumentVersionRow version(String state, int count) {
        DocumentVersionRow row = new DocumentVersionRow();
        row.setId(71L);
        row.setDocumentId(9L);
        row.setState(state);
        row.setStateVersion(4L);
        row.setExpectedChunkCount(count);
        return row;
    }

    private static final class Fixture {
        private final ReplayableParseStage parse = mock(ReplayableParseStage.class);
        private final ReplayableChunkStage chunk = mock(ReplayableChunkStage.class);
        private final DocumentService documents = mock(DocumentService.class);
        private final StepExecutor steps = mock(StepExecutor.class);
        private final VerificationService verification = mock(VerificationService.class);
        private final VersionActivationService activation = mock(VersionActivationService.class);
        private final VectorWriteStage vectors = mock(VectorWriteStage.class);
        private final IngestJobMapper jobs = mock(IngestJobMapper.class);
        private final DocumentVersionMapper versions = mock(DocumentVersionMapper.class);
        private final PipelineManifestProvider manifests = mock(PipelineManifestProvider.class);
        private final ChunkWriteRepository chunks = mock(ChunkWriteRepository.class);
        private final VersionTransitionService transitions = mock(VersionTransitionService.class);
        private final JobLeaseService leases = new JobLeaseService(
                jobs,
                Clock.fixed(Instant.parse("2026-08-04T08:00:00Z"), ZoneOffset.UTC),
                Duration.ofMinutes(5));
        private final IngestJobOrchestrator orchestrator =
                new IngestJobOrchestrator(
                        parse, chunk, documents, steps, verification,
                        activation, vectors, jobs, versions, manifests,
                        chunks, transitions, leases);

        private void stubJobAndDocument() {
            IngestJob job = new IngestJob();
            job.setJobId("job-71");
            job.setDocumentId(9L);
            job.setDocumentVersionId(71L);
            job.setJobType("INGEST");
            when(jobs.selectById("job-71")).thenReturn(job);
            when(jobs.renew(eq("job-71"), eq("worker-a"),
                    any(), any(), anyLong())).thenReturn(1);

            CourseDocument document = new CourseDocument();
            document.setId(9L);
            document.setCourseId(3L);
            document.setFileType("txt");
            document.setFilePath("source-file-is-not-needed-after-indexing.txt");
            document.setLifecycleStatus("ACTIVE");
            when(documents.getById(9L)).thenReturn(document);
        }

        private JobLeaseService.LeaseSession leaseSession() {
            JobLeaseService.Lease lease = new JobLeaseService.Lease(
                    "job-71", "worker-a",
                    java.time.LocalDateTime.ofInstant(
                            Instant.parse("2026-08-04T08:05:00Z"),
                            ZoneOffset.UTC),
                    1L);
            return leases.openSession(lease);
        }

        private void verifyNoBuildStages() {
            verifyNoInteractions(parse, chunk, steps, vectors, manifests, chunks);
            verify(transitions, never()).transition(anyLong(), any());
        }
    }
}
