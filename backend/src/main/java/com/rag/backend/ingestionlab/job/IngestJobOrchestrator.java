package com.rag.backend.ingestionlab.job;

import com.rag.backend.document.DocumentService;
import com.rag.backend.document.model.CourseDocument;
import com.rag.backend.ingestionlab.activate.VersionActivationService;
import com.rag.backend.ingestionlab.artifact.ParseSnapshot;
import com.rag.backend.ingestionlab.artifact.ReplayableChunkStage;
import com.rag.backend.ingestionlab.artifact.ReplayableParseStage;
import com.rag.backend.ingestionlab.identity.PipelineManifest;
import com.rag.backend.ingestionlab.identity.PipelineManifestProvider;
import com.rag.backend.ingestionlab.identity.StableHash;
import com.rag.backend.ingestionlab.outbox.DocumentVersionMapper;
import com.rag.backend.ingestionlab.outbox.DocumentVersionRow;
import com.rag.backend.ingestionlab.state.DocumentVersionState;
import com.rag.backend.ingestionlab.state.VersionTransitionService;
import com.rag.backend.ingestionlab.step.StepExecutor;
import com.rag.backend.ingestionlab.vector.ChunkWriteRepository;
import com.rag.backend.ingestionlab.vector.VectorWriteStage;
import com.rag.backend.ingestionlab.verify.IndexVerifier;
import com.rag.backend.ingestionlab.verify.VerificationService;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * 持有 Lease 的摄取编排器。
 *
 * 它根据持久 Version 状态恢复，不假设每次都从 BUILDING 开始；因此进程可以在任意
 * 已提交边界重启。每个昂贵阶段前后都会续租，并把最新 Lease 返回给 Worker 完成 Job。
 */
@Service
public class IngestJobOrchestrator {
    private static final Set<DocumentVersionState> RESUMABLE = EnumSet.of(
            DocumentVersionState.BUILDING,
            DocumentVersionState.PARSING,
            DocumentVersionState.CHUNKING,
            DocumentVersionState.EMBEDDING,
            DocumentVersionState.INDEXING,
            DocumentVersionState.VERIFYING,
            DocumentVersionState.READY,
            DocumentVersionState.ACTIVE);

    private final ReplayableParseStage parseStage;
    private final ReplayableChunkStage chunkStage;
    private final DocumentService documentService;
    private final StepExecutor stepExecutor;
    private final VerificationService verificationService;
    private final VersionActivationService versionActivationService;
    private final VectorWriteStage vectorWriteStage;
    private final IngestJobMapper ingestJobMapper;
    private final DocumentVersionMapper versions;
    private final PipelineManifestProvider manifests;
    private final ChunkWriteRepository chunkWriteRepository;
    private final VersionTransitionService transitions;
    private final JobLeaseService leases;

    public IngestJobOrchestrator(
            ReplayableParseStage parseStage,
            ReplayableChunkStage chunkStage,
            DocumentService documentService,
            StepExecutor stepExecutor,
            VerificationService verificationService,
            VersionActivationService versionActivationService,
            VectorWriteStage vectorWriteStage,
            IngestJobMapper ingestJobMapper,
            DocumentVersionMapper versions,
            PipelineManifestProvider manifests,
            ChunkWriteRepository chunkWriteRepository,
            VersionTransitionService transitions,
            JobLeaseService leases) {
        this.parseStage = parseStage;
        this.chunkStage = chunkStage;
        this.documentService = documentService;
        this.stepExecutor = stepExecutor;
        this.verificationService = verificationService;
        this.versionActivationService = versionActivationService;
        this.vectorWriteStage = vectorWriteStage;
        this.ingestJobMapper = ingestJobMapper;
        this.versions = versions;
        this.manifests = manifests;
        this.chunkWriteRepository = chunkWriteRepository;
        this.transitions = transitions;
        this.leases = leases;
    }

