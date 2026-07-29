// 状态机目的：集中允许边，任何未登记的跳转都在副作用前失败。
package com.rag.backend.ingestionlab.state;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

// 纯 Java 状态机：集中声明合法迁移，不负责外部调用或事务。
public final class DocumentVersionStateMachine {
    private static final Map<DocumentVersionState, Set<DocumentVersionState>> ALLOWED =
            new EnumMap<>(DocumentVersionState.class);

    static {
        // 受理成功后 Version 进入 BUILDING；排队和重试属于 Job 状态。
        allow(DocumentVersionState.UPLOADED,
                DocumentVersionState.BUILDING, DocumentVersionState.CANCELLED);
        allow(DocumentVersionState.BUILDING,
                DocumentVersionState.PARSING, DocumentVersionState.FAILED);
        allow(DocumentVersionState.PARSING,
                DocumentVersionState.CHUNKING, DocumentVersionState.FAILED);
        allow(DocumentVersionState.CHUNKING,
                DocumentVersionState.EMBEDDING, DocumentVersionState.FAILED);
        allow(DocumentVersionState.EMBEDDING,
                DocumentVersionState.INDEXING, DocumentVersionState.FAILED);
        allow(DocumentVersionState.INDEXING,
                DocumentVersionState.VERIFYING, DocumentVersionState.FAILED);
        allow(DocumentVersionState.VERIFYING,
                DocumentVersionState.READY, DocumentVersionState.INCONSISTENT,
                DocumentVersionState.FAILED);
        allow(DocumentVersionState.READY, DocumentVersionState.ACTIVE);
        allow(DocumentVersionState.ACTIVE, DocumentVersionState.SUPERSEDED);
        // 只有显式回滚用例可以把保留期内的旧版本恢复为 ACTIVE。
        allow(DocumentVersionState.SUPERSEDED, DocumentVersionState.ACTIVE);
        allow(DocumentVersionState.INCONSISTENT,
                DocumentVersionState.REPAIRING, DocumentVersionState.FAILED);
        allow(DocumentVersionState.REPAIRING,
                DocumentVersionState.VERIFYING, DocumentVersionState.FAILED);
    }

    private DocumentVersionStateMachine() { }

    // 非法跳转在 Mapper CAS 之前失败，避免把错误状态写进数据库。
    public static void requireAllowed(DocumentVersionState from,
                                      DocumentVersionState to) {
        if (!ALLOWED.getOrDefault(from, Set.of()).contains(to)) {
            throw new IllegalStateException("Illegal ingest transition: " + from + " -> " + to);
        }
    }

    public static boolean canTransition(DocumentVersionState from,
                                        DocumentVersionState to) {
        return ALLOWED.getOrDefault(from, Set.of()).contains(to);
    }

    private static void allow(DocumentVersionState from,
                              DocumentVersionState first,
                              DocumentVersionState... rest) {
        EnumSet<DocumentVersionState> targets = EnumSet.of(first, rest);
        ALLOWED.put(from, Set.copyOf(targets));
    }
}