package com.rag.backend.agent.retrieval.diversity;

import java.text.Normalizer;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 本地确定性的跨中英文 Jaccard 文本相似度。
 * 拉丁文字使用词 token；CJK 使用字符与相邻双字符 token。
 */
public final class JaccardTextSimilarity {

    public double similarity(String left, String right) {
        Set<String> leftTokens = tokens(left);
        Set<String> rightTokens = tokens(right);
        if (leftTokens.isEmpty() || rightTokens.isEmpty()) {
            return 0.0;
        }
        Set<String> intersection = new HashSet<>(leftTokens);
        intersection.retainAll(rightTokens);
        Set<String> union = new HashSet<>(leftTokens);
        union.addAll(rightTokens);
        return (double) intersection.size() / union.size();
    }

    Set<String> tokens(String text) {
        if (text == null || text.isBlank()) {
            return Set.of();
        }
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT);
        Set<String> tokens = new LinkedHashSet<>();
        StringBuilder word = new StringBuilder();
        Integer previousCjk = null;

        for (int offset = 0; offset < normalized.length();) {
            int codePoint = normalized.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (isCjk(codePoint)) {
                flushWord(tokens, word);
                tokens.add("c:" + Integer.toHexString(codePoint));
                if (previousCjk != null) {
                    tokens.add("b:" + Integer.toHexString(previousCjk)
                            + ":" + Integer.toHexString(codePoint));
                }
                previousCjk = codePoint;
            } else if (Character.isLetterOrDigit(codePoint)) {
                previousCjk = null;
                word.appendCodePoint(codePoint);
            } else {
                previousCjk = null;
                flushWord(tokens, word);
            }
        }
        flushWord(tokens, word);
        return Set.copyOf(tokens);
    }

    private static void flushWord(
            Set<String> tokens,
            StringBuilder word) {
        if (!word.isEmpty()) {
            tokens.add("w:" + word);
            word.setLength(0);
        }
    }

    private static boolean isCjk(int codePoint) {
        Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
        return script == Character.UnicodeScript.HAN
                || script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA
                || script == Character.UnicodeScript.HANGUL;
    }
}
