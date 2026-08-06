// Harness 模型：以独立内存表模拟 Document、Version、Chunk、Vector 及跨存储失败窗口。
package com.rag.backend.ingestionlab.harness;

import com.rag.backend.ingestionlab.vector.DeterministicVectorId;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

// 可靠摄取模型：模拟多存储副作用并验证三条不变量。
public final class ReliableIngestionHarness {
    private final AtomicLong versionIds = new AtomicLong();
    private final AtomicLong chunkIds = new AtomicLong();
    private final Map<Long, Document> documents = new LinkedHashMap<>();
    private final Map<Long, Version> versions = new LinkedHashMap<>();
    // 版本加业务键唯一，重放不新增逻辑块。
    private final Map<String, Chunk> chunksByBusinessKey = new LinkedHashMap<>();
    // 支持指定 ID upsert，重复写才能收敛。
    private final Map<Long, Vector> vectors = new LinkedHashMap<>();

    public void createDocument(long documentId) {
        documents.put(documentId, new Document(documentId, null, "ACTIVE"));
    }

    public long createVersion(long documentId, String contentHash, String pipeline) {
        requireDocument(documentId);
        Version duplicate = versions.values().stream()
                .filter(v -> v.documentId == documentId)
                .filter(v -> v.contentHash.equals(contentHash))
                .filter(v -> v.pipeline.equals(pipeline))
                .findFirst().orElse(null);
        if (duplicate != null) return duplicate.id;
        long id = versionIds.incrementAndGet();
        // BUILDING 属于 DocumentVersionState；QUEUED 属于 IngestJobState，本模型没有 Job 聚合。
        versions.put(id, new Version(id, documentId, contentHash, pipeline,
                "BUILDING", 0));
        return id;
    }

    // 从中断点重放，业务键和确定性 ID 复用副作用。
    public void resume(long versionId, List<String> contents, FaultInjector faults) {
        Version version = requireVersion(versionId);
        if ("ACTIVE".equals(version.state)) return;
        // activation 前崩溃时 Version 已是 READY；恢复只重放激活，不应重新进入 Embedding。
        if ("READY".equals(version.state)) {
            faults.hit(FailurePoint.BEFORE_ACTIVE_SWITCH, -1);
            activate(version);
            return;
        }
        version.state = "EMBEDDING";
        version.expectedCount = contents.size();

        for (int index = 0; index < contents.size(); index++) {
            int itemIndex = index;
            String content = contents.get(index);
            String businessKey = itemIndex + ":" + Integer.toHexString(content.hashCode());
            String storageKey = versionId + "|" + businessKey;
            // 幂等写：键存在就复用，重放不增加逻辑数据。
            Chunk chunk = chunksByBusinessKey.computeIfAbsent(storageKey,
                    ignored -> new Chunk(chunkIds.incrementAndGet(), versionId,
                            businessKey, content, "PENDING", null));
            if (!chunk.content.equals(content)) {
                throw new IllegalStateException("business key collision");
            }
            faults.hit(FailurePoint.AFTER_CHUNK_INSERT, itemIndex);

            long vectorId = DeterministicVectorId.from(versionId, businessKey,
                    "fake-embedding-v1", 2);
            vectors.put(vectorId, new Vector(vectorId, chunk.id, versionId));
            faults.hit(FailurePoint.AFTER_VECTOR_UPSERT_BEFORE_MYSQL_DONE, itemIndex);
            chunk.vectorId = vectorId;
            chunk.status = "DONE";
        }

        version.state = "VERIFYING";
        faults.hit(FailurePoint.BEFORE_VERIFY, -1);
        Verification verification = verify(versionId);
        if (!verification.passed()) {
            version.state = "INCONSISTENT";
            throw new IllegalStateException("verification failed: " + verification);
        }
        version.state = "READY";
        faults.hit(FailurePoint.BEFORE_ACTIVE_SWITCH, -1);
        activate(version);
    }

    // 先墓碑/清 active，再跨存储清理。
    public void deleteDocument(long documentId, FaultInjector faults) {
        Document document = requireDocument(documentId);
        document.lifecycle = "DELETING";
        document.activeVersionId = null;
        faults.hit(FailurePoint.AFTER_TOMBSTONE_BEFORE_VECTOR_DELETE, -1);

        Set<Long> versionIdsForDocument = versions.values().stream()
                .filter(v -> v.documentId == documentId)
                .map(v -> v.id).collect(Collectors.toSet());
        vectors.values().removeIf(v -> versionIdsForDocument.contains(v.versionId));
        chunksByBusinessKey.values().removeIf(c -> versionIdsForDocument.contains(c.versionId));
        document.lifecycle = "DELETED";
    }

