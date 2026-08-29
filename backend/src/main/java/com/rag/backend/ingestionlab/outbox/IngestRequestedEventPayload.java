package com.rag.backend.ingestionlab.outbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.backend.observability.trace.TraceCarrier;
import com.rag.backend.observability.trace.TraceContextService;

/** Versioned, privacy-bounded payload for an INGEST_REQUESTED outbox event. */
public record IngestRequestedEventPayload(
        int schemaVersion,
        String jobId,
        long documentVersionId,
        String traceparent,
        String correlationId) {

    public static final int CURRENT_SCHEMA_VERSION = 1;

    public static IngestRequestedEventPayload current(
            String jobId,
            long documentVersionId,
            TraceCarrier carrier) {
        String traceparent = carrier == null ? null : carrier.traceparent();
        if (!TraceContextService.isValidTraceparent(traceparent)) {
            throw new IllegalArgumentException(
                    "Current ingest outbox payload requires valid trace context");
        }
        return new IngestRequestedEventPayload(
                CURRENT_SCHEMA_VERSION,
                jobId,
                documentVersionId,
                traceparent,
                jobId);
    }

    /**
     * Decodes both the current payload and the legacy payload that only contained
     * jobId/documentVersionId. The aggregate id remains the authoritative job id.
     */
    public static IngestRequestedEventPayload decode(
            ObjectMapper objectMapper,
            String payloadJson,
            String aggregateId) {
        try {
            JsonNode root = objectMapper.readTree(payloadJson);
            if (root == null || !root.isObject()) {
                throw new IllegalArgumentException("Ingest outbox payload must be an object");
            }
            int schemaVersion = root.has("schemaVersion")
                    ? root.path("schemaVersion").asInt(-1)
                    : 0;
            if (schemaVersion < 0 || schemaVersion > CURRENT_SCHEMA_VERSION) {
                throw new IllegalArgumentException("Unsupported ingest outbox payload schema");
            }
            String jobId = text(root, "jobId");
            if (jobId == null || !jobId.equals(aggregateId)) {
                throw new IllegalArgumentException("Ingest outbox job identity mismatch");
            }
            if (!root.has("documentVersionId")
                    || !root.path("documentVersionId").canConvertToLong()) {
                throw new IllegalArgumentException("Ingest outbox version identity is missing");
            }
            long versionId = root.path("documentVersionId").longValue();
            String traceparent = schemaVersion == 0
                    ? null
                    : text(root, "traceparent");
            if (schemaVersion == CURRENT_SCHEMA_VERSION
                    && !TraceContextService.isValidTraceparent(traceparent)) {
                throw new IllegalArgumentException(
                        "Current ingest outbox payload has invalid trace context");
            }
            String correlationId = schemaVersion == 0
                    ? jobId
                    : text(root, "correlationId");
            if (correlationId == null) {
                correlationId = jobId;
            }
            if (!jobId.equals(correlationId)) {
                throw new IllegalArgumentException("Ingest correlation must equal job id");
            }
            return new IngestRequestedEventPayload(
                    schemaVersion, jobId, versionId, traceparent, jobId);
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalArgumentException("Cannot decode ingest outbox payload", error);
        }
    }

    public TraceCarrier carrier() {
        return new TraceCarrier(traceparent, jobId);
    }

    private static String text(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || value.isNull() || !value.isTextual()) {
            return null;
        }
        String text = value.textValue();
        return text == null || text.isBlank() ? null : text;
    }
}
