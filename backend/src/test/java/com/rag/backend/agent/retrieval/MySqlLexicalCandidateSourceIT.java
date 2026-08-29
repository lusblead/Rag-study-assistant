package com.rag.backend.agent.retrieval;

import com.rag.backend.agent.repository.KnowledgeChunkMapper;
import com.rag.backend.document.DocumentMapper;
import com.rag.backend.ingestionlab.retrieval.MyBatisActiveVersionResolver;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.LocalCacheScope;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 显式临时 MySQL 上的 Lexical 生命周期证据；默认测试集不连接外部数据库。
 */
@EnabledIfSystemProperty(named = "rag.mysql.it", matches = "true")
class MySqlLexicalCandidateSourceIT {
    private String url;
    private String user;
    private String password;
    private DataSource dataSource;
    private SqlSessionFactory sessions;
    private Long courseId;
    private Long documentId;

    @BeforeEach
    void setUp() {
        url = required("rag.mysql.jdbcUrl");
        user = required("rag.mysql.user");
        password = required("rag.mysql.password");
        Flyway.configure()
                .dataSource(url, user, password)
                .locations("classpath:db/migration")
                .validateOnMigrate(true)
                .load()
                .migrate();

        dataSource = new UnpooledDataSource(
                "com.mysql.cj.jdbc.Driver", url, user, password);
        Configuration configuration = new Configuration(new Environment(
                "lexical-it", new JdbcTransactionFactory(), dataSource));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setLocalCacheScope(LocalCacheScope.STATEMENT);
        configuration.addMapper(KnowledgeChunkMapper.class);
        configuration.addMapper(DocumentMapper.class);
        sessions = new SqlSessionFactoryBuilder().build(configuration);
    }

