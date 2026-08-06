// 激活事务：锁 Document，校验 READY，切 active 指针并把旧版改为 SUPERSEDED。
package com.rag.backend.ingestionlab.activate;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
// VersionActivationService 在同一个 MySQL 事务中切 active 指针并降级旧版；远程核验必须在调用前完成。
public class VersionActivationService {
    // 激活涉及的四个写动作全部经同一 Mapper，以便 Spring 事务统一回滚并检查每次影响行数。
    private final VersionActivationMapper mapper;

    // Service 只依赖激活 Mapper，不注入 Milvus，避免持有 Document 行锁等待远程请求。
    public VersionActivationService(VersionActivationMapper mapper) {
        this.mapper = mapper;
    }

    @Transactional
    // 锁文档、验 READY、切 active 并降级旧版。
    public Activation activate(long documentId, long readyVersionId) {
        if (mapper.lockDocument(documentId) == null) {
            throw new IllegalArgumentException("Unknown document: " + documentId);
        }
        Long previous = mapper.currentActive(documentId);
        if (previous != null && previous == readyVersionId) {
            return new Activation(previous, readyVersionId, true);
        }
        if (mapper.activateNew(documentId, readyVersionId) != 1) {
            throw new IllegalStateException("Version is not READY: " + readyVersionId);
        }
        if (mapper.pointDocumentTo(documentId, readyVersionId) != 1) {
            throw new IllegalStateException("Document is deleting or deleted");
        }
        if (previous != null && mapper.supersede(previous) != 1) {
            throw new IllegalStateException("Previous ACTIVE changed concurrently");
        }
        return new Activation(previous, readyVersionId, false);
    }

    // Activation 返回切换前后 Version 身份，并标记重复调用是否已指向同一 active 版本。
    public record Activation(Long previousVersionId, long activeVersionId,
                             boolean alreadyActive) { }
}