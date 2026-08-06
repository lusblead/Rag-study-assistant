package com.rag.backend.ingestionlab.verify;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.backend.ingestionlab.outbox.DocumentVersionMapper;
import com.rag.backend.ingestionlab.outbox.DocumentVersionRow;
import com.rag.backend.ingestionlab.state.DocumentVersionState;
import com.rag.backend.ingestionlab.state.VersionTransitionService;
import org.springframework.stereotype.Service;

/**
 * 完成一次“进入核验 → 事务外读 Milvus → 短事务保存结论”的用例。
 *
 * 本方法不能加长事务：
 * VersionTransitionService 和 VerificationResultWriter 分别提交短 MySQL 事务，
 * 两者之间的 IndexVerifier 远程读取不占用数据库事务。
 */
@Service
public class VerificationService {
    private final DocumentVersionMapper versionMapper;
    private final VersionTransitionService transitions;
    private final IndexVerifier verifier;
    private final VerificationResultWriter resultWriter;
    private final ObjectMapper objectMapper;

    public VerificationService(
            DocumentVersionMapper versionMapper,
            VersionTransitionService transitions,
            IndexVerifier verifier,
            VerificationResultWriter resultWriter,
            ObjectMapper objectMapper) {
        this.versionMapper = versionMapper;
        this.transitions = transitions;
        this.verifier = verifier;
        this.resultWriter = resultWriter;
        this.objectMapper = objectMapper;
    }

    public IndexVerifier.VerificationReport verify(long versionId) {
        DocumentVersionRow version = requireVersion(versionId);

        if (DocumentVersionState.INDEXING.name().equals(version.getState())) {
            // 这一调用只包含状态迁移所需的短 MySQL 事务。
            version = transitions.transition(
                    versionId, DocumentVersionState.VERIFYING);
        } else if (!DocumentVersionState.VERIFYING.name()
                .equals(version.getState())) {
            throw new IllegalStateException(
                    "Version is not ready for verification: "
                            + version.getState());
        }

        if (version.getExpectedChunkCount() == null) {
            throw new IllegalStateException(
                    "expected_chunk_count is missing: " + versionId);
        }

        // 这里会读取 MySQL 清单和 Milvus 清单，故意位于事务外。
        IndexVerifier.VerificationReport report = verifier.verify(
                versionId, version.getExpectedChunkCount());

        DocumentVersionState target = report.passed()
                ? DocumentVersionState.READY
                : DocumentVersionState.INCONSISTENT;
        resultWriter.write(version, target, report, toJson(report));
        return report;
    }

    private DocumentVersionRow requireVersion(long versionId) {
        DocumentVersionRow row = versionMapper.findById(versionId);
        if (row == null) {
            throw new IllegalArgumentException(
                    "Unknown document version: " + versionId);
        }
        return row;
    }

    private String toJson(IndexVerifier.VerificationReport report) {
        try {
            return objectMapper.writeValueAsString(report);
        } catch (JsonProcessingException error) {
            // JSON 生成失败时 Writer 尚未运行，Version 不会被误标为 READY。
            throw new IllegalStateException(
                    "Cannot serialize verification report", error);
        }
    }
}