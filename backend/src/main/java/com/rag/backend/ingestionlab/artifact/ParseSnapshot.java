// 快照目的：只序列化当前 TextChunker 真正依赖的 title、全文和页级文本，保证重放输入稳定。
package com.rag.backend.ingestionlab.artifact;

import com.rag.backend.agent.model.PageText;
import com.rag.backend.agent.model.ParsedDocument;

import java.util.List;

// 解析快照严格匹配当前 ParsedDocument 的 title、content、pages 三个字段。
// 如果未来加入 OCR 状态、表格或版面信息，应新建带 schemaVersion 的 v2 契约。
public record ParseSnapshot(String title, String content, List<Page> pages) {
    public ParseSnapshot {
        title = title == null ? "" : title;
        content = content == null ? "" : content;
        pages = pages == null ? List.of() : List.copyOf(pages);
    }

    // 当前 PageText 只有 pageNo 和 text，不能读取不存在的 markdown 或 ocrStatus。
    public static ParseSnapshot from(ParsedDocument document) {
        List<PageText> sourcePages =
                document.pages() == null ? List.of() : document.pages();
        List<Page> pages = sourcePages.stream()
                .map(page -> new Page(page.pageNo(), page.text()))
                .toList();
        return new ParseSnapshot(document.title(), document.content(), pages);
    }

    // 重放时恢复成当前代码真实使用的 ParsedDocument，供 TextChunker 继续消费。
    public ParsedDocument toParsedDocument() {
        List<PageText> restored = pages.stream()
                .map(page -> new PageText(page.pageNo(), page.text()))
                .toList();
        return new ParsedDocument(title, content, restored);
    }

    // 页级快照只保留当前模型确实拥有的页码和文本。
    public record Page(Integer pageNo, String text) { }
}