package com.rag.backend.ingestionlab.identity;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 可读的处理管线身份。
 * 只放会改变解析、切块、向量或验证语义的字段；
 * 日志级别、线程数等运行参数不能改变指纹。
 */
public record PipelineManifest(
        String parserFamily,
        String parserVersion,
        int parseArtifactSchema,
        String chunkStrategy,
        int chunkSize,
        int chunkOverlap,
        int chunkArtifactSchema,
        String embeddingModel,
        int embeddingDimension,
        String vectorSchema,
        String verificationRuleVersion) {

    public PipelineFingerprint fingerprint() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("parserFamily", parserFamily);
        values.put("parserVersion", parserVersion);
        values.put("parseArtifactSchema", parseArtifactSchema);
        values.put("chunkStrategy", chunkStrategy);
        values.put("chunkSize", chunkSize);
        values.put("chunkOverlap", chunkOverlap);
        values.put("chunkArtifactSchema", chunkArtifactSchema);
        values.put("embeddingModel", embeddingModel);
        values.put("embeddingDimension", embeddingDimension);
        values.put("vectorSchema", vectorSchema);
        values.put("verificationRuleVersion", verificationRuleVersion);
        return PipelineFingerprint.from(values);
    }
}