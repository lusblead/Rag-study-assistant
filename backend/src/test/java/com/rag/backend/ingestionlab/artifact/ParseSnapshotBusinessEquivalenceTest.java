package com.rag.backend.ingestionlab.artifact;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.backend.agent.chunk.FixedWindowTextChunker;
import com.rag.backend.agent.chunk.TextCleaner;
import com.rag.backend.agent.model.DocumentMetadata;
import com.rag.backend.agent.model.OcrInfo;
import com.rag.backend.agent.model.PageText;
import com.rag.backend.agent.model.ParseLog;
import com.rag.backend.agent.model.ParsedDocument;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 不只比较 JSON 字段，而是比较下游 Chunker 的实际业务输出。
 */
class ParseSnapshotBusinessEquivalenceTest {

    @Test
    void jsonRoundTripPreservesMarkdownUsedByChunker()
            throws Exception {
        PageText page = new PageText(
                1,
                "纯文本版本",
                "## Markdown 标题\n\nMarkdown 正文",
                List.of(),
                List.of(),
                PageText.OCR_SUCCESS,
                List.of());
        ParsedDocument original = new ParsedDocument(
                "教程",
                "文档纯文本",
                List.of(page),
                "# 文档 Markdown",
                DocumentMetadata.empty(),
                List.of(),
                List.of(),
                List.of(),
                OcrInfo.none(1),
                ParseLog.empty());

        ObjectMapper mapper = new ObjectMapper();
        byte[] json = mapper.writeValueAsBytes(
                ParseSnapshot.from(original));
        ParseSnapshot restoredSnapshot = mapper.readValue(
                json, ParseSnapshot.class);

        FixedWindowTextChunker chunker =
                new FixedWindowTextChunker(new TextCleaner());
        var firstRun = chunker.chunk(original, 800, 120);
        var replay = chunker.chunk(
                restoredSnapshot.toParsedDocument(), 800, 120);

        assertEquals(firstRun, replay);
        assertEquals(
                "## Markdown 标题\n\nMarkdown 正文",
                replay.getFirst().content());
    }
}