    // 集合验证通过后才切 active，并降级旧版。
    public Verification verify(long versionId) {
        Version version = requireVersion(versionId);
        Set<Long> expected = chunksByBusinessKey.values().stream()
                .filter(c -> c.versionId == versionId)
                .filter(c -> "DONE".equals(c.status))
                .map(c -> c.vectorId).filter(Objects::nonNull)
                .collect(Collectors.toCollection(TreeSet::new));
        Set<Long> actual = vectors.values().stream()
                .filter(v -> v.versionId == versionId)
                .map(v -> v.id)
                .collect(Collectors.toCollection(TreeSet::new));
        Set<Long> missing = new TreeSet<>(expected);
        missing.removeAll(actual);
        Set<Long> orphan = new TreeSet<>(actual);
        orphan.removeAll(expected);
        boolean passed = expected.size() == version.expectedCount
                && missing.isEmpty() && orphan.isEmpty();
        return new Verification(passed, expected, actual, missing, orphan);
    }

    public boolean isVisible(long documentId) {
        Document document = requireDocument(documentId);
        return "ACTIVE".equals(document.lifecycle)
                && document.activeVersionId != null
                && "ACTIVE".equals(requireVersion(document.activeVersionId).state);
    }

    public Long activeVersion(long documentId) {
        return requireDocument(documentId).activeVersionId;
    }

    public int logicalChunkCount(long versionId) {
        return (int) chunksByBusinessKey.values().stream()
                .filter(c -> c.versionId == versionId).count();
    }

    public int vectorCount(long versionId) {
        return (int) vectors.values().stream()
                .filter(v -> v.versionId == versionId).count();
    }

    // 集合验证通过后才切 active，并降级旧版。
    private void activate(Version next) {
        Document document = requireDocument(next.documentId);
        if (!"ACTIVE".equals(document.lifecycle)) {
            throw new IllegalStateException("document is not activatable");
        }
        if (document.activeVersionId != null) {
            requireVersion(document.activeVersionId).state = "SUPERSEDED";
        }
        next.state = "ACTIVE";
        document.activeVersionId = next.id;
    }

    private Document requireDocument(long id) {
        Document value = documents.get(id);
        if (value == null) throw new IllegalArgumentException("unknown document " + id);
        return value;
    }
    private Version requireVersion(long id) {
        Version value = versions.get(id);
        if (value == null) throw new IllegalArgumentException("unknown version " + id);
        return value;
    }

    // Verification 保存期望/实际集合及其差集，测试可直接说明哪个不变量因哪个 ID 失败。
    public record Verification(boolean passed, Set<Long> expected,
                               Set<Long> actual, Set<Long> missing,
                               Set<Long> orphan) { }

    // Document：Harness 聚合根，activeVersionId 和 lifecycle 共同决定是否可见。
    private static final class Document {
        private final long id;
        private Long activeVersionId;
        private String lifecycle;
        // Document 只保存逻辑生命周期和 active 指针，用于模拟在线可见性门禁。
        private Document(long id, Long activeVersionId, String lifecycle) {
            this.id = id; this.activeVersionId = activeVersionId;
            this.lifecycle = lifecycle;
        }
    }
    // Version：Harness 暂存版本，保存文档归属、内容/管线身份和构建状态。
    private static final class Version {
        private final long id;
        private final long documentId;
        private final String contentHash;
        private final String pipeline;
        private String state;
        private int expectedCount;
        // Version 固定文档归属、内容/管线身份，并保存 Harness 当前构建状态和期望数量。
        private Version(long id, long documentId, String contentHash,
                        String pipeline, String state, int expectedCount) {
            this.id = id; this.documentId = documentId;
            this.contentHash = contentHash; this.pipeline = pipeline;
            this.state = state; this.expectedCount = expectedCount;
        }
    }
    // Chunk：Harness 逻辑块，businessKey 去重，物理 id 不参与幂等判断。
    private static final class Chunk {
        private final long id;
        private final long versionId;
        private final String businessKey;
        private final String content;
        private String status;
        private Long vectorId;
        // Chunk 固定业务键和内容；状态与 vectorId 模拟 MySQL 在远端写入前后的持久化事实。
        private Chunk(long id, long versionId, String businessKey,
                      String content, String status, Long vectorId) {
            this.id = id; this.versionId = versionId;
            this.businessKey = businessKey; this.content = content;
            this.status = status; this.vectorId = vectorId;
        }
    }
    // Vector：Harness 向量行，确定性 id 关联 MySQL chunk 与 version。
    private record Vector(long id, long mysqlChunkId, long versionId) { }
}