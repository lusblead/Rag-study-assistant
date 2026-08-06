package com.rag.backend.ingestionlab;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 在显式提供的临时 MySQL 空库上执行 V1 -> V3。
 * 默认测试集不会连接外部数据库，只有 rag.mysql.it=true 时才运行。
 */
@EnabledIfSystemProperty(named = "rag.mysql.it", matches = "true")
class ReliableIngestionMySqlMigrationIT {

    @Test
    void emptyMySqlDatabaseMigratesToReliableIngestionSchema() throws Exception {
        String url = required("rag.mysql.jdbcUrl");
        String user = required("rag.mysql.user");
        String password = required("rag.mysql.password");

        Flyway flyway = Flyway.configure()
                .dataSource(url, user, password)
                .locations("classpath:db/migration")
                .validateOnMigrate(true)
                .load();
        var result = flyway.migrate();
        assertEquals(3, result.migrationsExecuted,
                "临时空库必须按顺序执行 V1、V2、V3");

        try (Connection connection = DriverManager.getConnection(
                url, user, password);
             Statement statement = connection.createStatement()) {
            assertTrue(tableExists(statement, "document_versions"));
            assertTrue(tableExists(statement, "ingest_jobs"));
            assertTrue(tableExists(statement, "ingest_steps"));
            assertTrue(tableExists(statement, "outbox_events"));
            assertTrue(tableExists(statement, "reconciliation_issues"));
            assertTrue(columnExists(statement, "documents", "active_version_id"));
            assertTrue(columnExists(statement, "ingest_jobs", "document_id"));
            assertTrue(columnExists(statement, "document_versions", "pipeline_manifest"));
        }
    }

    @Test
    void existingPreFlywaySchemaBaselinesAtV1ThenAppliesV2AndV3() throws Exception {
        String url = required("rag.mysql.upgradeJdbcUrl");
        String user = required("rag.mysql.user");
        String password = required("rag.mysql.password");

        Flyway flyway = Flyway.configure()
                .dataSource(url, user, password)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion(MigrationVersion.fromVersion("1"))
                .validateOnMigrate(true)
                .load();
        var result = flyway.migrate();
        assertEquals(2, result.migrationsExecuted,
                "既有业务表应被标记为 V1，只执行 V2、V3");

        try (Connection connection = DriverManager.getConnection(
                url, user, password);
             Statement statement = connection.createStatement()) {
            assertTrue(tableExists(statement, "document_versions"));
            assertTrue(columnExists(statement, "documents", "active_version_id"));
            assertTrue(columnExists(statement, "ingest_jobs", "document_id"));
        }
    }

    private boolean tableExists(Statement statement, String table) throws Exception {
        try (ResultSet rows = statement.executeQuery("""
                SELECT COUNT(*)
                  FROM information_schema.tables
                 WHERE table_schema = DATABASE()
                   AND table_name = '%s'
                """.formatted(table))) {
            rows.next();
            return rows.getInt(1) == 1;
        }
    }

    private boolean columnExists(
            Statement statement, String table, String column) throws Exception {
        try (ResultSet rows = statement.executeQuery("""
                SELECT COUNT(*)
                  FROM information_schema.columns
                 WHERE table_schema = DATABASE()
                   AND table_name = '%s'
                   AND column_name = '%s'
                """.formatted(table, column))) {
            rows.next();
            return rows.getInt(1) == 1;
        }
    }

    private String required(String key) {
        String value = System.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing system property: " + key);
        }
        return value;
    }
}
