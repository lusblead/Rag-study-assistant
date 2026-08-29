package com.rag.backend.agent.retrieval;

import java.util.StringJoiner;
import java.util.regex.Pattern;

/** 在自然语言与精确 Boolean phrase 查询之间做确定性选择。 */
public class LexicalQueryPolicy {
    private static final Pattern HAS_NUMBER = Pattern.compile("\\p{N}");
    private static final Pattern IDENTIFIER = Pattern.compile(
            "(?:\\b[A-Za-z_$][A-Za-z0-9_$]*(?:[.\\-/:][A-Za-z0-9_$]+)+\\b"
                    + "|\\b[A-Za-z0-9]*[_$][A-Za-z0-9_$]*\\b"
                    + "|\\b[A-Za-z]+\\d+[A-Za-z0-9]*\\b"
                    + "|\\b[A-Z]{2,}[A-Z0-9]*\\b"
                    + "|\\b[a-z]+[A-Z][A-Za-z0-9]*\\b"
                    + "|::|=>|==|!=|<=|>=)");

    public QueryPlan plan(String query) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("query must not be blank");
        }
        String normalized = query.trim();
        if (HAS_NUMBER.matcher(normalized).find()
                || IDENTIFIER.matcher(normalized).find()) {
            return new QueryPlan(
                    Mode.BOOLEAN_PHRASE,
                    '"' + escapeBooleanPhrase(normalized) + '"');
        }
        return new QueryPlan(Mode.NATURAL_LANGUAGE, normalized);
    }

    private String escapeBooleanPhrase(String query) {
        StringBuilder sanitized = new StringBuilder(query.length());
        for (int index = 0; index < query.length(); index++) {
            char value = query.charAt(index);
            if (Character.isISOControl(value) || isBooleanOperator(value)) {
                sanitized.append(' ');
            } else {
                sanitized.append(value);
            }
        }

        StringJoiner normalized = new StringJoiner(" ");
        for (String part : sanitized.toString().trim().split("\\s+")) {
            if (!part.isEmpty()) {
                normalized.add(part);
            }
        }
        return normalized.toString();
    }

    private boolean isBooleanOperator(char value) {
        return switch (value) {
            case '"', '+', '-', '<', '>', '(', ')', '~', '*', '@', '\\' -> true;
            default -> false;
        };
    }

    public enum Mode {
        NATURAL_LANGUAGE,
        BOOLEAN_PHRASE
    }

    public record QueryPlan(Mode mode, String boundQuery) {
    }
}
