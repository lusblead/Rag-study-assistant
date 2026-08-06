package com.rag.backend.agent.controller;

import com.rag.backend.common.Result;
import com.rag.backend.ingestionlab.application.IngestApplicationService;
import com.rag.backend.ingestionlab.application.IngestSubmissionResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/agent/documents")
// 提供文档入库相关的 Agent API。
public class AgentDocumentController {
    private final IngestApplicationService ingestService;

    public AgentDocumentController(IngestApplicationService ingestService) {
        this.ingestService = ingestService;
    }

    @PostMapping("/{documentId}/ingest")
    public ResponseEntity<Result<IngestSubmissionResponse>> ingest(
            @PathVariable Long documentId) {
        var submission = ingestService.submit(documentId);
        return ResponseEntity.accepted().body(
                Result.ok(IngestSubmissionResponse.from(submission)));
    }
}