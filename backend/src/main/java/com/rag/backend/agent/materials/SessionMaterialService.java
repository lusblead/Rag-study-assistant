package com.rag.backend.agent.materials;

import com.rag.backend.agent.retrieval.RetrievalScope;
import com.rag.backend.common.BizException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/** Short SQL transactions arbitrate readers against publication/withdrawal/GC document locks. */
@Service
public class SessionMaterialService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SessionMaterialService.class);
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Duration retention;
    private final String processOwner = UUID.randomUUID().toString();

    public SessionMaterialService(JdbcTemplate jdbc, PlatformTransactionManager manager,
            @Value("${rag.materials.session-retention:P7D}") Duration retention) {
        if (retention.isNegative() || retention.isZero()) {
            throw new IllegalArgumentException("Session retention must be positive");
        }
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(manager);
        this.tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.retention = retention;
        log.info("Material reader owner registered: owner={}, pid={}, processStartedAt={}",
                processOwner, ProcessHandle.current().pid(),
                ProcessHandle.current().info().startInstant().orElse(null));
    }

    public ReadHandle acquire(long sessionId, long courseId) {
        return tx.execute(ignored -> {
            lockSession(sessionId, courseId);
            lockCourseDocuments(courseId);
            if (expiry(sessionId) == null) {
                if (hasLegacyMessages(sessionId)) throw new MaterialScopeException("LEGACY_SESSION_UNBOUND");
                jdbc.update("INSERT INTO chat_material_scopes(session_id, expires_at) VALUES (?, ?)",
                        sessionId, now().plus(retention));
                for (MaterialStatus.Version version : active(courseId)) {
                    jdbc.update("""
                        INSERT INTO chat_material_versions
                            (session_id, document_id, document_version_id, version_no, document_name)
                        VALUES (?, ?, ?, ?, ?)
                        """, sessionId, version.documentId(), version.documentVersionId(),
                            version.versionNo(), version.documentName());
                }
            }
            MaterialStatus status = checkedStatus(sessionId, courseId);
            LocalDateTime until = now().plus(retention);
            jdbc.update("UPDATE chat_material_scopes SET expires_at=? WHERE session_id=?", until, sessionId);
            String readId = UUID.randomUUID().toString();
            for (MaterialStatus.Version version : status.versions()) {
                jdbc.update("""
                    INSERT INTO material_readers(read_id, session_id, document_version_id, process_owner)
                    VALUES (?, ?, ?, ?)
                    """, readId, sessionId, version.documentVersionId(), processOwner);
            }
            MaterialStatus renewed = new MaterialStatus(sessionId, status.state(), status.reason(),
                    status.updateAvailable(), until, status.versions());
            return new ReadHandle(readId, courseId, renewed);
        });
    }

    /** Page reads never bind or renew the retention merely because a page is open. */
    public MaterialStatus status(long sessionId, long courseId) {
        return tx.execute(ignored -> {
            lockSession(sessionId, courseId);
            lockCourseDocuments(courseId);
            return inspect(sessionId, courseId);
        });
    }

    /** Linearization point for accepting an answer and its versioned references. */
    public void complete(long sessionId, long courseId, Runnable persist) {
        tx.executeWithoutResult(ignored -> {
            lockSession(sessionId, courseId);
            lockCourseDocuments(courseId);
            checkedStatus(sessionId, courseId);
            persist.run();
        });
    }

    private void lockSession(long sessionId, long courseId) {
        List<Long> courses = jdbc.queryForList("SELECT course_id FROM chat_sessions WHERE id=? FOR UPDATE", Long.class, sessionId);
        if (courses.isEmpty()) throw new BizException(404, "会话不存在");
        if (courses.getFirst() != courseId) throw new BizException(400, "会话不属于当前课程");
    }

    private void lockCourseDocuments(long courseId) {
        jdbc.queryForList("SELECT id FROM documents WHERE course_id=? ORDER BY id FOR UPDATE", Long.class, courseId);
    }

    private LocalDateTime now() {
        return jdbc.queryForObject("SELECT CURRENT_TIMESTAMP", LocalDateTime.class);
    }

    private LocalDateTime expiry(long sessionId) {
        List<LocalDateTime> rows = jdbc.query("SELECT expires_at FROM chat_material_scopes WHERE session_id=?",
                (rs, index) -> rs.getTimestamp(1).toLocalDateTime(), sessionId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private List<MaterialStatus.Version> active(long courseId) {
        return jdbc.query("""
            SELECT d.id, v.id, v.version_no, d.filename FROM documents d
            JOIN document_versions v ON v.id=d.active_version_id AND v.document_id=d.id
            WHERE d.course_id=? AND d.lifecycle_status='ACTIVE' AND v.state='ACTIVE'
              AND v.read_status='READABLE' ORDER BY d.id
            """, (rs, index) -> new MaterialStatus.Version(rs.getLong(1), rs.getLong(2), rs.getInt(3), rs.getString(4)), courseId);
    }

    private List<MaterialStatus.Version> bound(long sessionId) {
        return jdbc.query("""
            SELECT document_id, document_version_id, version_no, document_name
            FROM chat_material_versions WHERE session_id=? ORDER BY document_id
            """, (rs, index) -> new MaterialStatus.Version(rs.getLong(1), rs.getLong(2), rs.getInt(3), rs.getString(4)), sessionId);
    }

    private MaterialStatus checkedStatus(long sessionId, long courseId) {
        MaterialStatus result = inspect(sessionId, courseId);
        if (!"READY".equals(result.state())) throw new MaterialScopeException(result.reason());
        return result;
    }

    private MaterialStatus inspect(long sessionId, long courseId) {
        LocalDateTime until = expiry(sessionId);
        if (until == null) return new MaterialStatus(sessionId,
                hasLegacyMessages(sessionId) ? "UNAVAILABLE" : "UNBOUND",
                hasLegacyMessages(sessionId) ? "LEGACY_SESSION_UNBOUND" : null, false, null, List.of());
        List<MaterialStatus.Version> versions = bound(sessionId);
        String reason = !until.isAfter(now()) ? "RETENTION_EXPIRED" : null;
        for (MaterialStatus.Version version : versions) {
            List<String> states = jdbc.query("""
                SELECT d.lifecycle_status, v.state, v.read_status, v.expected_chunk_count,
                    (SELECT COUNT(*) FROM knowledge_chunks kc WHERE kc.document_version_id=v.id) AS actual_count
                FROM document_versions v JOIN documents d ON d.id=v.document_id
                WHERE v.id=? AND d.id=? AND d.course_id=?
                """, (rs, index) -> {
                    if (!"ACTIVE".equals(rs.getString(1))) return "DOCUMENT_WITHDRAWN";
                    if (!List.of("ACTIVE", "SUPERSEDED").contains(rs.getString(2))
                            || !"READABLE".equals(rs.getString(3))) return "VERSION_UNAVAILABLE";
                    Integer expected = rs.getObject(4, Integer.class);
                    if (expected == null || expected != rs.getInt(5)) return "EVIDENCE_MISSING";
                    return "OK";
                }, version.documentVersionId(), version.documentId(), courseId);
            if (reason == null && (states.isEmpty() || !"OK".equals(states.getFirst()))) {
                reason = states.isEmpty() ? "VERSION_UNAVAILABLE" : states.getFirst();
            }
        }
        var oldIds = versions.stream().map(MaterialStatus.Version::documentVersionId).collect(Collectors.toSet());
        var newIds = active(courseId).stream().map(MaterialStatus.Version::documentVersionId).collect(Collectors.toSet());
        return new MaterialStatus(sessionId, reason == null ? "READY" : "UNAVAILABLE", reason,
                !oldIds.equals(newIds), until, versions);
    }

    public final class ReadHandle implements AutoCloseable {
        private final String readId;
        private final long courseId;
        private final MaterialStatus status;
        private boolean closed;
        private ReadHandle(String readId, long courseId, MaterialStatus status) {
            this.readId = readId; this.courseId = courseId; this.status = status;
        }
        public MaterialStatus status() { return status; }
        public RetrievalScope scope() {
            if (closed) throw new IllegalStateException("Read handle already released");
            return new RetrievalScope(courseId, status.versions().stream()
                    .map(MaterialStatus.Version::documentVersionId).collect(Collectors.toSet()), true);
        }
        public void validate() {
            if (closed) throw new IllegalStateException("Read handle already released");
            complete(status.sessionId(), courseId, () -> { });
        }
        @Override public void close() {
            if (!closed) {
                jdbc.update("DELETE FROM material_readers WHERE read_id=? AND process_owner=?", readId, processOwner);
                closed = true;
            }
        }
    }

    private boolean hasLegacyMessages(long sessionId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM chat_messages WHERE session_id=?", Integer.class, sessionId) > 0;
    }
}
