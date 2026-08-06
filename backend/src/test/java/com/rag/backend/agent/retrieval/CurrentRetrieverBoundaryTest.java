package com.rag.backend.agent.retrieval;

import com.rag.backend.agent.embedding.EmbeddingClient;
import com.rag.backend.agent.model.KnowledgeChunk;
import com.rag.backend.agent.repository.KnowledgeChunkRepository;
import com.rag.backend.agent.rerank.LocalLexicalKnowledgeReranker;
import com.rag.backend.ingestionlab.retrieval.VersionedVectorHit;
import com.rag.backend.ingestionlab.retrieval.VersionedVectorSearch;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 第 01 章：冻结当前 Retriever 的候选边界。
 *
 * 架构特征测试，证明当前流程是：
 *
 * <pre>
 * Dense Candidate Search
 *   → MySQL 回表
 *   → Local Lexical Rerank
 * </pre>
 *
 * 关键不变量：Local Lexical Reranker 只能重新排列 Dense 已返回的候选，
 * 不能补回 Dense 阶段未返回的词面强相关 Chunk。
 */
class CurrentRetrieverBoundaryTest {

    /**
     * 三个测试 Chunk 的职责：
     * 1. denseHitChunk（例如 chunkId=11、documentVersionId=101）：
     *    - 存在于 Repository；
     *    - 由 Dense 返回（VersionedVectorHit 列表包含它）；
     *    - 词面与 query 相关。
     *    - 预期：进入最终结果。
     * 2. lexicallyRelevantMissedChunk（例如 chunkId=12、documentVersionId=101）：
     *    - 存在于 Repository；
     *    - 词面与 query 高度重合（Local Lexical 会给很高词法分）；
     *    - 不在 Dense 返回的 VersionedVectorHit 列表中。
     *    - 预期：不进入最终结果（本测试核心断言）。
     * 3. unrelatedChunk（例如 chunkId=13、documentVersionId=101）：
     *    - 存在于 Repository；
     *    - 词面与 query 无关；
     *    - 不在 Dense 返回列表中。
     *    - 预期：不进入最终结果（负向控制）。
     *
     * 学习者需完成：
     * 1. 用 chunk(...) 构造上面三个 Chunk；
     * 2. 构造 Dense 返回的候选集合（只包含 denseHitChunk 对应的 VersionedVectorHit）；
     * 3. 构造 retriever：真实 LocalLexicalKnowledgeReranker、ActiveVersionResolver 返回 101L、
     *    VersionedVectorSearch 返回上述候选、CountingRepository 包含三个 Chunk；
     * 4. 调用 retriever.retrieve(7L, query, 5)；
     * 5. 断言 lexicallyRelevantMissedChunk 不在最终结果；
     * 6. 断言最终结果只包含 Dense 候选（子集断言）；
     * 7. 断言 CountingRepository.courseFallbackCalls == 0（没有 findByCourseId fallback）。
     */
    @Test
    void lexicalRerankerCannotRecoverChunkMissedByDenseRetrieval() {
        KnowledgeChunk denseHit = chunk(11L,101L,"网关默认请求超时为3秒");
        KnowledgeChunk missedChunk = chunk(12L,101L,"网关请求一般是3秒");
        KnowledgeChunk unrelatedChunk = chunk(13L,101L,"缓存雪崩是指大量key同时过期导致大量的请求打到数据库");

        StubVectorSearch vectors = new StubVectorSearch(
                List.of(new VersionedVectorHit(11L,101L,0.9))
        );

        CountingRepository repo = new CountingRepository(denseHit, missedChunk, unrelatedChunk);
        MilvusKnowledgeRetriever retriever = new MilvusKnowledgeRetriever(
                new CountingEmbedding(),
                ignored -> Set.of(101L),
                vectors,
                repo,
                null,
                new LocalLexicalKnowledgeReranker(),
                -1.0,
                20
        );

        List<RetrievedChunk> result = retriever.retrieve(7L,"网关请求一般是多少秒？",5);

        assertFalse(result.stream().anyMatch(c->c.chunkId()==12L));
        assertTrue(result.stream().allMatch(c -> c.chunkId() == 11L));
        assertEquals(0, repo.courseFallbackCalls);
        assertFalse(result.stream().anyMatch(c -> c.chunkId() == 13L));
    }

    /**
     * 构造一个 KnowledgeChunk（测试基建，AI 已补齐）。
     */
    private KnowledgeChunk chunk(long chunkId, long documentVersionId, String content) {
        KnowledgeChunk value = new KnowledgeChunk();
        value.setId(chunkId);
        value.setCourseId(7L);
        value.setDocumentId(3L);
        value.setDocumentVersionId(documentVersionId);
        value.setTitle("doc-" + chunkId);
        value.setContent(content);
        return value;
    }

    /** 记录调用次数；返回固定 2 维向量（测试基建，AI 已补齐）。 */
    private static final class CountingEmbedding implements EmbeddingClient {
        @Override
        public List<Double> embed(String text) {
            return List.of(1.0, 0.0);
        }
    }

    /**
     * Repository Stub：findById 从内存 map 读取，findByCourseId 记录调用次数
     * （测试基建，AI 已补齐）。
     */
    private static final class CountingRepository implements KnowledgeChunkRepository {
        private final List<KnowledgeChunk> values;
        private int courseFallbackCalls;

        private CountingRepository(KnowledgeChunk... values) {
            this.values = List.of(values);
        }

        @Override
        public KnowledgeChunk save(KnowledgeChunk chunk) {
            return chunk;
        }

        @Override
        public KnowledgeChunk findById(long id) {
            return values.stream()
                    .filter(value -> value.getId() == id)
                    .findFirst()
                    .orElse(null);
        }

        @Override
        public List<KnowledgeChunk> findByCourseId(long courseId, int limit) {
            courseFallbackCalls++;
            return new ArrayList<>(values);
        }

        @Override public void deleteByDocumentId(long documentId) { }
        @Override public void deleteByCourseId(long courseId) { }
        @Override public void updateVectorStatus(Long id, String vectorId, String status) { }
    }

    /**
     * 记录 Dense 返回的 VersionedVectorHit 列表（测试基建，AI 已补齐）。
     */
    private static final class StubVectorSearch implements VersionedVectorSearch {
        private final List<VersionedVectorHit> hits;

        private StubVectorSearch(List<VersionedVectorHit> hits) {
            this.hits = hits;
        }

        @Override
        public List<VersionedVectorHit> search(
                long courseId,
                Set<Long> activeVersionIds,
                List<Double> queryVector,
                int topK) {
            return hits;
        }
    }
}
