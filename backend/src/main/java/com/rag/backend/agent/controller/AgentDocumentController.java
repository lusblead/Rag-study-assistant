package com.rag.backend.agent.controller;

import com.rag.backend.agent.ingest.AgentDocumentIngestService;
import com.rag.backend.common.Result;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/agent/documents")
// 提供文档入库相关的 Agent API。
public class AgentDocumentController {
    private final AgentDocumentIngestService ingestService;

    public AgentDocumentController(AgentDocumentIngestService ingestService) {
        this.ingestService = ingestService;
    }

    @PostMapping("/{documentId}/ingest")
    public Result<Integer> ingest(@PathVariable Long documentId) {
        int chunkCount = ingestService.ingestDocument(documentId);
        return Result.ok(chunkCount);
    }
}