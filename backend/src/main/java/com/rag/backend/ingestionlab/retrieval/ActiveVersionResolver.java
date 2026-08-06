package com.rag.backend.ingestionlab.retrieval;

import java.util.Set;

/** 读取一个课程当前允许在线检索的文档版本集合。 */
public interface ActiveVersionResolver {
    Set<Long> forCourse(long courseId);
}
