package com.rag.backend.ingestionlab.observe;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.backend.ingestionlab.config.ReliableIngestionConfiguration;
import com.rag.backend.ingestionlab.delete.DeleteJobWorker;
import com.rag.backend.ingestionlab.job.AsyncJobWakeupAdapter;
import com.rag.backend.ingestionlab.job.DurableJobWorker;
import com.rag.backend.ingestionlab.job.IngestJob;
import com.rag.backend.ingestionlab.job.IngestJobMapper;
import com.rag.backend.ingestionlab.job.IngestJobPoller;
import com.rag.backend.ingestionlab.job.IngestJobWorker;
import com.rag.backend.ingestionlab.job.JobWakeupPort;
import com.rag.backend.ingestionlab.outbox.DocumentVersionMapper;
import com.rag.backend.ingestionlab.outbox.DocumentVersionRow;
import com.rag.backend.ingestionlab.outbox.IngestRequestedEventPayload;
import com.rag.backend.ingestionlab.outbox.OutboxClaimService;
import com.rag.backend.ingestionlab.outbox.OutboxDispatcher;
import com.rag.backend.ingestionlab.outbox.OutboxEvent;
import com.rag.backend.ingestionlab.outbox.OutboxEventMapper;
import com.rag.backend.ingestionlab.outbox.ReliableIngestSubmitter;
import com.rag.backend.observability.trace.TraceCarrier;
import com.rag.backend.observability.trace.TraceContextService;
import com.rag.backend.observability.trace.TraceSpan;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IngestionTracePropagationTest {
    private static final String TRACE_ID = "1".repeat(32);

    private ThreadPoolTaskExecutor executor;

    @AfterEach
    void cleanUp() {
        if (executor != null) {
            executor.shutdown();
        }
        MDC.clear();
    }

    @Test
    void outboxExecutorAndWorkerContinueThePersistedSubmitTrace()
            throws Exception {
        List<TraceContextService.TraceEvent> events =
                new CopyOnWriteArrayList<>();
        TraceContextService traces = service(events);

        String jobId = "job-trace-1";
        TraceCarrier submitCarrier;
        try (TraceSpan submit = traces.startRoot("ingestion.submit", jobId)) {
            submitCarrier = submit.carrier();
            submit.result("success");
        }

        IngestJob job = new IngestJob();
        job.setJobId(jobId);
        job.setJobType("INGEST");
        job.setSubmitTraceparent(submitCarrier.traceparent());
        IngestJobMapper jobs = mock(IngestJobMapper.class);
        when(jobs.selectById(jobId)).thenReturn(job);

        CountDownLatch workerRan = new CountDownLatch(1);
        AtomicReference<String> workerTraceId = new AtomicReference<>();
        AtomicReference<String> workerCorrelationId = new AtomicReference<>();
        IngestJobWorker ingestWorker = mock(IngestJobWorker.class);
        doAnswer(invocation -> {
            workerTraceId.set(MDC.get(TraceContextService.TRACE_ID_MDC_KEY));
            workerCorrelationId.set(
                    MDC.get(TraceContextService.CORRELATION_ID_MDC_KEY));
            workerRan.countDown();
            return null;
        }).when(ingestWorker).run(jobId);
        DurableJobWorker durableWorker = new DurableJobWorker(
                jobs, ingestWorker, mock(DeleteJobWorker.class), traces);

        executor = (ThreadPoolTaskExecutor)
                new ReliableIngestionConfiguration()
                        .ingestionJobExecutor(traces);
        AsyncJobWakeupAdapter wakeup = new AsyncJobWakeupAdapter(
                executor, durableWorker);

        ObjectMapper objectMapper = new ObjectMapper();
        String payload = objectMapper.writeValueAsString(
                IngestRequestedEventPayload.current(
                        jobId, 71L, submitCarrier));
        OutboxClaimService claimService = mock(OutboxClaimService.class);
        OutboxEventMapper outbox = mock(OutboxEventMapper.class);
        OutboxClaimService.Claim claim = new OutboxClaimService.Claim(
                "event-1", jobId, "INGEST_REQUESTED", payload,
                "claim-owner", LocalDateTime.now().plusMinutes(1), 1L, 1);
        when(claimService.claimBatch(anyString(), eq(10)))
                .thenReturn(List.of(claim));
        when(outbox.markPublished(
                eq("event-1"), eq("claim-owner"), eq(1L),
                org.mockito.ArgumentMatchers.any(LocalDateTime.class)))
                .thenReturn(1);

        new OutboxDispatcher(
                claimService, outbox, wakeup, objectMapper, traces).dispatch();

        assertTrue(workerRan.await(5, TimeUnit.SECONDS));
        assertEquals(TRACE_ID, workerTraceId.get());
        assertEquals(jobId, workerCorrelationId.get());
        assertNull(MDC.get(TraceContextService.TRACE_ID_MDC_KEY));

        TraceContextService.TraceEvent submit = start(events, "ingestion.submit");
        TraceContextService.TraceEvent dispatch =
                start(events, "ingestion.outbox.dispatch");
        TraceContextService.TraceEvent executorStart =
                start(events, "ingestion.executor");
        TraceContextService.TraceEvent route =
                start(events, "ingestion.durable.route");
        assertEquals(submit.traceId(), dispatch.traceId());
        assertEquals(submit.spanId(), dispatch.parentSpanId());
        assertEquals(dispatch.traceId(), executorStart.traceId());
        assertEquals(dispatch.spanId(), executorStart.parentSpanId());
        assertEquals(executorStart.traceId(), route.traceId());
        assertEquals(executorStart.spanId(), route.parentSpanId());
    }

    @Test
    void pollerWakeupRecoversJobCarrierInsideDurableWorkerWithoutExecutorSpan()
            throws Exception {
        List<TraceContextService.TraceEvent> events =
                new CopyOnWriteArrayList<>();
        TraceContextService traces = service(events);
        String jobId = "job-poller-recovery";
        TraceCarrier submitCarrier;
        try (TraceSpan submit = traces.startRoot("ingestion.submit", jobId)) {
            submitCarrier = submit.carrier();
            submit.result("prepared");
        }

        IngestJob job = new IngestJob();
        job.setJobId(jobId);
        job.setJobType("INGEST");
        job.setSubmitTraceparent(submitCarrier.traceparent());
        IngestJobMapper jobs = mock(IngestJobMapper.class);
        when(jobs.selectById(jobId)).thenReturn(job);
        when(jobs.findDueJobIds(any(LocalDateTime.class), eq(20)))
                .thenReturn(List.of(jobId));
        CountDownLatch workerRan = new CountDownLatch(1);
        IngestJobWorker ingestWorker = mock(IngestJobWorker.class);
        doAnswer(invocation -> {
            assertEquals(TRACE_ID,
                    MDC.get(TraceContextService.TRACE_ID_MDC_KEY));
            assertEquals(jobId,
                    MDC.get(TraceContextService.CORRELATION_ID_MDC_KEY));
            workerRan.countDown();
            return null;
        }).when(ingestWorker).run(jobId);
        DurableJobWorker durableWorker = new DurableJobWorker(
                jobs, ingestWorker, mock(DeleteJobWorker.class), traces);
        executor = (ThreadPoolTaskExecutor)
                new ReliableIngestionConfiguration()
                        .ingestionJobExecutor(traces);
        AsyncJobWakeupAdapter wakeup = new AsyncJobWakeupAdapter(
                executor, durableWorker);

        new IngestJobPoller(jobs, wakeup).poll();

        assertTrue(workerRan.await(5, TimeUnit.SECONDS));
        TraceContextService.TraceEvent submit =
                start(events, "ingestion.submit");
        TraceContextService.TraceEvent recovery =
                start(events, "ingestion.job.recovery");
        TraceContextService.TraceEvent route =
                start(events, "ingestion.durable.route");
        assertEquals(submit.traceId(), recovery.traceId());
        assertEquals(submit.spanId(), recovery.parentSpanId());
        assertEquals(recovery.spanId(), route.parentSpanId());
        assertTrue(events.stream().noneMatch(event ->
                "ingestion.executor".equals(event.operation())));
        assertNull(MDC.get(TraceContextService.TRACE_ID_MDC_KEY));
    }

    @Test
    void missingDurableJobHasExplicitJobNotFoundTerminal() {
        List<TraceContextService.TraceEvent> events =
                new CopyOnWriteArrayList<>();
        TraceContextService traces = service(events);
        IngestJobMapper jobs = mock(IngestJobMapper.class);
        IngestJobWorker ingestWorker = mock(IngestJobWorker.class);
        DeleteJobWorker deleteWorker = mock(DeleteJobWorker.class);

        new DurableJobWorker(jobs, ingestWorker, deleteWorker, traces)
                .run("job-missing");

        TraceContextService.TraceEvent route =
                end(events, "ingestion.durable.route");
        assertEquals("job_not_found", route.result());
        assertEquals("job-missing", route.correlationId());
        verify(ingestWorker, never()).run(anyString());
        verify(deleteWorker, never()).run(anyString());
    }

    @Test
    void nonHttpSubmissionPersistsAValidCarrier() {
        List<TraceContextService.TraceEvent> events =
                new CopyOnWriteArrayList<>();
        TraceContextService traces = service(events);
        DocumentVersionMapper versions = mock(DocumentVersionMapper.class);
        IngestJobMapper jobs = mock(IngestJobMapper.class);
        OutboxEventMapper outbox = mock(OutboxEventMapper.class);
        DocumentVersionMapper.LockedDocumentRow document =
                new DocumentVersionMapper.LockedDocumentRow();
        document.setId(99L);
        document.setLifecycleStatus("ACTIVE");
        when(versions.lockDocument(99L)).thenReturn(document);
        when(versions.nextVersionNo(99L)).thenReturn(1);
        doAnswer(invocation -> {
            invocation.getArgument(0, DocumentVersionRow.class).setId(71L);
            return 1;
        }).when(versions).insert(any(DocumentVersionRow.class));
        when(versions.transition(71L, "UPLOADED", "BUILDING", 0L))
                .thenReturn(1);

        ReliableIngestSubmitter.Submission submission =
                new ReliableIngestSubmitter(
                        versions, jobs, outbox, new ObjectMapper(), traces)
                        .submit(99L, "content-hash", "pipeline-fingerprint");

        ArgumentCaptor<IngestJob> jobCaptor =
                ArgumentCaptor.forClass(IngestJob.class);
        verify(jobs).insert(jobCaptor.capture());
        IngestJob persistedJob = jobCaptor.getValue();
        assertEquals(submission.jobId(), persistedJob.getJobId());
        assertNotNull(persistedJob.getSubmitTraceparent());
        assertTrue(persistedJob.getSubmitTraceparent().matches(
                "00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}"));

        ArgumentCaptor<OutboxEvent> eventCaptor =
                ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outbox).insert(eventCaptor.capture());
        IngestRequestedEventPayload payload =
                IngestRequestedEventPayload.decode(
                        new ObjectMapper(),
                        eventCaptor.getValue().getPayloadJson(),
                        persistedJob.getJobId());
        assertEquals(persistedJob.getSubmitTraceparent(), payload.traceparent());
        assertEquals(persistedJob.getJobId(), payload.correlationId());
        assertEquals(TRACE_ID, start(events, "ingestion.submit").traceId());
        TraceContextService.TraceEvent prepared = end(
                events, "ingestion.submit");
        assertEquals("prepared", prepared.result());
        assertNull(prepared.correlationId());
        assertNull(MDC.get(TraceContextService.TRACE_ID_MDC_KEY));
    }

    @Test
    void reusedSubmissionLinksOriginalCarrierUnderJobCorrelation() {
        List<TraceContextService.TraceEvent> events =
                new CopyOnWriteArrayList<>();
        TraceContextService traces = service(events);
        DocumentVersionMapper versions = mock(DocumentVersionMapper.class);
        IngestJobMapper jobs = mock(IngestJobMapper.class);
        OutboxEventMapper outbox = mock(OutboxEventMapper.class);
        DocumentVersionMapper.LockedDocumentRow document =
                new DocumentVersionMapper.LockedDocumentRow();
        document.setId(99L);
        document.setLifecycleStatus("ACTIVE");
        when(versions.lockDocument(99L)).thenReturn(document);
        DocumentVersionRow existingVersion = new DocumentVersionRow();
        existingVersion.setId(71L);
        when(versions.findIdentity(
                99L, "content-hash", "pipeline-fingerprint"))
                .thenReturn(existingVersion);
        String originalTraceparent = "00-" + "a".repeat(32)
                + "-" + "b".repeat(16) + "-00";
        IngestJob existingJob = new IngestJob();
        existingJob.setJobId("job-existing");
        existingJob.setJobType("INGEST");
        existingJob.setDocumentVersionId(71L);
        existingJob.setSubmitTraceparent(originalTraceparent);
        when(jobs.selectByVersionAndType(71L, "INGEST"))
                .thenReturn(existingJob);

        ReliableIngestSubmitter.Submission submission;
        try (TraceSpan request = traces.startRoot(
                "ingestion.http", "request-correlation")) {
            submission = new ReliableIngestSubmitter(
                    versions, jobs, outbox, new ObjectMapper(), traces)
                    .submit(99L, "content-hash", "pipeline-fingerprint");
        }

        assertTrue(submission.reused());
        assertEquals(originalTraceparent, existingJob.getSubmitTraceparent());
        TraceContextService.TraceEvent link = events.stream()
                .filter(event -> event.type()
                        == TraceContextService.EventType.LINK)
                .findFirst()
                .orElseThrow();
        assertEquals("job-existing", link.correlationId());
        assertEquals("a".repeat(32), link.detail());
        verify(jobs, never()).insert(any(IngestJob.class));
        verify(outbox, never()).insert(any(OutboxEvent.class));
        verify(versions, never()).insert(any(DocumentVersionRow.class));
    }

    @Test
    void recoveryRouteFailureHasNoUnknownTerminalAndUsesJobCorrelation() {
        List<TraceContextService.TraceEvent> events =
                new CopyOnWriteArrayList<>();
        TraceContextService traces = service(events);
        IngestJobMapper jobs = mock(IngestJobMapper.class);
        IngestJobWorker ingestWorker = mock(IngestJobWorker.class);
        IngestJob legacy = new IngestJob();
        legacy.setJobId("job-legacy");
        legacy.setJobType("INGEST");
        when(jobs.selectById("job-legacy")).thenReturn(legacy);
        doThrow(new IllegalStateException("sensitive failure detail"))
                .when(ingestWorker).run("job-legacy");
        DurableJobWorker durable = new DurableJobWorker(
                jobs, ingestWorker, mock(DeleteJobWorker.class), traces);

        assertThrows(IllegalStateException.class,
                () -> durable.run("job-legacy"));

        TraceContextService.TraceEvent recovery = end(
                events, "ingestion.job.recovery");
        TraceContextService.TraceEvent route = end(
                events, "ingestion.durable.route");
        assertEquals("failed", recovery.result());
        assertEquals("failed", route.result());
        assertEquals("job-legacy", recovery.correlationId());
        assertEquals("job-legacy", route.correlationId());
        assertEquals("illegalstateexception", recovery.detail());
        assertTrue(events.stream().noneMatch(event ->
                event.toString().contains("sensitive failure detail")));
    }

    @Test
    void malformedPayloadIsRetriedWithoutAbortingTheFollowingClaim()
            throws Exception {
        List<TraceContextService.TraceEvent> events =
                new CopyOnWriteArrayList<>();
        TraceContextService traces = service(events);
        OutboxClaimService claimService = mock(OutboxClaimService.class);
        OutboxEventMapper outbox = mock(OutboxEventMapper.class);
        JobWakeupPort wakeup = mock(JobWakeupPort.class);
        LocalDateTime deadline = LocalDateTime.now().plusMinutes(1);
        OutboxClaimService.Claim malformed = new OutboxClaimService.Claim(
                "event-bad", "job-bad", "INGEST_REQUESTED", "[",
                "claim-owner", deadline, 1L, 5);
        OutboxClaimService.Claim missingV1Carrier =
                new OutboxClaimService.Claim(
                        "event-missing", "job-missing", "INGEST_REQUESTED",
                        "{\"schemaVersion\":1,\"jobId\":\"job-missing\","
                                + "\"documentVersionId\":73,"
                                + "\"correlationId\":\"job-missing\"}",
                        "claim-owner", deadline, 2L, 1);
        OutboxClaimService.Claim invalidV1Carrier =
                new OutboxClaimService.Claim(
                        "event-invalid", "job-invalid", "INGEST_REQUESTED",
                        "{\"schemaVersion\":1,\"jobId\":\"job-invalid\","
                                + "\"documentVersionId\":74,"
                                + "\"traceparent\":\"not-a-traceparent\","
                                + "\"correlationId\":\"job-invalid\"}",
                        "claim-owner", deadline, 3L, 1);
        OutboxClaimService.Claim legacy = new OutboxClaimService.Claim(
                "event-good", "job-good", "INGEST_REQUESTED",
                "{\"jobId\":\"job-good\",\"documentVersionId\":72}",
                "claim-owner", deadline, 4L, 1);
        when(claimService.claimBatch(anyString(), eq(10)))
                .thenReturn(List.of(
                        malformed, missingV1Carrier, invalidV1Carrier, legacy));
        when(outbox.markRetry(
                eq("event-bad"), eq("claim-owner"), eq(1L),
                any(LocalDateTime.class), any(LocalDateTime.class),
                eq("OUTBOX_PAYLOAD_INVALID"), eq(true)))
                .thenReturn(1);
        when(outbox.markRetry(
                eq("event-missing"), eq("claim-owner"), eq(2L),
                any(LocalDateTime.class), any(LocalDateTime.class),
                eq("OUTBOX_PAYLOAD_INVALID"), eq(false)))
                .thenReturn(1);
        when(outbox.markRetry(
                eq("event-invalid"), eq("claim-owner"), eq(3L),
                any(LocalDateTime.class), any(LocalDateTime.class),
                eq("OUTBOX_PAYLOAD_INVALID"), eq(false)))
                .thenReturn(1);
        when(outbox.markPublished(
                eq("event-good"), eq("claim-owner"), eq(4L),
                any(LocalDateTime.class)))
                .thenReturn(1);

        new OutboxDispatcher(
                claimService, outbox, wakeup, new ObjectMapper(), traces)
                .dispatch();

        verify(wakeup, never()).wakeup("job-bad");
        verify(wakeup, never()).wakeup("job-missing");
        verify(wakeup, never()).wakeup("job-invalid");
        verify(wakeup).wakeup("job-good");
        verify(outbox).markRetry(
                eq("event-bad"), eq("claim-owner"), eq(1L),
                any(LocalDateTime.class), any(LocalDateTime.class),
                eq("OUTBOX_PAYLOAD_INVALID"), eq(true));
        verify(outbox).markRetry(
                eq("event-missing"), eq("claim-owner"), eq(2L),
                any(LocalDateTime.class), any(LocalDateTime.class),
                eq("OUTBOX_PAYLOAD_INVALID"), eq(false));
        verify(outbox).markRetry(
                eq("event-invalid"), eq("claim-owner"), eq(3L),
                any(LocalDateTime.class), any(LocalDateTime.class),
                eq("OUTBOX_PAYLOAD_INVALID"), eq(false));
        verify(outbox).markPublished(
                eq("event-good"), eq("claim-owner"), eq(4L),
                any(LocalDateTime.class));
        assertTrue(events.stream().anyMatch(event ->
                event.type() == TraceContextService.EventType.END
                        && "ingestion.outbox.dispatch".equals(
                        event.operation())
                        && "job-good".equals(event.correlationId())
                        && "success".equals(event.result())));
    }

    @Test
    void outboxRetryClaimLossIsSanitizedAndDoesNotAbortFollowingClaim()
            throws Exception {
        List<TraceContextService.TraceEvent> events =
                new CopyOnWriteArrayList<>();
        TraceContextService traces = service(events);
        OutboxClaimService claimService = mock(OutboxClaimService.class);
        OutboxEventMapper outbox = mock(OutboxEventMapper.class);
        JobWakeupPort wakeup = mock(JobWakeupPort.class);
        LocalDateTime deadline = LocalDateTime.now().plusMinutes(1);
        OutboxClaimService.Claim casLost = new OutboxClaimService.Claim(
                "event-cas", "job-cas", "INGEST_REQUESTED",
                "{\"secret\":\"payload-secret\"}",
                "claim-owner", deadline, 1L, 1);
        OutboxClaimService.Claim persistenceFailure =
                new OutboxClaimService.Claim(
                        "event-throw", "job-throw", "INGEST_REQUESTED",
                        "{\"secret\":\"payload-secret-2\"}",
                        "claim-owner", deadline, 2L, 1);
        OutboxClaimService.Claim following =
                new OutboxClaimService.Claim(
                        "event-good", "job-good", "INGEST_REQUESTED",
                        "{\"jobId\":\"job-good\","
                                + "\"documentVersionId\":72}",
                        "claim-owner", deadline, 3L, 1);
        when(claimService.claimBatch(anyString(), eq(10)))
                .thenReturn(List.of(casLost, persistenceFailure, following));
        when(outbox.markRetry(
                eq("event-cas"), eq("claim-owner"), eq(1L),
                any(LocalDateTime.class), any(LocalDateTime.class),
                eq("OUTBOX_PAYLOAD_INVALID"), eq(false)))
                .thenReturn(0);
        when(outbox.markRetry(
                eq("event-throw"), eq("claim-owner"), eq(2L),
                any(LocalDateTime.class), any(LocalDateTime.class),
                eq("OUTBOX_PAYLOAD_INVALID"), eq(false)))
                .thenThrow(new IllegalStateException("persistence-secret"));
        when(outbox.markPublished(
                eq("event-good"), eq("claim-owner"), eq(3L),
                any(LocalDateTime.class)))
                .thenReturn(1);

        new OutboxDispatcher(
                claimService, outbox, wakeup, new ObjectMapper(), traces)
                .dispatch();

        verify(wakeup, never()).wakeup("job-cas");
        verify(wakeup, never()).wakeup("job-throw");
        verify(wakeup).wakeup("job-good");
        verify(outbox).markPublished(
                eq("event-good"), eq("claim-owner"), eq(3L),
                any(LocalDateTime.class));
        TraceContextService.TraceEvent casEnd = end(
                events, "ingestion.outbox.dispatch", "job-cas");
        TraceContextService.TraceEvent persistenceEnd = end(
                events, "ingestion.outbox.dispatch", "job-throw");
        TraceContextService.TraceEvent followingEnd = end(
                events, "ingestion.outbox.dispatch", "job-good");
        assertEquals("claim_lost", casEnd.result());
        assertEquals("illegalargumentexception", casEnd.detail());
        assertEquals("claim_lost", persistenceEnd.result());
        assertEquals("illegalstateexception", persistenceEnd.detail());
        assertEquals("success", followingEnd.result());
        assertTrue(events.stream().noneMatch(event -> {
            String value = event.toString();
            return value.contains("payload-secret")
                    || value.contains("persistence-secret");
        }));
    }

    private TraceContextService.TraceEvent start(
            List<TraceContextService.TraceEvent> events,
            String operation) {
        return events.stream()
                .filter(event -> event.type()
                        == TraceContextService.EventType.START)
                .filter(event -> operation.equals(event.operation()))
                .findFirst()
                .orElseThrow();
    }

    private TraceContextService.TraceEvent end(
            List<TraceContextService.TraceEvent> events,
            String operation) {
        return events.stream()
                .filter(event -> event.type()
                        == TraceContextService.EventType.END)
                .filter(event -> operation.equals(event.operation()))
                .findFirst()
                .orElseThrow();
    }

    private TraceContextService.TraceEvent end(
            List<TraceContextService.TraceEvent> events,
            String operation,
            String correlationId) {
        return events.stream()
                .filter(event -> event.type()
                        == TraceContextService.EventType.END)
                .filter(event -> operation.equals(event.operation()))
                .filter(event -> correlationId.equals(event.correlationId()))
                .findFirst()
                .orElseThrow();
    }

    private TraceContextService service(
            List<TraceContextService.TraceEvent> events) {
        AtomicInteger spanIds = new AtomicInteger();
        return TraceContextService.forTesting(
                new TraceContextService.IdGenerator() {
                    @Override
                    public String nextTraceId() {
                        return TRACE_ID;
                    }

                    @Override
                    public String nextSpanId() {
                        return "%016x".formatted(spanIds.incrementAndGet());
                    }
                },
                events::add);
    }
}
