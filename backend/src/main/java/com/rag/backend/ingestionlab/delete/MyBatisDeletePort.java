package com.rag.backend.ingestionlab.delete;

import com.rag.backend.ingestionlab.artifact.ArtifactStore;
import com.rag.backend.ingestionlab.vector.ConsistentVectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** 把 Delete Saga 的四类幂等动作连接到 MySQL、向量库、制品目录和受控源文件。 */
@Repository
public class MyBatisDeletePort implements DeleteSaga.DeletePort {
    private final DeleteRequestMapper documents;
    private final ConsistentVectorStore vectors;
    private final ArtifactStore artifacts;
    private final Path uploadRoot;

    public MyBatisDeletePort(
            DeleteRequestMapper documents,
            ConsistentVectorStore vectors,
            ArtifactStore artifacts,
            @Value("${app.upload.dir:./uploads}") String uploadRoot) {
        this.documents = documents;
        this.vectors = vectors;
        this.artifacts = artifacts;
        this.uploadRoot = Path.of(uploadRoot).toAbsolutePath().normalize();
    }

    @Override
    public DeleteSaga.DocumentToDelete loadTombstoned(long documentId) {
        DeleteRequestMapper.DeleteDocumentRow row =
                documents.lockDocument(documentId);
        if (row == null) {
            throw new IllegalArgumentException(
                    "Unknown document: " + documentId);
        }
        List<Long> versionIds = documents.selectVersionIds(documentId);
        return new DeleteSaga.DocumentToDelete(
                documentId,
                row.getFilePath(),
                versionIds == null ? List.of() : List.copyOf(versionIds),
                "DELETING".equals(row.getLifecycleStatus()),
                "DELETED".equals(row.getLifecycleStatus()));
    }

    @Override
    public void deleteVectorsByVersion(long versionId) {
        vectors.deleteByVersion(versionId);
    }

    @Override
    public void deleteArtifactsByVersion(long versionId) {
        artifacts.deletePrefix(versionId + "/");
    }

    @Override
    public void deleteChunksByDocument(long documentId) {
        documents.deleteChunks(documentId);
    }

    @Override
    public void deleteSourceFileIfExists(String sourcePath) {
        if (sourcePath == null || sourcePath.isBlank()) {
            return;
        }
        Path target = Path.of(sourcePath).toAbsolutePath().normalize();
        if (!target.startsWith(uploadRoot)) {
            throw new IllegalArgumentException(
                    "Source path is outside upload root");
        }
        try {
            Files.deleteIfExists(target);
        } catch (IOException error) {
            throw new IllegalStateException(
                    "Cannot delete source file", error);
        }
    }

    @Override
    public void markDeleted(long documentId) {
        if (documents.markDeleted(documentId) != 1) {
            throw new IllegalStateException(
                    "Document is no longer DELETING: " + documentId);
        }
    }
}
