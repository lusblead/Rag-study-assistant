// 本地适配器把逻辑 Artifact Key 限制在 root 内，并用同目录临时文件加原子 Move 提交完整内容。
package com.rag.backend.ingestionlab.artifact;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;

// FileArtifactStore 是 ArtifactStore 的单机实现；Stage 通过端口调用它，删除 Saga 再按 Version 前缀清理。
public final class FileArtifactStore implements ArtifactStore {
    // 制品受控根目录，任何 key 必须解析在其中。
    private final Path root;

    // 构造时固定并创建受控根目录，后续每个 Key 都必须规范化后仍位于该目录内。
    public FileArtifactStore(Path root) {
        this.root = root.toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.root);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create artifact root", e);
        }
    }

    @Override
    public boolean exists(String key) {
        return Files.isRegularFile(resolve(key));
    }

    @Override
    public byte[] read(String key) {
        try {
            return Files.readAllBytes(resolve(key));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read artifact: " + key, e);
        }
    }

    @Override
    // 先写临时文件再原子替换，避免半文件。
    public void writeAtomically(String key, byte[] content) {
        Path target = resolve(key);
        Path temp = null;
        try {
            Files.createDirectories(target.getParent());
            temp = Files.createTempFile(target.getParent(), ".artifact-", ".part");
            Files.write(temp, content);
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException error) {
                // 教程协议要求原子提交；不支持时明确失败，不能悄悄降级成非原子替换。
                throw new IllegalStateException(
                        "Artifact filesystem does not support atomic move", error);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot atomically write artifact: " + key, e);
        } finally {
            if (temp != null) {
                try { Files.deleteIfExists(temp); } catch (IOException ignored) { }
            }
        }
    }

    @Override
    public void deletePrefix(String prefix) {
        Path directory = resolve(prefix);
        if (!Files.exists(directory)) return;
        try (var paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); }
                // 任一文件删除失败都保留原 IOException 作为 cause，让 Delete Job 决定重试而非假装前缀已清空。
                catch (IOException e) {
                    throw new IllegalStateException("Cannot delete artifact: " + path, e);
                }
            });
        } catch (IOException e) {
            throw new IllegalStateException("Cannot list artifact prefix: " + prefix, e);
        }
    }

    // 规范化 key 并确认仍在 root 内，阻止路径穿越。
    private Path resolve(String key) {
        if (key == null || key.isBlank()) throw new IllegalArgumentException("blank artifact key");
        Path result = root.resolve(key).normalize();
        if (!result.startsWith(root)) {
            throw new IllegalArgumentException("artifact key escapes root: " + key);
        }
        return result;
    }
}