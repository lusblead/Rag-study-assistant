-- 可靠摄取协议：Version 管可见性，Job/Step 管恢复，Outbox 管可靠唤醒。
CREATE TABLE document_versions (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    document_id BIGINT NOT NULL,
    version_no INT NOT NULL,
    content_hash CHAR(64) NOT NULL,
    pipeline_fingerprint CHAR(64) NOT NULL,
    state VARCHAR(32) NOT NULL,
    expected_chunk_count INT NULL,
    indexed_chunk_count INT NOT NULL DEFAULT 0,
    state_version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ready_at DATETIME NULL,
    activated_at DATETIME NULL,
    superseded_at DATETIME NULL,
    verification_digest CHAR(64) NULL,
    verification_report JSON NULL,
    UNIQUE KEY uk_document_version_no (document_id, version_no),
    UNIQUE KEY uk_document_content_pipeline
        (document_id, content_hash, pipeline_fingerprint),
    INDEX idx_document_version_state (document_id, state)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE documents
    ADD COLUMN active_version_id BIGINT NULL,
    ADD COLUMN lifecycle_status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
    ADD COLUMN deleted_at DATETIME(6) NULL,
    ADD INDEX idx_documents_active_version (active_version_id),
    ADD INDEX idx_documents_lifecycle (course_id, lifecycle_status);

ALTER TABLE knowledge_chunks
    ADD COLUMN document_version_id BIGINT NULL,
    ADD COLUMN chunk_business_key VARCHAR(160) NULL,
    ADD COLUMN content_hash CHAR(64) NULL,
    ADD COLUMN vector_business_id BIGINT NULL,
    ADD COLUMN embedding_model VARCHAR(200) NULL,
    ADD COLUMN embedding_dimension INT NULL,
    ADD COLUMN embedding_error_code VARCHAR(64) NULL,
    ADD UNIQUE KEY uk_version_chunk (document_version_id, chunk_business_key),
    ADD UNIQUE KEY uk_vector_business_id (vector_business_id),
    ADD INDEX idx_chunks_version_embedding (document_version_id, embedding_status);

CREATE TABLE ingest_jobs (
    job_id CHAR(36) PRIMARY KEY,
    document_version_id BIGINT NULL,
    job_type VARCHAR(32) NOT NULL,
    state VARCHAR(24) NOT NULL,
    attempt INT NOT NULL DEFAULT 0,
    max_attempts INT NOT NULL DEFAULT 5,
    lease_owner VARCHAR(120) NULL,
    lease_until DATETIME(6) NULL,
    next_run_at DATETIME(6) NOT NULL,
    state_version BIGINT NOT NULL DEFAULT 0,
    error_code VARCHAR(64) NULL,
    error_detail_digest VARCHAR(500) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    started_at DATETIME(6) NULL,
    finished_at DATETIME(6) NULL,
    UNIQUE KEY uk_version_job_type (document_version_id, job_type),
    INDEX idx_job_poll (state, next_run_at, lease_until)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE ingest_steps (
    job_id CHAR(36) NOT NULL,
    step_name VARCHAR(40) NOT NULL,
    state VARCHAR(20) NOT NULL,
    attempt INT NOT NULL DEFAULT 0,
    input_digest CHAR(64) NOT NULL,
    output_ref VARCHAR(500) NULL,
    output_digest CHAR(64) NULL,
    processed_count INT NOT NULL DEFAULT 0,
    error_code VARCHAR(64) NULL,
    started_at DATETIME(6) NULL,
    finished_at DATETIME(6) NULL,
    PRIMARY KEY (job_id, step_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE outbox_events (
    event_id CHAR(36) PRIMARY KEY,
    aggregate_type VARCHAR(40) NOT NULL,
    aggregate_id VARCHAR(80) NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    payload_json JSON NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'NEW',
    attempts INT NOT NULL DEFAULT 0,
    available_at DATETIME(6) NOT NULL,
    claimed_by VARCHAR(120) NULL,
    claim_until DATETIME(6) NULL,
    state_version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    published_at DATETIME(6) NULL,
    INDEX idx_outbox_poll (status, available_at, claim_until)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE reconciliation_issues (
    issue_id CHAR(36) PRIMARY KEY,
    issue_key CHAR(64) NOT NULL,
    document_version_id BIGINT NOT NULL,
    issue_type VARCHAR(64) NOT NULL,
    subject_key VARCHAR(160) NOT NULL,
    expected_value VARCHAR(500) NULL,
    actual_value VARCHAR(500) NULL,
    suggested_action VARCHAR(64) NOT NULL,
    state VARCHAR(20) NOT NULL,
    state_version BIGINT NOT NULL DEFAULT 0,
    claimed_by VARCHAR(128) NULL,
    claim_until DATETIME(6) NULL,
    repair_attempt INT NOT NULL DEFAULT 0,
    last_error_code VARCHAR(64) NULL,
    last_seen_run_id CHAR(36) NOT NULL,
    first_seen_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    last_seen_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    resolved_at DATETIME(6) NULL,
    resolution_note VARCHAR(500) NULL,
    UNIQUE KEY uk_issue_key (issue_key),
    INDEX idx_issue_state (state, issue_type, claim_until, last_seen_at),
    INDEX idx_issue_version (document_version_id, state)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