    /**
     * 执行编排（前提：当前 Worker 已持有 Job Lease，由 IngestJobWorker.run 调用）。
     *
     * 过程：定位 Job/Version/Document 并校验身份 → 按版本状态机从断点恢复 →
     *       需要时解析/切块/写向量 → 一致性校验 → 激活 → 回写 parse_status。
     * 状态变化：Version.state 沿 BUILDING→PARSING→CHUNKING→EMBEDDING→INDEXING→
     *           VERIFYING→READY→ACTIVE 迁移；Job.state 由外层 Worker 管理
     *           （RUNNING→SUCCEEDED / RETRY_WAIT / FAILED）。
     * Lease 契约：每个阶段边界前续租；续租失败抛 LeaseLostException，
     *           当前 Worker 立即停止写入，由新 owner 从持久状态接管。
     */
    public void runOwned(JobLeaseService.LeaseSession leaseSession) {
        JobLeaseService.Lease lease = leaseSession.current();
        IngestJob job = requireIngestJob(lease.jobId());
        long versionId = job.getDocumentVersionId();
        DocumentVersionRow version = requireVersion(versionId);
        CourseDocument document = requireActiveDocument(version.getDocumentId());
        requireJobTargetsDocument(job, document);

        // 第 1 步：身份校验。Job 必须是 INGEST 且指向该版本；Job/Version/Document
        // 任一缺失、错配或文档非 ACTIVE，都直接失败，不进入执行。
        // 第 2 步：恢复点检查。只有 RESUMABLE 集合内的状态允许续跑；
        // FAILED/CANCELLED 等终态直接抛 UnsupportedResumeStateException。
        DocumentVersionState currentState = stateOf(version);
        if (!RESUMABLE.contains(currentState)) {
            throw new UnsupportedResumeStateException(versionId, currentState);
        }
        if (currentState == DocumentVersionState.ACTIVE) {
            // 激活事务可能已经提交，但 Worker 在更新旧 parse_status 前退出。
            // 此时只补关系库展示字段，不能重新解析、切块或写向量。
            documentService.updateParseStatus(
                    document.getId(), CourseDocument.STATUS_PARSED,
                    requireExpectedCount(version));
            return;
        }

        // 第 3 步：分支选择。BUILDING/PARSING/CHUNKING/EMBEDDING 需要构建产物，
        // 其余状态（INDEXING/VERIFYING/READY）已有持久化清单，走下方复用分支。
        int processedCount;
        if (requiresBuildArtifacts(currentState)) {
            // 只有仍需 Parse/Chunk/Embedding 的状态才读取源文件与当前管线。
            // INDEXING、VERIFYING、READY 已有持久化清单，不应因源文件暂时不可读
            // 而重复前序昂贵步骤或阻断激活恢复。
            // 第 4 步：重放身份校验。源文件路径、内容 hash、管线指纹任一变化，
            // 都抛 SourceIdentityMismatchException / PipelineIdentityMismatchException，
            // 拒绝"同一个 Version 换输入或换加工方式重放"。
            Path source = Path.of(document.getFilePath())
                    .toAbsolutePath().normalize();
            if (!Files.isRegularFile(source)) {
                throw new SourceIdentityMismatchException(versionId);
            }
            if (version.getSourceRef() != null
                    && !source.toString().equals(
                    Path.of(version.getSourceRef()).toAbsolutePath()
                            .normalize().toString())) {
                throw new SourceIdentityMismatchException(versionId);
            }
            String sourceHash = StableHash.sha256(source);
            if (!sourceHash.equals(version.getContentHash())) {
                throw new SourceIdentityMismatchException(versionId);
            }

            PipelineManifest manifest = manifests.current();
            String pipelineFingerprint = manifest.fingerprint().value();
            if (!pipelineFingerprint.equals(version.getPipelineFingerprint())) {
                throw new PipelineIdentityMismatchException(versionId);
            }

            // 第 5 步：状态变化 BUILDING→PARSING（CAS 迁移，携带 state_version；
            // 更新 0 行说明被其他 Worker 推进，抛 ConcurrentVersionChangeException）。
            if (stateOf(version) == DocumentVersionState.BUILDING) {
                lease = leaseSession.renew();
                requireActiveDocument(document.getId());
                version = transitions.transition(
                        versionId, DocumentVersionState.PARSING);
            }

            // 第 6 步：PARSE 步骤（StepExecutor 幂等）。输入摘要=sha256(源hash|指纹)；
            // 同输入且已 DONE 直接复用落盘产物，不重复解析；新执行成功后才提交产物引用与摘要。
            String parseInputDigest = StableHash.sha256(
                    sourceHash + "|" + pipelineFingerprint);
            lease = leaseSession.renew();
            requireActiveDocument(document.getId());
            StepExecutor.StepResult parseResult = stepExecutor.run(
                    lease,
                    "PARSE",
                    parseInputDigest,
                    () -> {
                        ReplayableParseStage.Result result = parseStage.execute(
                                versionId, source, document.getFileType(),
                                sourceHash, pipelineFingerprint);
                        return new StepExecutor.StepResult(
                                result.artifactKey(), result.outputDigest(),
                                result.snapshot().document().pages().size(),
                                result.replayed());
                    });
            // 第 7 步：状态变化 PARSING→CHUNKING；随后加载经源 hash+指纹校验的解析快照，
            // 作为 CHUNK 步骤的输入（前一级输出摘要参与下一级输入摘要，形成链条）。
            lease = leaseSession.renew();
            version = requireVersion(versionId);
            if (stateOf(version) == DocumentVersionState.PARSING) {
                version = transitions.transition(
                        versionId, DocumentVersionState.CHUNKING);
            }
            requireAtLeast(version, DocumentVersionState.CHUNKING);

            ParseSnapshot snapshot = parseStage.loadVerified(
                    parseResult.outputRef(), sourceHash, pipelineFingerprint);
            String parseDigest = parseResult.outputDigest();
            // 第 8 步：CHUNK 步骤（幂等）。输入摘要=sha256(解析输出digest|指纹)；
            // 产物含切片参数（chunkSize/chunkOverlap），同输入复用，否则重新切块。
            String chunkInputDigest = StableHash.sha256(
                    parseDigest + "|" + pipelineFingerprint);
            requireActiveDocument(document.getId());
            StepExecutor.StepResult chunkResult = stepExecutor.run(
                    lease,
                    "CHUNK",
                    chunkInputDigest,
                    () -> {
                        ReplayableChunkStage.Result result = chunkStage.execute(
                                versionId, snapshot, parseDigest,
                                pipelineFingerprint, manifest.chunkSize(),
                                manifest.chunkOverlap());
                        return new StepExecutor.StepResult(
                                result.artifactKey(), result.outputDigest(),
                                result.chunks().size(), result.replayed());
                    });
            // 第 9 步：固化 expected_chunk_count 并迁移 CHUNKING→EMBEDDING。
            // 数量与同一版本绑定：重复出现不同数量会拒绝，防止重放漂移。
            lease = leaseSession.renew();
            version = requireVersion(versionId);
            version = persistExpectedCount(
                    version, chunkResult.processedCount());
            if (stateOf(version) == DocumentVersionState.CHUNKING) {
                version = transitions.transition(versionId, DocumentVersionState.EMBEDDING);
            }
            requireAtLeast(version, DocumentVersionState.EMBEDDING);

            List<ReplayableChunkStage.ChunkSnapshot> chunks = chunkStage.loadVerified(chunkResult.outputRef(), parseDigest,pipelineFingerprint);
            version = requireVersion(versionId);
            // 第 10 步：写向量阶段。逐 chunk 调 VectorWriteStage：MySQL 建行并预留
            // 确定性 vector_id → 查 Milvus（已有且身份匹配则跳过）→ upsert → markDone。
            // 每次远程调用后重新确认文档仍 ACTIVE，防止写向量期间文档被删除。
            if (stateOf(version) == DocumentVersionState.EMBEDDING) {
                for (ReplayableChunkStage.ChunkSnapshot chunk : chunks) {
                    lease = leaseSession.renew();
                    requireActiveDocument(document.getId());
                    vectorWriteStage.write(toDraft(document, versionId, chunk));
                    // 远端调用期间可能刚好发生删除；再次检查可阻止后续写入和激活。
                    requireActiveDocument(document.getId());
                }
                // 第 11 步：状态变化 EMBEDDING→INDEXING（全部 chunk DONE 之后）。
                lease = leaseSession.renew();
                version = transitions.transition(versionId, DocumentVersionState.INDEXING);
            }
            requireAtLeast(version, DocumentVersionState.INDEXING);
            processedCount = chunkResult.processedCount();
        } else {
            // INDEXING/VERIFYING/READY 的前序产物已经由 Version 清单固化。
            processedCount = requireExpectedCount(version);
        }

        // 第 12 步：一致性校验。INDEXING→VERIFYING 在 VerificationService 内迁移；
        // 对账 MySQL 期望清单 vs Milvus 实际清单，missing/orphan 任一非空即
        // 抛 InconsistentIndexException，版本进 INCONSISTENT，绝不激活。
        version = requireVersion(versionId);
        if (stateOf(version) == DocumentVersionState.INDEXING || stateOf(version) == DocumentVersionState.VERIFYING) {
            lease = leaseSession.renew();
            requireActiveDocument(document.getId());
            IndexVerifier.VerificationReport report = verificationService.verify(versionId);
            if (!report.passed()) {throw new InconsistentIndexException(versionId);}

            version = requireVersion(versionId);
        }

        // 第 13 步：状态变化 READY→ACTIVE（单事务：锁 Document、切 active 指针、
        // 旧版本 SUPERSEDED）。此阶段只重放激活，不重建任何产物。
        if (stateOf(version) == DocumentVersionState.READY) {
            lease = leaseSession.renew();
            requireActiveDocument(document.getId());
            versionActivationService.activate(document.getId(), versionId);
        }
        // 第 14 步：确认 ACTIVE 后回写 documents.parse_status=PARSED，
        // 由外层 IngestJobWorker 将 Job 提交为 SUCCEEDED。
        version = requireVersion(versionId);
        if (stateOf(version) != DocumentVersionState.ACTIVE) {
            throw new UnsupportedResumeStateException(
                    versionId, stateOf(version));
        }

        requireActiveDocument(document.getId());
        documentService.updateParseStatus(
                document.getId(), CourseDocument.STATUS_PARSED,
                processedCount);
    }

