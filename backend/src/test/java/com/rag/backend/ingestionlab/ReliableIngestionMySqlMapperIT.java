package com.rag.backend.ingestionlab;

import com.rag.backend.ingestionlab.delete.DeleteRequestService;
import com.rag.backend.ingestionlab.identity.PipelineManifest;
import com.rag.backend.ingestionlab.outbox.ReliableIngestSubmitter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 在临时 MySQL 上执行真实 Mapper SQL，覆盖重复受理、墓碑和取消旧 Lease。 */
@EnabledIfSystemProperty(named = "rag.mysql.it", matches = "true")
@SpringBootTest(properties = {
        "agent.mock=true",
        "vector.provider=local",
        "spring.flyway.enabled=true",
        "spring.sql.init.mode=never",
        "ingestion.scheduling.enabled=false",
        "ingestion.artifact.dir=target/test-artifacts/mysql-context"
})
class ReliableIngestionMySqlMapperIT {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ReliableIngestSubmitter submitter;
    @Autowired private DeleteRequestService deleteRequestService;

    @DynamicPropertySource
    static void mysqlProperties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url",
                () -> required("rag.mysql.jdbcUrl"));
        properties.add("spring.datasource.username",
                () -> required("rag.mysql.user"));
        properties.add("spring.datasource.password",
                () -> required("rag.mysql.password"));
        properties.add("spring.datasource.driver-class-name",
                () -> "com.mysql.cj.jdbc.Driver");
    }

    @Test
    void repeatedSubmitAndDeleteUseOneDurableIdentity() {
        jdbc.update("""
                INSERT INTO courses (id, name, term)
                VALUES (5001, '方案三 MySQL IT', '2026')
                """);
        jdbc.update("""
                INSERT INTO documents
                    (id, course_id, filename, file_type, file_path,
                     parse_status, chunk_count, lifecycle_status)
                VALUES
                    (6001, 5001, 'mysql-it.txt', 'txt',
                     'C:/temporary/mysql-it.txt', 'UPLOADED', 0, 'ACTIVE')
                """);
        PipelineManifest manifest = new PipelineManifest(
                "test-parser", "1", 1,
                "fixed-window", 800, 120, 1,
                "mock-embedding", 64,
                "test-vector-schema", "verification-v1");

        var first = submitter.submit(
                6001L, "C:/temporary/mysql-it.txt",
                "a".repeat(64), manifest);
        var replay = submitter.submit(
                6001L, "C:/temporary/mysql-it.txt",
                "a".repeat(64), manifest);

        assertEquals(first.documentVersionId(), replay.documentVersionId());
        assertEquals(first.jobId(), replay.jobId());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM document_versions WHERE document_id=6001",
                Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM ingest_jobs WHERE document_id=6001 AND job_type='INGEST'",
                Integer.class));

        var deletion = deleteRequestService.request(6001L);
        assertEquals(6001L, deletion.documentId());
        assertEquals("DELETING", jdbc.queryForObject(
                "SELECT lifecycle_status FROM documents WHERE id=6001",
                String.class));
        assertEquals("CANCELLED", jdbc.queryForObject(
                "SELECT state FROM ingest_jobs WHERE document_id=6001 AND job_type='INGEST'",
                String.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM ingest_jobs WHERE document_id=6001 AND job_type='DELETE'",
                Integer.class));
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_events",
                Integer.class));
    }

    private static String required(String key) {
        String value = System.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing system property: " + key);
        }
        return value;
    }
}
