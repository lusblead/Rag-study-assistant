package com.rag.backend.agent.materials;

import com.rag.backend.ingestionlab.artifact.ArtifactStore;
import com.rag.backend.ingestionlab.vector.ConsistentVectorStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SessionMaterialServiceTest {
    JdbcTemplate jdbc;
    DataSourceTransactionManager manager;
    SessionMaterialService materials;
    MaterialReclamationService gc;
    ConsistentVectorStore vectors;
    ArtifactStore artifacts;

    @BeforeEach void setup() {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("db/session-material-base.sql"),
                new ClassPathResource("db/migration/V6__session_material_versions.sql")).execute(ds);
        jdbc = new JdbcTemplate(ds);
        manager = new DataSourceTransactionManager(ds);
        materials = new SessionMaterialService(jdbc, manager, Duration.ofDays(7));
        vectors = mock(ConsistentVectorStore.class);
        artifacts = mock(ArtifactStore.class);
        gc = new MaterialReclamationService(jdbc, manager, vectors, artifacts, Duration.ZERO);
        jdbc.update("INSERT INTO documents(id,course_id,filename,active_version_id) VALUES (10,1,'course.txt',100)");
        jdbc.update("INSERT INTO document_versions(id,document_id,version_no,state,expected_chunk_count) VALUES (100,10,1,'ACTIVE',1)");
        jdbc.update("INSERT INTO knowledge_chunks(id,document_id,course_id,document_version_id,content) VALUES (1,10,1,100,'original evidence')");
        jdbc.update("INSERT INTO chat_sessions(id,course_id) VALUES (1,1),(2,1)");
    }

    void publish() {
        new TransactionTemplate(manager).executeWithoutResult(ignored -> {
            jdbc.queryForList("SELECT id FROM documents WHERE id=10 FOR UPDATE");
            jdbc.update("INSERT INTO document_versions(id,document_id,version_no,state,expected_chunk_count) VALUES (101,10,2,'ACTIVE',1)");
            jdbc.update("INSERT INTO knowledge_chunks(id,document_id,course_id,document_version_id,content) VALUES (2,10,1,101,'new evidence')");
            jdbc.update("UPDATE document_versions SET state='SUPERSEDED',superseded_at=CURRENT_TIMESTAMP WHERE id=100");
            jdbc.update("UPDATE documents SET active_version_id=101 WHERE id=10");
        });
    }

    void expire() { jdbc.update("UPDATE chat_material_scopes SET expires_at=TIMESTAMP '2000-01-01 00:00:00'"); }

    @Test void oldSessionSurvivesPublicationAndServiceRestartWhileNewSessionUsesNewVersion() {
        try (var first = materials.acquire(1,1)) { assertEquals(Set.of(100L), first.scope().activeVersionIds()); }
        publish();
        var restarted = new SessionMaterialService(jdbc, manager, Duration.ofDays(7));
        try (var old = restarted.acquire(1,1); var fresh = restarted.acquire(2,1)) {
            assertEquals(Set.of(100L), old.scope().activeVersionIds());
            assertTrue(old.status().updateAvailable());
            assertEquals(Set.of(101L), fresh.scope().activeVersionIds());
            assertFalse(gc.reclaim(100));
        }
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM material_readers", Integer.class));
    }

    @Test void firstConcurrentRequestsBindOnce() throws Exception {
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            Callable<Set<Long>> work = () -> {
                start.await();
                try (var read = materials.acquire(1,1)) { return read.scope().activeVersionIds(); }
            };
            var a = pool.submit(work); var b = pool.submit(work); start.countDown();
            assertEquals(a.get(5, TimeUnit.SECONDS), b.get(5, TimeUnit.SECONDS));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM chat_material_scopes", Integer.class));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM chat_material_versions", Integer.class));
        }
    }

    @Test void retentionAndInFlightProtectionBothPrecedeReclamation() {
        var read = materials.acquire(1,1);
        publish();
        assertFalse(gc.reclaim(100));
        expire();
        assertFalse(gc.reclaim(100));
        assertEquals("RETIRING", jdbc.queryForObject("SELECT read_status FROM document_versions WHERE id=100", String.class));
        verifyNoInteractions(vectors, artifacts);
        assertThrows(MaterialScopeException.class, () -> materials.acquire(1,1));
        assertThrows(MaterialScopeException.class, read::validate);
        read.close();
        assertTrue(gc.reclaim(100));
        verify(vectors).deleteByVersion(100);
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_chunks WHERE document_version_id=100", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_chunks WHERE document_version_id=101", Integer.class));
    }

    @Test void withdrawalWaitsForRealReaderAndRejectsLateSubmission() {
        try (var read = materials.acquire(1,1)) {
            jdbc.update("UPDATE documents SET lifecycle_status='DELETING',active_version_id=NULL WHERE id=10");
            assertEquals("DOCUMENT_WITHDRAWN", materials.status(1,1).reason());
            assertThrows(MaterialReclamationService.ReadersActiveException.class, () -> gc.awaitDocumentReaders(10));
            assertThrows(MaterialScopeException.class, read::validate);
            assertThrows(MaterialScopeException.class, () -> materials.complete(1,1, () -> fail("must not persist")));
        }
        assertDoesNotThrow(() -> gc.awaitDocumentReaders(10));
    }

    @Test void crashedReaderNeverExpiresJustBecauseSessionDoes() {
        materials.acquire(1,1); // Simulated crash: intentionally no close.
        publish(); expire();
        assertFalse(gc.reclaim(100));
        var restartedGc = new MaterialReclamationService(jdbc, manager, vectors, artifacts, Duration.ZERO);
        assertFalse(restartedGc.reclaim(100));
        verifyNoInteractions(vectors, artifacts);
    }

    @Test void failedExternalDeletionIsResumableWithoutReopeningAdmission() {
        try (var read = materials.acquire(1,1)) { read.validate(); }
        publish(); expire();
        doThrow(new IllegalStateException("injected Milvus failure")).doNothing().when(vectors).deleteByVersion(100);
        assertThrows(IllegalStateException.class, () -> gc.reclaim(100));
        assertEquals("RETIRING", jdbc.queryForObject("SELECT read_status FROM document_versions WHERE id=100", String.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_chunks WHERE document_version_id=100", Integer.class));
        assertTrue(gc.reclaim(100));
        assertFalse(gc.reclaim(100));
    }

    @Test void missingBodyIsNotReportedAsOrdinaryNoHitOrWithdrawal() {
        try (var read = materials.acquire(1,1)) {
            jdbc.update("DELETE FROM knowledge_chunks WHERE id=1");
            assertEquals("EVIDENCE_MISSING", assertThrows(MaterialScopeException.class, read::validate).reason());
        }
    }

    @Test void deletedSessionDoesNotEraseAnInFlightReader() {
        var read = materials.acquire(1,1); publish();
        jdbc.update("DELETE FROM chat_sessions WHERE id=1");
        assertFalse(gc.reclaim(100));
        read.close();
        assertTrue(gc.reclaim(100));
    }

    @Test void emptyScopeIsFrozenAndUnboundLegacyHistoryIsRejected() {
        jdbc.update("UPDATE documents SET active_version_id=NULL WHERE id=10");
        try (var read = materials.acquire(1,1)) { assertTrue(read.scope().activeVersionIds().isEmpty()); }
        jdbc.update("UPDATE documents SET active_version_id=100 WHERE id=10");
        try (var read = materials.acquire(1,1)) {
            assertTrue(read.scope().activeVersionIds().isEmpty()); assertTrue(read.status().updateAvailable());
        }
        jdbc.update("INSERT INTO chat_messages(session_id,role,content) VALUES (2,'assistant','old unversioned answer')");
        assertEquals("LEGACY_SESSION_UNBOUND", assertThrows(MaterialScopeException.class, () -> materials.acquire(2,1)).reason());
    }

    @Test void courseMismatchCreatesNoScopeAndStatusDoesNotRenewRetention() {
        assertThrows(com.rag.backend.common.BizException.class, () -> materials.acquire(1,2));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM chat_material_scopes", Integer.class));
        try (var read = materials.acquire(1,1)) { read.validate(); }
        expire();
        assertEquals("RETENTION_EXPIRED", materials.status(1,1).reason());
        assertThrows(MaterialScopeException.class, () -> materials.acquire(1,1));
    }

    @Test void cleanupAndRegistrationShareTheDocumentLock() throws Exception {
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            CountDownLatch locked = new CountDownLatch(1), release = new CountDownLatch(1);
            Future<?> withdrawal = pool.submit(() -> new TransactionTemplate(manager).executeWithoutResult(ignored -> {
                jdbc.queryForList("SELECT id FROM documents WHERE id=10 FOR UPDATE");
                jdbc.update("UPDATE documents SET lifecycle_status='DELETING' WHERE id=10");
                locked.countDown();
                try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
                catch (InterruptedException error) { throw new RuntimeException(error); }
            }));
            assertTrue(locked.await(5, TimeUnit.SECONDS));
            Future<?> reader = pool.submit(() -> {
                // A first binding after withdrawal excludes that document, never reads its old version.
                try (var read = materials.acquire(1,1)) { assertTrue(read.scope().activeVersionIds().isEmpty()); }
            });
            release.countDown(); withdrawal.get(5, TimeUnit.SECONDS); reader.get(5, TimeUnit.SECONDS);
            assertDoesNotThrow(() -> gc.awaitDocumentReaders(10));
        }
    }

    private com.rag.backend.agent.history.MyBatisChatHistoryService history() throws Exception {
        var factory = new org.mybatis.spring.SqlSessionFactoryBean();
        factory.setDataSource(jdbc.getDataSource());
        var config = new org.apache.ibatis.session.Configuration();
        config.setMapUnderscoreToCamelCase(true);
        config.addMapper(com.rag.backend.agent.history.ChatHistoryMapper.class);
        factory.setConfiguration(config);
        var mapper = new org.mybatis.spring.SqlSessionTemplate(factory.getObject())
                .getMapper(com.rag.backend.agent.history.ChatHistoryMapper.class);
        var service = new com.rag.backend.agent.history.MyBatisChatHistoryService(mapper);
        service.setObjectMapper(new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules());
        return service;
    }

    private com.rag.backend.agent.retrieval.KnowledgeRetriever retriever() {
        var retriever = mock(com.rag.backend.agent.retrieval.KnowledgeRetriever.class);
        when(retriever.retrieveInScope(any(),anyString(),anyInt())).thenAnswer(invocation -> {
            var scope = (com.rag.backend.agent.retrieval.RetrievalScope) invocation.getArgument(0);
            long version = scope.activeVersionIds().iterator().next();
            var chunk = new com.rag.backend.agent.retrieval.RetrievedChunk(1L,10L,"course.txt",
                    "original evidence","original evidence is the supported answer.",1,0.9)
                    .withDocumentVersionId(version);
            return com.rag.backend.agent.retrieval.RetrievalExecutionResult.unobserved(java.util.List.of(chunk));
        });
        return retriever;
    }

    private com.rag.backend.agent.chat.RagChatService chat(
            com.rag.backend.agent.retrieval.KnowledgeRetriever retriever,
            com.rag.backend.agent.llm.ChatClient provider) throws Exception {
        var result = new com.rag.backend.agent.chat.RagChatService(retriever,
                new com.rag.backend.agent.prompt.RagPromptTemplate(),provider,history(),
                answerPolicy(), new com.rag.backend.agent.evidence.EvidenceDecisionRenderer(),5,8);
        result.setMaterials(materials);
        return result;
    }

    @Test void chatPersistsVersionedReferencesAndRestoresThemWithHistory() throws Exception {
        var provider = mock(com.rag.backend.agent.llm.ChatClient.class);
        when(provider.call(anyString())).thenReturn("Supported answer [S1]");
        var response = chat(retriever(),provider).chat(1L,1L,"original evidence");
        assertEquals("Supported answer [S1]",response.answer());
        assertEquals(100L,response.references().getFirst().documentVersionId());
        assertEquals(100L,response.metadata().materials().versions().getFirst().documentVersionId());
        verify(provider).call(contains("资料版本记录 100"));
        var messages = history().listMessages(1L);
        assertEquals(2,messages.size());
        var json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(messages.get(1).getEvidenceJson());
        assertEquals(100L,json.path("references").get(0).path("documentVersionId").asLong());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM material_readers",Integer.class));
    }

    @Test void publicationDuringRetrievalKeepsOldEvidenceAndPublicationDuringNextTurnNotifies() throws Exception {
        var retriever = retriever();
        var provider = mock(com.rag.backend.agent.llm.ChatClient.class);
        when(provider.call(anyString())).thenAnswer(invocation -> {
            if (jdbc.queryForObject("SELECT COUNT(*) FROM document_versions",Integer.class)==1) publish();
            return "Supported answer [S1]";
        });
        var service = chat(retriever,provider);
        assertEquals(100L,service.chat(1L,1L,"original evidence").references().getFirst().documentVersionId());
        var next = service.chat(1L,1L,"original evidence");
        assertTrue(next.metadata().materials().updateAvailable());
        assertEquals(100L,next.references().getFirst().documentVersionId());
        verify(retriever,never()).retrieveWithResult(anyLong(),anyString(),anyInt());
    }

    @Test void synchronousLateAnswerAfterWithdrawalNeverBecomesSuccessfulHistory() throws Exception {
        var provider = mock(com.rag.backend.agent.llm.ChatClient.class);
        when(provider.call(anyString())).thenAnswer(invocation -> {
            jdbc.update("UPDATE documents SET lifecycle_status='DELETING' WHERE id=10");
            return "late answer";
        });
        var service = chat(retriever(),provider);
        assertThrows(MaterialScopeException.class, () -> service.chat(1L,1L,"original evidence"));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM chat_messages",Integer.class));
    }

    @Test void sseBuffersLateCandidateAndEmitsOnlyErrorAfterWithdrawal() throws Exception {
        var provider = mock(com.rag.backend.agent.llm.ChatClient.class);
        when(provider.stream(anyString())).thenReturn(reactor.core.publisher.Flux.defer(() -> {
            jdbc.update("UPDATE documents SET lifecycle_status='DELETING' WHERE id=10");
            return reactor.core.publisher.Flux.just("unvalidated", " answer");
        }));
        var response = chat(retriever(),provider).stream(1L,1L,"original evidence");
        var signals = response.stream().materialize().collectList().block();
        assertEquals(1,signals.size());
        assertTrue(signals.getFirst().isOnError());
        assertInstanceOf(MaterialScopeException.class,signals.getFirst().getThrowable());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM chat_messages",Integer.class));
    }

    @Test void failedRetrievalReleasesProtectionAndSkipsProviderAndSuccessfulHistory() throws Exception {
        var retriever = retriever();
        doThrow(new IllegalStateException("temporary retrieval outage")).when(retriever).retrieveInScope(any(),anyString(),anyInt());
        var provider = mock(com.rag.backend.agent.llm.ChatClient.class);
        var service = chat(retriever,provider);
        assertThrows(IllegalStateException.class, () -> service.chat(1L,1L,"original evidence"));
        assertEquals("READY",materials.status(1,1).state());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM material_readers",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM chat_messages",Integer.class));
        verifyNoInteractions(provider);
    }

    @Test void referenceReadsRejectAnotherVersionAndNeverJumpToLatest() throws Exception {
        try(var read = materials.acquire(1,1)) { read.validate(); }
        publish();
        var chunks = mock(com.rag.backend.agent.repository.KnowledgeChunkRepository.class);
        var chunk = new com.rag.backend.agent.model.KnowledgeChunk();
        chunk.setCourseId(1L); chunk.setDocumentVersionId(100L); chunk.setContent("old evidence");
        when(chunks.findById(1L)).thenReturn(chunk);
        var controller = new SessionMaterialController(materials,chunks,history());
        assertEquals("old evidence",controller.reference(1,1,1,100).getData().getContent());
        assertThrows(com.rag.backend.common.BizException.class, () -> controller.reference(1,1,1,101));
        expire();
        assertThrows(MaterialScopeException.class, () -> controller.reference(1,1,1,100));
    }

    @Test void failedEvidenceSerializationRollsBackBothMessages() throws Exception {
        var service = history();
        service.setObjectMapper(new com.fasterxml.jackson.databind.ObjectMapper()); // cannot serialize java.time
        var provider = mock(com.rag.backend.agent.llm.ChatClient.class);
        when(provider.call(anyString())).thenReturn("Supported answer");
        var chat = new com.rag.backend.agent.chat.RagChatService(retriever(),
                new com.rag.backend.agent.prompt.RagPromptTemplate(),provider,service,
                answerPolicy(), new com.rag.backend.agent.evidence.EvidenceDecisionRenderer(),5,8);
        chat.setMaterials(materials);
        assertThrows(IllegalStateException.class, () -> chat.chat(1L,1L,"original evidence"));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM chat_messages",Integer.class));
    }

    private com.rag.backend.agent.evidence.EvidenceDecisionPolicy answerPolicy() {
        // This suite verifies lifecycle orchestration; evidence quality has its own policy suite.
        return input -> new com.rag.backend.agent.evidence.EvidenceDecisionResult(
                com.rag.backend.agent.evidence.AnswerabilityDecision.ANSWER,
                com.rag.backend.agent.evidence.EvidenceDecisionReason.DIRECT_SUPPORT_OBSERVED,
                java.util.List.of(1L),null,
                new com.rag.backend.agent.evidence.EvidenceObservedSignals(1,1,1,1,1,true,false,false,false,
                        false,null,null,null,false,com.rag.backend.agent.retrieval.RetrievalDiagnostics.EmptyReason.NONE),
                "lifecycle-test-policy");
    }
}