    private IngestJob requireIngestJob(String jobId) {
        IngestJob job = ingestJobMapper.selectById(jobId);
        if (job == null || !"INGEST".equals(job.getJobType())
                || job.getDocumentVersionId() == null) {
            throw new IllegalArgumentException("Not an INGEST job: " + jobId);
        }
        return job;
    }

    private DocumentVersionRow requireVersion(long versionId) {
        DocumentVersionRow value = versions.findById(versionId);
        if (value == null) {
            throw new IllegalArgumentException(
                    "Unknown document version: " + versionId);
        }
        return value;
    }

    private void requireJobTargetsDocument(
            IngestJob job, CourseDocument document) {
        if (job.getDocumentId() == null
                || !job.getDocumentId().equals(document.getId())) {
            throw new IllegalArgumentException(
                    "Job document identity mismatch");
        }
    }

    private DocumentVersionRow persistExpectedCount(
            DocumentVersionRow version, int count) {
        if (version.getExpectedChunkCount() != null) {
            if (version.getExpectedChunkCount() != count) {
                throw new IllegalStateException(
                        "Chunk count changed for the same version");
            }
            return version;
        }
        if (versions.updateExpectedChunkCount(
                version.getId(), count, version.getStateVersion()) != 1) {
            throw new VersionTransitionService.ConcurrentVersionChangeException(
                    version.getId());
        }
        return requireVersion(version.getId());
    }

