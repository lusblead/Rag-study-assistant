package com.rag.backend.question;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Extracts stable chapter labels from document names, headings, and chunk text. */
public final class ChapterTagExtractor {
    private static final Pattern CHINESE_CHAPTER = Pattern.compile(
            "第\\s*([0-9０-９一二三四五六七八九十百零两]+)\\s*(?:章|讲|单元)");
    private static final Pattern ENGLISH_CHAPTER = Pattern.compile(
            "(?i)(?:chapter|ch)[-_\\s]*([0-9０-９]{1,3})");
    private static final Pattern LEADING_NUMBER = Pattern.compile(
            "^\\s*([0-9０-９]{1,3})\\s*[-－]\\s*[^0-9０-９]");

    private ChapterTagExtractor() {}

    public static List<String> extract(String... sources) {
        List<String> chapters = new ArrayList<>();
        if (sources == null) return chapters;
        for (String source : sources) {
            if (source == null || source.isBlank()) continue;
            collect(CHINESE_CHAPTER.matcher(source), chapters);
            collect(ENGLISH_CHAPTER.matcher(source), chapters);
            collect(LEADING_NUMBER.matcher(source), chapters);
            if (chapters.size() >= 12) break;
        }
        return chapters;
    }

    private static void collect(Matcher matcher, List<String> chapters) {
        while (matcher.find() && chapters.size() < 12) {
            String number = normalizeDigits(matcher.group(1).replaceAll("\\s+", ""));
            String chapter = "第" + number + "章";
            if (!chapters.contains(chapter)) chapters.add(chapter);
        }
    }

    private static String normalizeDigits(String value) {
        StringBuilder normalized = new StringBuilder(value.length());
        for (char character : value.toCharArray()) {
            normalized.append(character >= '０' && character <= '９'
                    ? (char) ('0' + character - '０')
                    : character);
        }
        return normalized.toString();
    }
}
