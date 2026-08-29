package com.rag.backend.ingestionlab.identity;

import com.rag.backend.ingestionlab.artifact.ParseSnapshot;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 从服务端受控配置生成当前管线清单。
 * Controller 不能允许客户端自报这些字段。
 */
@Component
public class PipelineManifestProvider {
    private final String embeddingModel;
    private final int embeddingDimension;
    private final ChunkProfile chunkProfile;

    public PipelineManifestProvider(
            @Value("${embedding.model}") String embeddingModel,
            @Value("${milvus.embedding-dimension}") int embeddingDimension,
            @Value("${ingestion.chunk.fixed-window.size:800}") int chunkSize,
            @Value("${ingestion.chunk.fixed-window.overlap:120}") int chunkOverlap) {
        this.embeddingModel = embeddingModel;
        this.embeddingDimension = embeddingDimension;
        this.chunkProfile = new ChunkProfile(chunkSize, chunkOverlap);
    }

    public PipelineManifest current() {
        return new PipelineManifest(
                "project-parser-factory",
                "1",
                ParseSnapshot.CURRENT_SCHEMA_VERSION,
                "fixed-window",
                chunkProfile.size(),
                chunkProfile.overlap(),
                1,
                embeddingModel,
                embeddingDimension,
                "ingestion-v2",
                "verification-v1");
    }
}
