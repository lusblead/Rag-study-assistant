package com.rag.backend.agent.grounding;

import com.rag.backend.agent.retrieval.RetrievedChunk;

import java.util.Objects;
import java.util.regex.Pattern;

/** 本次回答可引用的一条请求级来源；S 编号不跨请求稳定。 */
public record CitationSource(String sourceId, RetrievedChunk chunk) {
    private static final Pattern SOURCE_ID = Pattern.compile("S[1-9]\\d*");

    public CitationSource {
        if (sourceId == null || !SOURCE_ID.matcher(sourceId).matches()) {
            throw new IllegalArgumentException("invalid citation sourceId");
        }
        chunk = Objects.requireNonNull(chunk, "chunk");
        if (chunk.chunkId() == null || chunk.documentId() == null) {
            throw new IllegalArgumentException(
                    "citation source requires chunkId and documentId");
        }
        if (chunk.content() == null || chunk.content().isBlank()) {
            throw new IllegalArgumentException(
                    "citation source requires non-blank content");
        }
    }

    public String promptText() {
        return "[" + sourceId + "] 资料来源：" + displaySource()
                + "\n内容：\n" + chunk.content();
    }

    private String displaySource() {
        String documentName = firstNonBlank(
                chunk.documentName(), chunk.title(), "未知文件");
        Integer sourcePage = chunk.sourcePage();
        String page = sourcePage == null || sourcePage <= 0
                ? ""
                : "，第 " + sourcePage + " 页";
        String version = chunk.documentVersionId() == null ? ""
                : "，资料版本记录 " + chunk.documentVersionId();
        return "《" + documentName + "》" + page + version;
    }

    private String firstNonBlank(
            String first, String second, String fallback) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        if (second != null && !second.isBlank()) {
            return second;
        }
        return fallback;
    }
}
