-- Step 4.3: persist only the minimal W3C parent needed to resume an INGEST trace.
-- correlationId is the existing durable job_id; baggage and source data are never stored.
ALTER TABLE ingest_jobs
    ADD COLUMN submit_traceparent VARCHAR(55) NULL AFTER error_detail_digest;

-- OutboxEventMapper.markRetry already writes this field. Keep schema and retry SQL aligned.
ALTER TABLE outbox_events
    ADD COLUMN last_error_code VARCHAR(64) NULL AFTER claim_until;
