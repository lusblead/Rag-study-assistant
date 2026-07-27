package com.rag.backend.agent.parse.pdf;

import com.rag.backend.agent.model.LayoutBlock;
import com.rag.backend.agent.model.ParsedImage;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

@Component
public class RuleBasedLayoutAnalyzer implements LayoutAnalyzer {
    private static final Pattern NUMBERED_HEADING = Pattern.compile("^((第[一二三四五六七八九十百千万0-9]+[章节])|([0-9]+(\\.[0-9]+){0,3}))\\s+.+");

    @Override
    public List<LayoutBlock> analyze(int pageNo, String text, List<ParsedImage> images) {
        List<LayoutBlock> blocks = new ArrayList<>();
        if (text != null && !text.isBlank()) {
            for (String paragraph : text.split("\\n{2,}")) {
                String trimmed = paragraph.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                int headingLevel = headingLevel(trimmed);
                if (headingLevel > 0) {
                    blocks.add(new LayoutBlock(pageNo, LayoutBlock.TYPE_TITLE, trimmed, headingLevel));
                } else {
                    blocks.add(new LayoutBlock(pageNo, LayoutBlock.TYPE_PARAGRAPH, trimmed, 0));
                }
            }
        }
        if (images != null) {
            for (ParsedImage image : images) {
                blocks.add(new LayoutBlock(pageNo, LayoutBlock.TYPE_IMAGE,
                        "Image " + image.imageIndex() + " (" + image.width() + "x" + image.height() + ")", 0));
            }
        }
        return blocks;
    }

    private int headingLevel(String text) {
        String singleLine = text.replace('\n', ' ').trim();
        if (singleLine.length() > 80) {
            return 0;
        }
        if (singleLine.startsWith("#")) {
            return Math.min(6, Math.max(1, (int) singleLine.chars().takeWhile(ch -> ch == '#').count()));
        }
        if (NUMBERED_HEADING.matcher(singleLine).matches()) {
            long dots = singleLine.chars().filter(ch -> ch == '.').count();
            return (int) Math.min(3, Math.max(1, dots + 1));
        }
        if (singleLine.length() <= 30 && !singleLine.endsWith(".") && !singleLine.endsWith(",")) {
            return 2;
        }
        return 0;
    }
}
