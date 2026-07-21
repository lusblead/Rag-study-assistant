package com.rag.backend.agent.parse.pdf;

import org.springframework.stereotype.Component;

@Component
public class HeuristicOcrDecisionPolicy implements OcrDecisionPolicy {
    @Override
    public boolean needsOcr(String text) {
        if (text == null || text.isBlank()) {
            return true;
        }
        String normalized = text.replaceAll("\\s+", "");
        if (normalized.length() < 12) {
            return true;
        }
        if (normalized.matches("(?i)^(page)?[第-]?\\d+[页]?$")) {
            return true;
        }
        long lettersOrCjk = normalized.codePoints()
                .filter(cp -> Character.isLetter(cp) || isCjk(cp))
                .count();
        long digits = normalized.codePoints().filter(Character::isDigit).count();
        double usefulRatio = lettersOrCjk / (double) Math.max(1, normalized.codePointCount(0, normalized.length()));
        if (lettersOrCjk < 8) {
            return true;
        }
        return digits > 0 && usefulRatio < 0.25;
    }

    private boolean isCjk(int codePoint) {
        Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
        return script == Character.UnicodeScript.HAN
                || script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA
                || script == Character.UnicodeScript.HANGUL;
    }
}
