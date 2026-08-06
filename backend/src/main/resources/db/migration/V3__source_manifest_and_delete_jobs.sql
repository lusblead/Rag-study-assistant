-- 保存可读管线身份和可信源引用，便于重放、对账与效果快照追溯。
ALTER TABLE document_versions
    ADD COLUMN source_ref VARCHAR(500) NULL,
    ADD COLUMN pipeline_manifest JSON NULL,
    ADD COLUMN manifest_schema_version INT NOT NULL DEFAULT 1;

-- DELETE Job 以逻辑文档为目标；INGEST Job 同时保存 document_id 便于统一查询和调度。
ALTER TABLE ingest_jobs
    ADD COLUMN document_id BIGINT NULL AFTER job_id,
    ADD INDEX idx_job_document_type (document_id, job_type, state);

UPDATE ingest_jobs j
JOIN document_versions v ON v.id = j.document_version_id
SET j.document_id = v.document_id
WHERE j.document_id IS NULL;

ALTER TABLE ingest_jobs
    MODIFY COLUMN document_id BIGINT NOT NULL;
