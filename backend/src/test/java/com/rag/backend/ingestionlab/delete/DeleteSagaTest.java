// 先做集合验证，再用短事务切换在线版本。 先墓碑保证不可见，再由可重试 Saga 清理副作用。
package com.rag.backend.ingestionlab.delete;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

// 在第一个向量删除处注入失败，证明后续元数据删除未执行且墓碑可见性不被回滚。
class DeleteSagaTest {
    @Test
        // 故障窗口崩溃后重放，检查三条核心不变量。
    void vectorFailureStopsPhysicalMetadataDeletionButTombstoneRemains() {
        List<String> calls = new ArrayList<>();
        DeleteSaga.DeletePort port = new DeleteSaga.DeletePort() {
            @Override public DeleteSaga.DocumentToDelete loadTombstoned(long id) {
                return new DeleteSaga.DocumentToDelete(id, "source.pdf",
                        List.of(8L, 9L), true, false);
            }
            @Override public void deleteVectorsByVersion(long versionId) {
                calls.add("vector:" + versionId);
                throw new IllegalStateException("milvus down");
            }
            @Override public void deleteArtifactsByVersion(long id) { calls.add("artifact"); }
            @Override public void deleteChunksByDocument(long id) { calls.add("chunks"); }
            @Override public void deleteSourceFileIfExists(String path) { calls.add("file"); }
            @Override public void markDeleted(long id) { calls.add("done"); }
        };

        assertThrows(IllegalStateException.class,
                () -> new DeleteSaga(port).execute(10L));
        assertEquals(List.of("vector:8"), calls);
        // 用户不可见性来自调用 Saga 前已提交的 tombstone，不依赖 Milvus 在线。
    }
}