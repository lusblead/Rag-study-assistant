package com.rag.backend.config;

import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.ResultSet;

@Component
@DependsOn(DatabaseMigrationConfiguration.MIGRATION_GATE_BEAN)
public class PracticeRecordSchemaMigration {
    private final JdbcTemplate jdbcTemplate;

    public PracticeRecordSchemaMigration(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    public void migrate() {
        addColumnIfMissing("grading_mode", "VARCHAR(40)");
        addColumnIfMissing("grading_feedback", "TEXT");
        addColumnIfMissing("answer_payload", "TEXT");
        addColumnIfMissing("score", "DECIMAL(7,2)");
        addColumnIfMissing("max_score", "DECIMAL(7,2)");
        addColumnIfMissing("grading_status", "VARCHAR(32) NOT NULL DEFAULT 'graded'");
    }

    private void addColumnIfMissing(String columnName, String definition) {
        if (columnExists(columnName)) {
            return;
        }
        jdbcTemplate.execute("ALTER TABLE practice_records ADD COLUMN " + columnName + " " + definition);
    }

    private boolean columnExists(String columnName) {
        try (Connection connection = jdbcTemplate.getDataSource().getConnection()) {
            String catalog = connection.getCatalog();
            return hasColumn(connection, catalog, "practice_records", columnName)
                    || hasColumn(connection, catalog, "PRACTICE_RECORDS", columnName.toUpperCase());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to inspect practice_records schema", e);
        }
    }

    private boolean hasColumn(Connection connection, String catalog, String tableName, String columnName) throws Exception {
        try (ResultSet columns = connection.getMetaData().getColumns(catalog, null, tableName, columnName)) {
            return columns.next();
        }
    }
}
