package com.rag.backend.agent.chunk;

import com.rag.backend.agent.model.PageText;
import com.rag.backend.agent.model.ParsedDocument;
import com.rag.backend.agent.model.TextChunk;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Component
// 按固定窗口将清洗后的文本切分为可入库的知识片段，优先保留 Markdown 结构和页码来源。
public class FixedWindowTextChunker implements TextChunker {
    private final TextCleaner textCleaner;

    public FixedWindowTextChunker(final TextCleaner textCleaner) {
        this.textCleaner = textCleaner;
    }

    private int estimateTokenCount(String text) {
        return Math.max(1, text.length());
    }

    @Override
    public List<TextChunk> chunk(ParsedDocument document, int chunkSize, int overlap) {
        Objects.requireNonNull(document, "document");
        validateProfile(chunkSize, overlap);
        List<TextChunk> chunks = new ArrayList<>();
        int index = 0;

        if (!document.pages().isEmpty()) {
            for (PageText page : document.pages()) {
                String source = preferredPageContent(page);
                index = appendChunks(chunks, index, document.title(), source, page.pageNo(), chunkSize, overlap);
            }
        }

        if (chunks.isEmpty()) {
            appendChunks(chunks, index, document.title(), preferredDocumentContent(document), null, chunkSize, overlap);
        }
        return chunks;
    }

    private static void validateProfile(int chunkSize, int overlap) {
        if (chunkSize <= 0) {
            throw new IllegalArgumentException("chunkSize must be > 0");
        }
        if (overlap < 0 || overlap >= chunkSize) {
            throw new IllegalArgumentException(
                    "overlap must be >= 0 and < chunkSize");
        }
    }

    private int appendChunks(List<TextChunk> chunks,
                             int startIndex,
                             String title,
                             String rawContent,
                             Integer sourcePage,
                             int chunkSize,
                             int overlap) {
        String content = textCleaner.clean(rawContent);
        if (content.isBlank()) {
            return startIndex;
        }
        int start = 0;
        int index = startIndex;
        while (start < content.length()) {
            int end = Math.min(start + chunkSize, content.length());
            String piece = content.substring(start, end).trim();
            if (!piece.isBlank()) {
                chunks.add(new TextChunk(index++, title, piece, sourcePage, estimateTokenCount(piece)));
            }
            if (end == content.length()) {
                break;
            }
            start = Math.max(0, end - overlap);
        }
        return index;
    }

    private String preferredPageContent(PageText page) {
        if (page.markdown() != null && !page.markdown().isBlank()) {
            return page.markdown();
        }
        return page.text();
    }

    private String preferredDocumentContent(ParsedDocument document) {
        if (document.markdown() != null && !document.markdown().isBlank()) {
            return document.markdown();
        }
        return document.content();
    }
}
