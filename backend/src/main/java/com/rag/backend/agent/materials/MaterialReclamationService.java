package com.rag.backend.agent.materials;

import com.rag.backend.ingestionlab.artifact.ArtifactStore;
import com.rag.backend.ingestionlab.vector.ConsistentVectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/** Commit admission closure before external deletion; readers are durable and never timed out. */
@Service
public class MaterialReclamationService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ConsistentVectorStore vectors;
    private final ArtifactStore artifacts;
    private final Duration minimumRetention;

    public MaterialReclamationService(JdbcTemplate jdbc, PlatformTransactionManager manager,
            ConsistentVectorStore vectors, ArtifactStore artifacts,
            @Value("${rag.materials.version-retention:P7D}") Duration minimumRetention) {
        if (minimumRetention.isNegative()) throw new IllegalArgumentException("Negative version retention");
        this.jdbc = jdbc; this.vectors = vectors; this.artifacts = artifacts;
        this.minimumRetention = minimumRetention;
        this.tx = new TransactionTemplate(manager);
        this.tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    public List<Long> candidates(int limit) {
        return jdbc.queryForList("""
            SELECT v.id FROM document_versions v JOIN documents d ON d.id=v.document_id
            WHERE v.state='SUPERSEDED' AND v.read_status IN ('READABLE','RETIRING')
              AND d.lifecycle_status='ACTIVE' AND v.superseded_at<=?
              AND NOT EXISTS (SELECT 1 FROM material_readers r WHERE r.document_version_id=v.id)
              AND NOT EXISTS (SELECT 1 FROM chat_material_versions b
                  JOIN chat_material_scopes s ON s.session_id=b.session_id
                  JOIN chat_sessions c ON c.id=s.session_id
                  WHERE b.document_version_id=v.id AND s.expires_at>CURRENT_TIMESTAMP)
            ORDER BY v.id LIMIT ?
            """, Long.class, now().minus(minimumRetention), limit);
    }

    public boolean reclaim(long versionId) {
        Boolean allowed = tx.execute(ignored -> {
            List<Long> documentIds = jdbc.queryForList("SELECT document_id FROM document_versions WHERE id=?", Long.class, versionId);
            if (documentIds.isEmpty()) return false;
            long documentId = documentIds.getFirst();
            List<String> documents = jdbc.queryForList("SELECT lifecycle_status FROM documents WHERE id=? FOR UPDATE", String.class, documentId);
            if (documents.isEmpty() || !"ACTIVE".equals(documents.getFirst())) return false;
            Integer eligible = jdbc.queryForObject("""
                SELECT COUNT(*) FROM document_versions WHERE id=? AND state='SUPERSEDED'
                  AND read_status IN ('READABLE','RETIRING') AND superseded_at<=?
                """, Integer.class, versionId, now().minus(minimumRetention));
            if (eligible == null || eligible == 0) return false;
            Integer retained = jdbc.queryForObject("""
                SELECT COUNT(*) FROM chat_material_versions b
                JOIN chat_material_scopes s ON s.session_id=b.session_id
                JOIN chat_sessions c ON c.id=s.session_id
                WHERE b.document_version_id=? AND s.expires_at>CURRENT_TIMESTAMP
                """, Integer.class, versionId);
            if (retained != null && retained > 0) return false;
            jdbc.update("UPDATE document_versions SET read_status='RETIRING' WHERE id=? AND read_status='READABLE'", versionId);
            return readerCount(versionId) == 0;
        });
        if (!Boolean.TRUE.equals(allowed)) return false;
        // No SQL lock spans I/O. RETIRING prevents a new reader registering after this commit.
        vectors.deleteByVersion(versionId);
        artifacts.deletePrefix(versionId + "/");
        tx.executeWithoutResult(ignored -> {
            jdbc.update("DELETE FROM knowledge_chunks WHERE document_version_id=?", versionId);
            jdbc.update("UPDATE document_versions SET read_status='DELETED' WHERE id=? AND read_status='RETIRING'", versionId);
        });
        return true;
    }

    /** Withdrawal may ignore session retention, but must wait for real in-flight readers. */
    public void awaitDocumentReaders(long documentId) {
        tx.executeWithoutResult(ignored -> {
            List<String> documents = jdbc.queryForList("SELECT lifecycle_status FROM documents WHERE id=? FOR UPDATE", String.class, documentId);
            if (documents.isEmpty() || !List.of("DELETING", "DELETED").contains(documents.getFirst())) {
                throw new IllegalStateException("Document must be withdrawn before deletion");
            }
            Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM material_readers r JOIN document_versions v ON v.id=r.document_version_id
                WHERE v.document_id=?
                """, Integer.class, documentId);
            if (count != null && count > 0) throw new ReadersActiveException();
        });
    }

    private int readerCount(long versionId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM material_readers WHERE document_version_id=?", Integer.class, versionId);
    }
    private LocalDateTime now() { return jdbc.queryForObject("SELECT CURRENT_TIMESTAMP", LocalDateTime.class); }
    public static class ReadersActiveException extends RuntimeException {
        public ReadersActiveException() { super("MATERIAL_READERS_ACTIVE"); }
    }
}
