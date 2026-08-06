package com.rag.backend.ingestionlab.artifact;

import com.rag.backend.agent.model.ParsedDocument;

/**
 * 解析制品 v2。
 *
 * 当前 ParsedDocument 是由不可变 record 组成的完整解析结果，
 * 直接保存它可以避免手工挑字段时漏掉 Markdown、OCR、表格或图片。
 * schemaVersion 用于拒绝把旧语义制品当成新语义继续重放。
 */
public record ParseSnapshot(
        int schemaVersion,
        ParsedDocument document) {

    public static final int CURRENT_SCHEMA_VERSION = 2;

    public ParseSnapshot {
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException(
                    "Unsupported parse snapshot schema: " + schemaVersion);
        }
        if (document == null) {
            throw new IllegalArgumentException(
                    "ParsedDocument must not be null");
        }
    }

    public static ParseSnapshot from(ParsedDocument document) {
        return new ParseSnapshot(CURRENT_SCHEMA_VERSION, document);
    }

    /**
     * Chunk Stage 取回与第一次解析业务等价的完整对象。
     * 这里不再用三参数旧构造器重建，否则会把 Markdown 回退成纯文本。
     */
    public ParsedDocument toParsedDocument() {
        return document;
    }
}