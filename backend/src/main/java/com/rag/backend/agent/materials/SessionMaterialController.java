package com.rag.backend.agent.materials;

import com.rag.backend.agent.history.ChatHistoryService;
import com.rag.backend.agent.model.KnowledgeChunk;
import com.rag.backend.agent.repository.KnowledgeChunkRepository;
import com.rag.backend.common.BizException;
import com.rag.backend.common.Result;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/agent/chat/sessions")
public class SessionMaterialController {
    private final SessionMaterialService materials;
    private final KnowledgeChunkRepository chunks;
    private final ChatHistoryService history;
    public SessionMaterialController(SessionMaterialService materials, KnowledgeChunkRepository chunks, ChatHistoryService history) {
        this.materials = materials; this.chunks = chunks; this.history = history;
    }

    @PostMapping
    public Result<Map<String, Long>> create(@RequestParam long courseId) {
        return Result.ok(Map.of("sessionId", history.resolveSession(null, courseId, "新对话")));
    }

    @GetMapping("/{sessionId}/materials")
    public Result<MaterialStatus> status(@PathVariable long sessionId, @RequestParam long courseId) {
        return Result.ok(materials.status(sessionId, courseId));
    }

    @GetMapping("/{sessionId}/references/{chunkId}")
    public Result<KnowledgeChunk> reference(@PathVariable long sessionId, @PathVariable long chunkId,
            @RequestParam long courseId, @RequestParam long documentVersionId) {
        // References may not implicitly bind a legacy/unbound session to today's active version.
        if (!"READY".equals(materials.status(sessionId, courseId).state())) {
            throw new MaterialScopeException("VERSION_UNAVAILABLE");
        }
        try (SessionMaterialService.ReadHandle read = materials.acquire(sessionId, courseId)) {
            if (!read.scope().activeVersionIds().contains(documentVersionId)) {
                throw new BizException(404, "引用不属于本会话的资料范围");
            }
            KnowledgeChunk chunk = chunks.findById(chunkId);
            if (chunk == null) throw new MaterialScopeException("EVIDENCE_MISSING");
            if (!java.util.Objects.equals(chunk.getDocumentVersionId(), documentVersionId)
                    || !java.util.Objects.equals(chunk.getCourseId(), courseId)) {
                throw new BizException(404, "引用不属于本会话的资料范围");
            }
            read.validate();
            return Result.ok(chunk);
        }
    }
}
