// 端口目的：让 Parse/Chunk Stage 只依赖制品语义，后续可把本地文件实现替换为共享对象存储。
package com.rag.backend.ingestionlab.artifact;

// ArtifactStore 规定制品的存在检查、读取、原子提交和按 Version 清理；不暴露任意文件路径。
public interface ArtifactStore {
    boolean exists(String key);
    byte[] read(String key);
    void writeAtomically(String key, byte[] content);
    void deletePrefix(String prefix);
}