package com.rag.backend.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.backend.question.ChapterTagExtractor;
import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.ResultSet;
import java.util.List;

@Component
@DependsOn(DatabaseMigrationConfiguration.MIGRATION_GATE_BEAN)
public class QuestionSchemaMigration {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private final JdbcTemplate jdbcTemplate;

    public QuestionSchemaMigration(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    public void migrate() {
        if (!columnExists("questions", "batch_id")) {
            jdbcTemplate.execute("ALTER TABLE questions ADD COLUMN batch_id BIGINT");
        }
        if (!columnExists("questions", "chapter_tags")) {
            jdbcTemplate.execute("ALTER TABLE questions ADD COLUMN chapter_tags TEXT");
        }
        addColumnIfMissing("question_data", "TEXT");
        addColumnIfMissing("answer_schema", "TEXT");
        addColumnIfMissing("subject", "VARCHAR(32) NOT NULL DEFAULT 'general'");
        addColumnIfMissing("grading_strategy", "VARCHAR(32) NOT NULL DEFAULT 'rule'");
        widenQuestionTypeColumns();
        backfillChapterTags();
    }

    private void widenQuestionTypeColumns() {
        try (Connection connection = jdbcTemplate.getDataSource().getConnection()) {
            String product = connection.getMetaData().getDatabaseProductName().toLowerCase();
            if (product.contains("h2")) {
                jdbcTemplate.execute("ALTER TABLE questions ALTER COLUMN type VARCHAR(64) NOT NULL");
                if (tableExists(connection, "QUESTION_BATCHES")) {
                    jdbcTemplate.execute("ALTER TABLE question_batches ALTER COLUMN question_type VARCHAR(64)");
                }
            } else {
                jdbcTemplate.execute("ALTER TABLE questions MODIFY COLUMN type VARCHAR(64) NOT NULL");
                if (tableExists(connection, "question_batches")) {
                    jdbcTemplate.execute("ALTER TABLE question_batches MODIFY COLUMN question_type VARCHAR(64)");
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to widen question type columns", e);
        }
    }

    private boolean tableExists(Connection connection, String tableName) throws Exception {
        try (ResultSet tables = connection.getMetaData().getTables(connection.getCatalog(), null, tableName, new String[]{"TABLE"})) {
            return tables.next();
        }
    }

    private void addColumnIfMissing(String columnName, String definition) {
        if (!columnExists("questions", columnName)) {
            jdbcTemplate.execute("ALTER TABLE questions ADD COLUMN " + columnName + " " + definition);
        }
    }

    private void backfillChapterTags() {
        jdbcTemplate.query("""
                SELECT q.id, d.filename, kc.title, kc.content
                FROM questions q
                LEFT JOIN knowledge_chunks kc ON kc.id = q.source_chunk_id
                LEFT JOIN documents d ON d.id = kc.document_id
                WHERE q.chapter_tags IS NULL OR q.chapter_tags = '' OR q.chapter_tags = '[]'
                """, resultSet -> {
            List<String> chapters = ChapterTagExtractor.extract(
                    resultSet.getString("filename"),
                    resultSet.getString("title"),
                    resultSet.getString("content")
            );
            if (!chapters.isEmpty()) {
                jdbcTemplate.update(
                        "UPDATE questions SET chapter_tags = ? WHERE id = ?",
                        encodeChapters(chapters),
                        resultSet.getLong("id")
                );
            }
        });
    }

    private String encodeChapters(List<String> chapters) {
        try {
            return OBJECT_MAPPER.writeValueAsString(chapters);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to encode inferred chapter tags", exception);
        }
    }

    private boolean columnExists(String tableName, String columnName) {
        try (Connection connection = jdbcTemplate.getDataSource().getConnection()) {
            String catalog = connection.getCatalog();
            return hasColumn(connection, catalog, tableName, columnName)
                    || hasColumn(connection, catalog, tableName.toUpperCase(), columnName.toUpperCase());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to inspect questions schema", e);
        }
    }

    private boolean hasColumn(Connection connection, String catalog, String tableName, String columnName) throws Exception {
        try (ResultSet columns = connection.getMetaData().getColumns(catalog, null, tableName, columnName)) {
            return columns.next();
        }
    }
}
