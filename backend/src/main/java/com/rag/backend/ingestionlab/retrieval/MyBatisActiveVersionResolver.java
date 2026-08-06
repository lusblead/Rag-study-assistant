package com.rag.backend.ingestionlab.retrieval;

import com.rag.backend.document.DocumentMapper;
import org.springframework.stereotype.Repository;

import java.util.Set;

/**
 * 从 documents.active_version_id 解析在线版本，并同时要求 Document 与 Version
 * 都处于可读状态。Retriever 不自行猜测版本状态。
 */
@Repository
public class MyBatisActiveVersionResolver implements ActiveVersionResolver {
    private final DocumentMapper documents;

    public MyBatisActiveVersionResolver(DocumentMapper documents) {
        this.documents = documents;
    }

    @Override
    public Set<Long> forCourse(long courseId) {
        Set<Long> values = documents.selectActiveVersionIdsByCourseId(courseId);
        return values == null ? Set.of() : Set.copyOf(values);
    }
}
