package com.rag.backend.agent.parse.pdf;

import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Component
public class RuleBasedTextCleaner implements TextCleaner {
    private static final Pattern PAGE_NUMBER = Pattern.compile("(?i)^(page\\s*)?\\d+\\s*$|^第\\s*\\d+\\s*页$");
    private static final Pattern MULTI_SPACE = Pattern.compile("[ \\t\\x0B\\f]{2,}");
    private static final Pattern MULTI_BLANK_LINE = Pattern.compile("\\n{3,}");

    @Override
    public List<String> detectRepeatedHeadersAndFooters(Collection<String> pageTexts) {
        Map<String, Integer> lineCounts = new HashMap<>();
        int pageCount = 0;
        for (String pageText : pageTexts) {
            pageCount++;
            List<String> candidates = edgeLines(pageText);
            candidates.stream().distinct().forEach(line -> lineCounts.merge(line, 1, Integer::sum));
        }
        int threshold = Math.max(2, (int) Math.ceil(pageCount * 0.5));
        List<String> repeated = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : lineCounts.entrySet()) {
            if (entry.getValue() >= threshold && entry.getKey().length() >= 3) {
                repeated.add(entry.getKey());
            }
        }
        return repeated;
    }

    @Override
    public String clean(String raw, Collection<String> repeatedLines) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String normalized = Normalizer.normalize(raw, Normalizer.Form.NFKC)
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .replace('　', ' ');
        normalized = normalized.replaceAll("([A-Za-z])-[\\n]+([A-Za-z])", "$1$2");
        List<String> lines = new ArrayList<>();
        for (String line : normalized.split("\\n")) {
            String cleaned = MULTI_SPACE.matcher(line).replaceAll(" ").trim();
            if (cleaned.isEmpty()) {
                lines.add("");
                continue;
            }
            if (PAGE_NUMBER.matcher(cleaned).matches()) {
                continue;
            }
            if (repeatedLines != null && repeatedLines.contains(cleaned)) {
                continue;
            }
            lines.add(fixCommonOcrNoise(cleaned));
        }
        return MULTI_BLANK_LINE.matcher(String.join("\n", lines)).replaceAll("\n\n").trim();
    }

    private List<String> edgeLines(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String[] rawLines = text.replace("\r\n", "\n").replace('\r', '\n').split("\\n");
        List<String> cleaned = new ArrayList<>();
        for (String rawLine : rawLines) {
            String line = rawLine.trim();
            if (!line.isEmpty() && !PAGE_NUMBER.matcher(line).matches()) {
                cleaned.add(line);
            }
        }
        List<String> result = new ArrayList<>();
        for (int i = 0; i < Math.min(2, cleaned.size()); i++) {
            result.add(cleaned.get(i));
        }
        for (int i = Math.max(0, cleaned.size() - 2); i < cleaned.size(); i++) {
            result.add(cleaned.get(i));
        }
        return result;
    }

    private String fixCommonOcrNoise(String text) {
        return text.replace('｜', '|')
                .replace('﹣', '-')
                .replace('—', '-')
                .replace('“', '"')
                .replace('”', '"')
                .replace('’', '\'')
                .replace('，', ',')
                .replace('。', '.')
                .replace('：', ':')
                .replace('；', ';');
    }
}
