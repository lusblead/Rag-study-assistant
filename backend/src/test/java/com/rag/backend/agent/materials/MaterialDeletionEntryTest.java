package com.rag.backend.agent.materials;

import com.rag.backend.course.CourseMapper;
import com.rag.backend.course.CourseServiceImpl;
import com.rag.backend.course.model.Course;
import com.rag.backend.document.DocumentMapper;
import com.rag.backend.document.model.CourseDocument;
import com.rag.backend.agent.ingest.AgentDocumentCleanupService;
import com.rag.backend.ingestionlab.delete.*;
import com.rag.backend.ingestionlab.job.*;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MaterialDeletionEntryTest {
    @Test void releasedReadersAllowOrderedDeletionAndFinalTombstoneCompletion() {
        var guard = mock(MaterialReclamationService.class);
        var mapper = mock(DeleteRequestMapper.class);
        var vectors = mock(com.rag.backend.ingestionlab.vector.ConsistentVectorStore.class);
        var artifacts = mock(com.rag.backend.ingestionlab.artifact.ArtifactStore.class);
        var row = new DeleteRequestMapper.DeleteDocumentRow();
        row.setId(10L); row.setLifecycleStatus("DELETING");
        when(mapper.lockDocument(10)).thenReturn(row);
        when(mapper.selectVersionIds(10)).thenReturn(List.of(100L));
        when(mapper.markDeleted(10)).thenReturn(1);
        var port = new MyBatisDeletePort(mapper,vectors,artifacts,"target/materials-test-uploads");
        port.setReclamation(guard);
        assertFalse(new DeleteSaga(port).execute(10).alreadyDeleted());
        var order = inOrder(guard,mapper,vectors,artifacts);
        order.verify(guard).awaitDocumentReaders(10);
        order.verify(mapper).lockDocument(10);
        order.verify(mapper).selectVersionIds(10);
        order.verify(vectors).deleteByVersion(100);
        order.verify(artifacts).deletePrefix("100/");
        order.verify(mapper).deleteChunks(10);
        order.verify(mapper).markDeleted(10);
    }

    @Test void deletingCourseQueuesDocumentWithdrawalBeforeAnyLegacyCleanup() {
        var courses = mock(CourseMapper.class);
        var documents = mock(DocumentMapper.class);
        var cleanup = mock(AgentDocumentCleanupService.class);
        var requests = mock(DeleteRequestService.class);
        when(courses.selectById(1L)).thenReturn(new Course());
        var document = new CourseDocument(); document.setId(10L);
        when(documents.selectListByCourseId(1L)).thenReturn(List.of(document));
        var service = new CourseServiceImpl(courses,documents,null,null,null,cleanup,null,null,null);
        service.setDeleteRequests(requests);
        assertThrows(CourseServiceImpl.CourseDeletionPendingException.class, () -> service.delete(1L));
        verify(requests).request(10);
        verifyNoInteractions(cleanup);
        verify(courses,never()).deleteById(anyLong());
    }

    @Test void deletePortCannotStartPhysicalEffectsWhileReaderExists() {
        var guard = mock(MaterialReclamationService.class);
        doThrow(new MaterialReclamationService.ReadersActiveException()).when(guard).awaitDocumentReaders(10);
        var mapper = mock(DeleteRequestMapper.class);
        var vectors = mock(com.rag.backend.ingestionlab.vector.ConsistentVectorStore.class);
        var artifacts = mock(com.rag.backend.ingestionlab.artifact.ArtifactStore.class);
        var port = new MyBatisDeletePort(mapper,vectors,artifacts,"target/materials-test-uploads");
        port.setReclamation(guard);
        assertThrows(MaterialReclamationService.ReadersActiveException.class, () -> new DeleteSaga(port).execute(10));
        verifyNoInteractions(mapper,vectors,artifacts);
    }

    @Test void waitingForReadersDefersJobWithoutExhaustingFailureRetries() {
        var leases = mock(JobLeaseService.class);
        var jobs = mock(IngestJobMapper.class);
        var saga = mock(DeleteSaga.class);
        var lease = new JobLeaseService.Lease("job","worker", LocalDateTime.now().plusMinutes(5),1);
        var session = mock(JobLeaseService.LeaseSession.class);
        when(leases.claim(eq("job"),anyString())).thenReturn(lease);
        when(leases.openSession(lease)).thenReturn(session);
        when(session.current()).thenReturn(lease);
        var job = new IngestJob(); job.setJobType("DELETE"); job.setDocumentId(10L);
        when(jobs.selectById("job")).thenReturn(job);
        when(saga.execute(10)).thenThrow(new MaterialReclamationService.ReadersActiveException());
        new DeleteJobWorker(leases,jobs,saga).run("job");
        verify(leases).deferDeleteForReaders(lease);
        verify(leases,never()).retry(any(),anyString(),anyString(),any());
        verify(leases,never()).succeed(any());
    }
}
