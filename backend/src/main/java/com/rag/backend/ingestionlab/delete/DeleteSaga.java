// 先墓碑保证不可见，再由可重试 Saga 清理副作用。
package com.rag.backend.ingestionlab.delete;

import org.springframework.stereotype.Service;

import java.util.List;

// 删除 Saga：墓碑先不可见，再可重试清理各存储。
@Service
public final class DeleteSaga {
    private final DeletePort port;

    // DeletePort 汇总四类存储操作，Saga 只负责固定删除顺序和重放语义。
    public DeleteSaga(DeletePort port) { this.port = port; }

    // 幂等清理，中途失败时墓碑仍保持不可见。
    public Result execute(long documentId) {
        DocumentToDelete document = port.loadTombstoned(documentId);
        if (document.deleted()) return new Result(documentId, true);
        if (!document.deleting()) {
            throw new IllegalStateException("Document is not tombstoned: " + documentId);
        }

        // 每一步必须幂等；任一步失败由 Job RETRY_WAIT 重放。
        for (long versionId : document.versionIds()) {
            port.deleteVectorsByVersion(versionId);
            port.deleteArtifactsByVersion(versionId);
        }
        port.deleteChunksByDocument(documentId);
        port.deleteSourceFileIfExists(document.sourcePath());
        port.markDeleted(documentId);
        return new Result(documentId, false);
    }

    // DeletePort 把向量、Artifact、Chunk、源文件和最终生命周期更新统一暴露给 Saga，各 Adapter 必须幂等。
    public interface DeletePort {
        DocumentToDelete loadTombstoned(long documentId);
        void deleteVectorsByVersion(long versionId);
        void deleteArtifactsByVersion(long versionId);
        void deleteChunksByDocument(long documentId);
        void deleteSourceFileIfExists(String sourcePath);
        void markDeleted(long documentId);
    }

    // DocumentToDelete：墓碑后的删除快照，固定源路径和全部版本列表。
    public record DocumentToDelete(long id, String sourcePath,
                                   List<Long> versionIds,
                                   boolean deleting, boolean deleted) { }
    // 删除结果关联逻辑文档，并区分本次完成清理还是读取到已删除终态。
    public record Result(long documentId, boolean alreadyDeleted) { }
}
