package com.rag.backend.ingestionlab.retrieval;

import java.util.List;
import java.util.Set;

/** 版本化向量搜索端口：courseId 和 activeVersionIds 必须同时成为远端过滤条件。 */
public interface VersionedVectorSearch {
    List<VersionedVectorHit> search(
            long courseId,
            Set<Long> activeVersionIds,
            List<Double> queryVector,
            int topK);
}
