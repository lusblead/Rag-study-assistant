-- 迁移目的：用版本、业务键、唯一约束和索引把幂等语义落到 MySQL。
-- document_versions 保存持久化事实，不能只依赖进程内对象。
CREATE TABLE document_versions (
                                   id                      BIGINT AUTO_INCREMENT PRIMARY KEY,
    -- 逻辑文档身份，可有多个不可变版本。
                                   document_id             BIGINT       NOT NULL,
                                   version_no              INT          NOT NULL,
    -- 源内容 SHA-256，文件名不能替代。
                                   content_hash            CHAR(64)     NOT NULL,
    -- 解析、切块、Embedding 与索引配置指纹。
                                   pipeline_fingerprint    CHAR(64)     NOT NULL,
    -- 稳定机器状态，由状态机与条件更新约束。
                                   state                   VARCHAR(32)  NOT NULL,
                                   expected_chunk_count    INT          NULL,
                                   indexed_chunk_count     INT          NOT NULL DEFAULT 0,
    -- 乐观锁版本，阻止旧 Worker 覆盖。
                                   state_version           BIGINT       NOT NULL DEFAULT 0,
                                   created_at              DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                   ready_at                DATETIME     NULL,
                                   activated_at            DATETIME     NULL,
                                   superseded_at           DATETIME     NULL,
    -- 并发防线：唯一键最终裁决先查后插竞态。
                                   UNIQUE KEY uk_document_version_no (document_id, version_no),
    -- 并发防线：唯一键最终裁决先查后插竞态。
                                   UNIQUE KEY uk_document_content_pipeline
                                       (document_id, content_hash, pipeline_fingerprint),
    -- 访问索引：轮询/对账避免全表扫描。
                                   INDEX idx_document_version_state (document_id, state)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE documents
    ADD COLUMN active_version_id BIGINT NULL,
    ADD INDEX idx_documents_active_version (active_version_id);

ALTER TABLE knowledge_chunks
    ADD COLUMN document_version_id BIGINT NULL,
    ADD COLUMN chunk_business_key VARCHAR(160) NULL,
    ADD COLUMN content_hash CHAR(64) NULL,
    ADD UNIQUE KEY uk_version_chunk (document_version_id, chunk_business_key),
    ADD INDEX idx_chunks_version_embedding (document_version_id, embedding_status);

-- 持久化执行权，用 owner、期限和版本阻止并发误提交。
-- ingest_jobs 保存持久化事实，不能只依赖进程内对象。
CREATE TABLE ingest_jobs (
                             job_id               CHAR(36)     PRIMARY KEY,
    -- 版本归属，跨存储对账的批次边界。
                             document_version_id  BIGINT       NOT NULL,
                             job_type             VARCHAR(32)  NOT NULL,
    -- 稳定机器状态，由状态机与条件更新约束。
                             state                VARCHAR(24)  NOT NULL,
                             attempt              INT          NOT NULL DEFAULT 0,
                             max_attempts         INT          NOT NULL DEFAULT 5,
    -- 执行权持有者，续租和完成必须匹配。
                             lease_owner          VARCHAR(120) NULL,
    -- 租约截止，过期后其他 Worker 才能接管。
                             lease_until          DATETIME(6)  NULL,
    -- 最早重试时间，用持久化退避替代 sleep。
                             next_run_at          DATETIME(6)  NOT NULL,
    -- 乐观锁版本，阻止旧 Worker 覆盖。
                             state_version        BIGINT       NOT NULL DEFAULT 0,
                             error_code           VARCHAR(64)  NULL,
                             error_detail_digest  VARCHAR(500) NULL,
                             created_at           DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
                             started_at           DATETIME(6)  NULL,
                             finished_at          DATETIME(6)  NULL,
    -- 并发防线：唯一键最终裁决先查后插竞态。
                             UNIQUE KEY uk_version_job_type (document_version_id, job_type),
    -- 访问索引：轮询/对账避免全表扫描。
                             INDEX idx_job_poll (state, next_run_at, lease_until)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 让业务状态与事件原子落库，并为步骤建立幂等记录。
-- ingest_steps 保存持久化事实，不能只依赖进程内对象。
CREATE TABLE ingest_steps (
                              job_id            CHAR(36)     NOT NULL,
                              step_name         VARCHAR(40)  NOT NULL,
    -- 稳定机器状态，由状态机与条件更新约束。
                              state             VARCHAR(20)  NOT NULL,
                              attempt           INT          NOT NULL DEFAULT 0,
                              input_digest      CHAR(64)     NOT NULL,
                              output_ref        VARCHAR(500) NULL,
                              output_digest     CHAR(64)     NULL,
                              processed_count   INT          NOT NULL DEFAULT 0,
                              error_code        VARCHAR(64)  NULL,
                              started_at        DATETIME(6)  NULL,
                              finished_at       DATETIME(6)  NULL,
                              PRIMARY KEY (job_id, step_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- outbox_events 保存持久化事实，不能只依赖进程内对象。
CREATE TABLE outbox_events (
                               event_id          CHAR(36)      PRIMARY KEY,
                               aggregate_type    VARCHAR(40)   NOT NULL,
                               aggregate_id      VARCHAR(80)   NOT NULL,
                               event_type        VARCHAR(64)   NOT NULL,
                               payload_json      JSON          NOT NULL,
                               status            VARCHAR(20)   NOT NULL DEFAULT 'NEW',
                               attempts          INT           NOT NULL DEFAULT 0,
                               available_at      DATETIME(6)   NOT NULL,
                               claimed_by        VARCHAR(120)  NULL,
                               claim_until       DATETIME(6)   NULL,
    -- 乐观锁版本，阻止旧 Worker 覆盖。
                               state_version     BIGINT        NOT NULL DEFAULT 0,
                               created_at        DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
                               published_at      DATETIME(6)   NULL,
    -- 访问索引：轮询/对账避免全表扫描。
                               INDEX idx_outbox_poll (status, available_at, claim_until)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;