package com.rag.backend.ingestionlab.application;

import com.rag.backend.document.DocumentService;
import com.rag.backend.document.model.CourseDocument;
import com.rag.backend.ingestionlab.identity.PipelineManifest;
import com.rag.backend.ingestionlab.identity.PipelineManifestProvider;
import com.rag.backend.ingestionlab.identity.StableHash;
import com.rag.backend.ingestionlab.outbox.ReliableIngestSubmitter;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 统一 HTTP 与 Agent Tool 的摄取用例入口。
 *
 * 输入只有 documentId；内容摘要和管线指纹全部由服务端可信事实计算。
 */
@Service
public class IngestApplicationService {
    private final DocumentService documents;
    private final PipelineManifestProvider manifests;
    private final ReliableIngestSubmitter submitter;

    public IngestApplicationService(
            DocumentService documents,
            PipelineManifestProvider manifests,
            ReliableIngestSubmitter submitter) {
        this.documents = documents;
        this.manifests = manifests;
        this.submitter = submitter;
    }

    public Submission submit(long documentId) {
        // 项目接入登录态后，权限检查必须位于读取文件和提交任务之前。
        CourseDocument document = documents.getById(documentId);
        Path source = Path.of(document.getFilePath())
                .toAbsolutePath()
                .normalize();
        if (!Files.isRegularFile(source)) {
            throw new IllegalStateException(
                    "Source file does not exist: " + documentId);
        }

        String contentHash = StableHash.sha256(source);
        PipelineManifest manifest = manifests.current();
        var accepted = submitter.submit(
                documentId,
                source.toString(),
                contentHash,
                manifest);

        return new Submission(
                accepted.documentVersionId(),
                accepted.jobId(),
                accepted.reused());
    }

    public record Submission(
            long documentVersionId,
            String jobId,
            boolean reused) {
    }
}
