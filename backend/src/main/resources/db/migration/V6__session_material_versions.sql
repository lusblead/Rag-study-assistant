-- Session continuity and reclamation are separate from ingestion activation state.
ALTER TABLE document_versions ADD COLUMN read_status VARCHAR(24) NOT NULL DEFAULT 'READABLE';
ALTER TABLE chat_messages ADD COLUMN evidence_json LONGTEXT NULL;
CREATE TABLE chat_material_scopes (
    session_id BIGINT PRIMARY KEY,
    expires_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE chat_material_versions (
    session_id BIGINT NOT NULL,
    document_id BIGINT NOT NULL,
    document_version_id BIGINT NOT NULL,
    version_no INT NOT NULL,
    document_name VARCHAR(255) NOT NULL,
    PRIMARY KEY (session_id, document_id),
    INDEX idx_material_version (document_version_id, session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
-- No expiry or cascading FK: deleting a session must not erase active readers.
CREATE TABLE material_readers (
    read_id CHAR(36) NOT NULL,
    session_id BIGINT NOT NULL,
    document_version_id BIGINT NOT NULL,
    process_owner VARCHAR(120) NOT NULL,
    started_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (read_id, document_version_id),
    INDEX idx_reader_version (document_version_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
