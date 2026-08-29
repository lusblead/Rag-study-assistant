package com.rag.backend.ingestionlab.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.backend.agent.chunk.TextChunker;
import com.rag.backend.agent.embedding.EmbeddingClient;
import com.rag.backend.agent.parse.DocumentParserFactory;
import com.rag.backend.ingestionlab.artifact.ArtifactStore;
import com.rag.backend.ingestionlab.artifact.FileArtifactStore;
import com.rag.backend.ingestionlab.artifact.ReplayableChunkStage;
import com.rag.backend.ingestionlab.artifact.ReplayableParseStage;
import com.rag.backend.ingestionlab.reconcile.IssueRepository;
import com.rag.backend.ingestionlab.reconcile.ReconciliationService;
import com.rag.backend.ingestionlab.step.IngestStepMapper;
import com.rag.backend.ingestionlab.step.StepExecutor;
import com.rag.backend.ingestionlab.vector.ChunkWriteRepository;
import com.rag.backend.ingestionlab.vector.ConsistentVectorStore;
import com.rag.backend.ingestionlab.vector.VectorWriteStage;
import com.rag.backend.ingestionlab.verify.IndexVerifier;
import com.rag.backend.observability.trace.TraceCarrier;
import com.rag.backend.observability.trace.TraceContextService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.nio.file.Path;

/**
 * 可靠摄取生产对象的组合根。
 *
 * Stage 和 StepExecutor 保持纯 Java，便于单测；本配置明确把它们连接到项目已有的
 * Parser、Chunker、MyBatis Repository、Embedding 和版本化向量存储。
 */
@Configuration
@EnableScheduling
public class ReliableIngestionConfiguration {

    @Bean
    public ArtifactStore ingestionArtifactStore(
            @Value("${ingestion.artifact.dir}") String root) {
        return new FileArtifactStore(Path.of(root));
    }

    @Bean
    public ReplayableParseStage replayableParseStage(
            DocumentParserFactory parserFactory,
            ArtifactStore ingestionArtifactStore,
            ObjectMapper objectMapper) {
        return new ReplayableParseStage(
                parserFactory, ingestionArtifactStore, objectMapper);
    }

    @Bean
    public ReplayableChunkStage replayableChunkStage(
            TextChunker chunker,
            ArtifactStore ingestionArtifactStore,
            ObjectMapper objectMapper) {
        return new ReplayableChunkStage(
                chunker, ingestionArtifactStore, objectMapper);
    }

    @Bean
    public StepExecutor ingestStepExecutor(IngestStepMapper mapper) {
        return new StepExecutor(mapper);
    }

    @Bean
    public VectorWriteStage vectorWriteStage(
            ChunkWriteRepository chunks,
            EmbeddingClient embeddingClient,
            ConsistentVectorStore vectors,
            @Value("${embedding.model}") String embeddingModel,
            @Value("${milvus.embedding-dimension}") int dimension) {
        return new VectorWriteStage(
                chunks, embeddingClient, vectors, embeddingModel, dimension);
    }

    @Bean(name = "ingestionJobExecutor")
    public TaskExecutor ingestionJobExecutor(TraceContextService traces) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("reliable-ingest-");
        executor.setTaskDecorator(task -> {
            TraceCarrier carrier = traces.currentCarrier().orElse(null);
            return carrier == null
                    ? task
                    : traces.wrap(carrier, "ingestion.executor", task);
        });
        executor.initialize();
        return executor;
    }

    @Bean
    public ReconciliationService reconciliationService(
            IndexVerifier verifier,
            IssueRepository issues,
            ReconciliationService.IssueMetrics metrics) {
        return new ReconciliationService(verifier, issues, metrics);
    }
}
