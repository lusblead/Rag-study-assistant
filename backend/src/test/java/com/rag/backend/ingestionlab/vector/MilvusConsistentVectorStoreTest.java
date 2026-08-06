package com.rag.backend.ingestionlab.vector;

import io.milvus.v2.common.IndexParam;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MilvusConsistentVectorStoreTest {

    @Test
    void parsesExplicitFlatIndexForDeterministicEvaluation() {
        assertEquals(IndexParam.IndexType.FLAT,
                MilvusConsistentVectorStore.parseIndexType("flat"));
        assertEquals(IndexParam.IndexType.AUTOINDEX,
                MilvusConsistentVectorStore.parseIndexType(" "));
        assertThrows(IllegalArgumentException.class,
                () -> MilvusConsistentVectorStore.parseIndexType("unknown"));
    }
}
