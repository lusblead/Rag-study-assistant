// 哈希目的：为源文件和规范化文本生成跨进程稳定摘要。
package com.rag.backend.ingestionlab.identity;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

// 稳定哈希工具：对 UTF-8 字符串或文件字节计算 SHA-256。
public final class StableHash {
    // 该类只提供确定性静态哈希函数，禁止实例化可避免出现带状态的第二套算法。
    private StableHash() { }

    // 固定 UTF-8 或流式文件字节，避免大文件一次入内存。
    public static String sha256(String value) {
        MessageDigest digest = digest();
        return hex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    // 固定 UTF-8 或流式文件字节，避免大文件一次入内存。
    public static String sha256(Path path) {
        MessageDigest digest = digest();
        byte[] buffer = new byte[8192];
        try (InputStream input = Files.newInputStream(path)) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) digest.update(buffer, 0, read);
            }
            return hex(digest.digest());
        } catch (IOException e) {
            throw new IllegalStateException("Cannot hash source file: " + path, e);
        }
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM does not provide SHA-256", e);
        }
    }

    private static String hex(byte[] bytes) {
        return java.util.HexFormat.of().formatHex(bytes);
    }
}