    private ChunkWriteRepository.ChunkDraft toDraft(
            CourseDocument document,
            long versionId,
            ReplayableChunkStage.ChunkSnapshot chunk) {
        return new ChunkWriteRepository.ChunkDraft(
                document.getCourseId(), document.getId(), versionId,
                chunk.index(), chunk.title(), chunk.content(),
                chunk.sourcePage(), chunk.tokenCount(),
                chunk.businessKey(), chunk.contentHash());
    }

    private DocumentVersionState stateOf(DocumentVersionRow version) {
        return DocumentVersionState.valueOf(version.getState());
    }

    private CourseDocument requireActiveDocument(long documentId) {
        CourseDocument document = documentService.getById(documentId);
        if (!"ACTIVE".equals(document.getLifecycleStatus())) {
            throw new DocumentTombstonedException(documentId);
        }
        return document;
    }

    private boolean requiresBuildArtifacts(DocumentVersionState state) {
        return state == DocumentVersionState.BUILDING
                || state == DocumentVersionState.PARSING
                || state == DocumentVersionState.CHUNKING
                || state == DocumentVersionState.EMBEDDING;
    }

    private int requireExpectedCount(DocumentVersionRow version) {
        if (version.getExpectedChunkCount() == null) {
            throw new IllegalStateException(
                    "expected_chunk_count is missing: " + version.getId());
        }
        return version.getExpectedChunkCount();
    }

    private void requireAtLeast(
            DocumentVersionRow version,
            DocumentVersionState minimum) {
        DocumentVersionState state = stateOf(version);
        List<DocumentVersionState> order = List.of(
                DocumentVersionState.BUILDING,
                DocumentVersionState.PARSING,
                DocumentVersionState.CHUNKING,
                DocumentVersionState.EMBEDDING,
                DocumentVersionState.INDEXING,
                DocumentVersionState.VERIFYING,
                DocumentVersionState.READY,
                DocumentVersionState.ACTIVE);
        if (!order.contains(state)
                || order.indexOf(state) < order.indexOf(minimum)) {
            throw new UnsupportedResumeStateException(version.getId(), state);
        }
    }

    public static final class SourceIdentityMismatchException
            extends RuntimeException {
        public SourceIdentityMismatchException(long versionId) {
            super("Source identity changed for version " + versionId);
        }
    }

    public static final class PipelineIdentityMismatchException
            extends RuntimeException {
        public PipelineIdentityMismatchException(long versionId) {
            super("Pipeline identity changed for version " + versionId);
        }
    }

    public static final class DocumentTombstonedException
            extends RuntimeException {
        public DocumentTombstonedException(long documentId) {
            super("Document is deleting or deleted: " + documentId);
        }
    }

    public static final class InconsistentIndexException
            extends RuntimeException {
        public InconsistentIndexException(long versionId) {
            super("Index verification failed for version " + versionId);
        }
    }

    public static final class UnsupportedResumeStateException
            extends RuntimeException {
        public UnsupportedResumeStateException(
                long versionId, DocumentVersionState state) {
            super("Version " + versionId
                    + " cannot resume from " + state);
        }
    }
}