    @AfterEach
    void cleanUp() throws Exception {
        if (dataSource == null || courseId == null) {
            return;
        }
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            execute(connection,
                    "DELETE FROM knowledge_chunks WHERE course_id=?", courseId);
            if (documentId != null) {
                execute(connection,
                        "DELETE FROM document_versions WHERE document_id=?",
                        documentId);
                execute(connection,
                        "DELETE FROM documents WHERE id=?", documentId);
            }
            execute(connection, "DELETE FROM courses WHERE id=?", courseId);
            connection.commit();
        }
    }

    @Test
    void activeSwitchDeleteAndReplacementFollowMySqlRows() throws Exception {
        Fixture fixture = insertFixture();

        try (SqlSession session = sessions.openSession(true)) {
            KnowledgeChunkMapper mapper = session.getMapper(
                    KnowledgeChunkMapper.class);
            MyBatisActiveVersionResolver resolver =
                    new MyBatisActiveVersionResolver(
                            session.getMapper(DocumentMapper.class));
            MySqlLexicalCandidateSource source =
                    new MySqlLexicalCandidateSource(
                            mapper, 0.0, new LexicalQueryPolicy());

            Set<Long> initialScope = resolver.forCourse(courseId);
            assertEquals(Set.of(fixture.versionOne()), initialScope);
            assertEquals(List.of(fixture.chunkOne()), chunkIds(source.retrieve(
                    new RetrievalScope(courseId, initialScope),
                    "alpha-101", 10)));
            assertTrue(source.retrieve(
                    new RetrievalScope(courseId, initialScope),
                    "beta-202", 10).candidates().isEmpty(),
                    "READY but inactive version must remain invisible");

            activate(fixture.versionOne(), fixture.versionTwo());
            Set<Long> switchedScope = resolver.forCourse(courseId);
            assertEquals(Set.of(fixture.versionTwo()), switchedScope);
            assertEquals(List.of(fixture.chunkTwo()), chunkIds(source.retrieve(
                    new RetrievalScope(courseId, switchedScope),
                    "beta-202", 10)));
            assertTrue(source.retrieve(
                    new RetrievalScope(courseId, switchedScope),
                    "alpha-101", 10).candidates().isEmpty(),
                    "superseded version must be hidden by the new scope");

            deleteChunk(fixture.chunkTwo());
            assertTrue(source.retrieve(
                    new RetrievalScope(courseId, switchedScope),
                    "beta-202", 10).candidates().isEmpty());

            long replacement = insertChunk(
                    fixture.versionTwo(), "replacement", "gamma-303 replacement");
            assertEquals(List.of(replacement), chunkIds(source.retrieve(
                    new RetrievalScope(courseId, switchedScope),
                    "gamma-303", 10)));
        }
    }

    private Fixture insertFixture() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            courseId = insertAndReturnId(connection,
                    "INSERT INTO courses(name) VALUES (?)", "lexical-it");
            documentId = insertAndReturnId(connection, """
                    INSERT INTO documents
                        (course_id, filename, file_type, file_path,
                         parse_status, chunk_count, lifecycle_status)
                    VALUES (?, ?, 'txt', ?, 'PARSED', 2, 'ACTIVE')
                    """, courseId, "lexical-it.txt", "isolated/lexical-it.txt");
            String marker = UUID.randomUUID().toString().replace("-", "");
            long versionOne = insertVersion(
                    connection, 1, marker + marker, "ACTIVE");
            String second = new StringBuilder(marker).reverse().toString();
            long versionTwo = insertVersion(
                    connection, 2, second + second, "READY");
            execute(connection,
                    "UPDATE documents SET active_version_id=? WHERE id=?",
                    versionOne, documentId);
            long chunkOne = insertChunk(
                    connection, versionOne, "v1", "alpha-101 active evidence");
            long chunkTwo = insertChunk(
                    connection, versionTwo, "v2", "beta-202 ready evidence");
            connection.commit();
            return new Fixture(versionOne, versionTwo, chunkOne, chunkTwo);
        }
    }

    private long insertVersion(
            Connection connection,
            int versionNo,
            String contentHash,
            String state) throws Exception {
        return insertAndReturnId(connection, """
                INSERT INTO document_versions
                    (document_id, version_no, content_hash,
                     pipeline_fingerprint, state)
                VALUES (?, ?, ?, ?, ?)
                """, documentId, versionNo, contentHash,
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                state);
    }

    private long insertChunk(
            long versionId,
            String businessKey,
            String content) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            long id = insertChunk(connection, versionId, businessKey, content);
            return id;
        }
    }

    private long insertChunk(
            Connection connection,
            long versionId,
            String businessKey,
            String content) throws Exception {
        String contentHash = ("%064x".formatted(content.hashCode()))
                .substring(0, 64);
        return insertAndReturnId(connection, """
                INSERT INTO knowledge_chunks
                    (course_id, document_id, document_version_id, chunk_index,
                     title, content, chunk_business_key, content_hash,
                     embedding_status)
                VALUES (?, ?, ?, 0, ?, ?, ?, ?, 'DONE')
                """, courseId, documentId, versionId, businessKey,
                content, businessKey, contentHash);
    }

    private void activate(long oldVersion, long newVersion) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            execute(connection,
                    "UPDATE document_versions SET state='SUPERSEDED' WHERE id=?",
                    oldVersion);
            execute(connection,
                    "UPDATE document_versions SET state='ACTIVE' WHERE id=?",
                    newVersion);
            execute(connection,
                    "UPDATE documents SET active_version_id=? WHERE id=?",
                    newVersion, documentId);
            connection.commit();
        }
    }

    private void deleteChunk(long chunkId) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            execute(connection, "DELETE FROM knowledge_chunks WHERE id=?", chunkId);
        }
    }

    private long insertAndReturnId(
            Connection connection,
            String sql,
            Object... values) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                sql, Statement.RETURN_GENERATED_KEYS)) {
            bind(statement, values);
            assertEquals(1, statement.executeUpdate());
            try (ResultSet keys = statement.getGeneratedKeys()) {
                assertTrue(keys.next());
                return keys.getLong(1);
            }
        }
    }

    private void execute(
            Connection connection,
            String sql,
            Object... values) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, values);
            statement.executeUpdate();
        }
    }

    private void bind(PreparedStatement statement, Object... values)
            throws Exception {
        for (int index = 0; index < values.length; index++) {
            statement.setObject(index + 1, values[index]);
        }
    }

    private List<Long> chunkIds(CandidateBatch batch) {
        return batch.candidates().stream()
                .map(candidate -> candidate.chunk().chunkId())
                .toList();
    }

    private String required(String key) {
        String value = System.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing system property: " + key);
        }
        return value;
    }

    private record Fixture(
            long versionOne,
            long versionTwo,
            long chunkOne,
            long chunkTwo) {
    }
}
