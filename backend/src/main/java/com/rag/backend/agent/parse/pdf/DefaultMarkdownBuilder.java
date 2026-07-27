package com.rag.backend.agent.parse.pdf;

import com.rag.backend.agent.model.LayoutBlock;
import com.rag.backend.agent.model.ParsedImage;
import com.rag.backend.agent.model.ParsedTable;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class DefaultMarkdownBuilder implements MarkdownBuilder {
    @Override
    public String buildPageMarkdown(int pageNo, List<LayoutBlock> blocks, List<ParsedTable> tables, List<ParsedImage> images) {
        StringBuilder markdown = new StringBuilder("\n\n<!-- page: ").append(pageNo).append(" -->\n\n");
        for (LayoutBlock block : blocks) {
            if (LayoutBlock.TYPE_TITLE.equals(block.type())) {
                markdown.append("#".repeat(Math.max(1, Math.min(6, block.level()))))
                        .append(' ')
                        .append(stripLeadingHashes(block.text()))
                        .append("\n\n");
            } else if (LayoutBlock.TYPE_IMAGE.equals(block.type())) {
                markdown.append("> [Image] ").append(block.text()).append("\n\n");
            } else {
                markdown.append(block.text()).append("\n\n");
            }
        }
        for (ParsedTable table : tables) {
            if (!table.markdown().isBlank()) {
                markdown.append(table.markdown()).append("\n\n");
            }
        }
        for (ParsedImage image : images) {
            markdown.append("> Image ").append(image.imageIndex())
                    .append(" on page ").append(pageNo)
                    .append(" (format=").append(image.format())
                    .append(", size=").append(image.width()).append('x').append(image.height())
                    .append(")")
                    .append(image.caption().isBlank() ? "" : ": " + image.caption())
                    .append("\n\n");
        }
        return markdown.toString().trim();
    }

    @Override
    public String buildDocumentMarkdown(String title, List<String> pageMarkdowns) {
        StringBuilder markdown = new StringBuilder();
        if (title != null && !title.isBlank()) {
            markdown.append("# ").append(title).append("\n\n");
        }
        for (String pageMarkdown : pageMarkdowns) {
            if (pageMarkdown != null && !pageMarkdown.isBlank()) {
                markdown.append(pageMarkdown).append("\n\n");
            }
        }
        return markdown.toString().trim();
    }

    private String stripLeadingHashes(String text) {
        return text == null ? "" : text.replaceFirst("^#+\\s*", "");
    }
}
