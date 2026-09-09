package com.rag.backend.agent.materials;

import java.time.LocalDateTime;
import java.util.List;

public record MaterialStatus(long sessionId, String state, String reason,
                             boolean updateAvailable, LocalDateTime expiresAt,
                             List<Version> versions) {
    public MaterialStatus { versions = List.copyOf(versions); }
    public record Version(long documentId, long documentVersionId,
                          int versionNo, String documentName) { }
}
