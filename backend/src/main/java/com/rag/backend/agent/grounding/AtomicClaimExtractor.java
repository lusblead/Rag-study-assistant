package com.rag.backend.agent.grounding;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 本地、版本化的原子事实主张抽取器。
 * 这是保守句法启发式，不声称完成语义解析。
 */
public class AtomicClaimExtractor {
    public static final String VERSION = "atomic-claim-v1";
    private static final Pattern SENTENCE = Pattern.compile(
            "[^。！？!?；;\\n]+(?:[。！？!?；;]"
                    + "(?:\\s*\\[[sS][^]\\r\\n]{0,30}])*)?");
    private static final Pattern VALID_CITATION = Pattern.compile(
            "\\[(S[1-9]\\d*)]");
    private static final Pattern MARKDOWN_PREFIX = Pattern.compile(
            "^(?:#{1,6}\\s*|[-*+]\\s+|\\d+[.)、]\\s*)");
    private static final Pattern ATOMIC_CONNECTOR = Pattern.compile(
            "(?:，|,)?(?:并且|而且|但是|但|同时|以及)(?:，|,)?");
    private static final Set<String> NON_FACTUAL = Set.of(
            "你好", "您好", "谢谢", "不客气", "首先", "其次", "最后",
            "综上", "总之", "下面", "以上");

    public List<AtomicClaim> extract(String answer) {
        if (answer == null || answer.isBlank()) {
            return List.of();
        }
        List<AtomicClaim> claims = new ArrayList<>();
        Matcher sentenceMatcher = SENTENCE.matcher(answer.replace('\r', '\n'));
        while (sentenceMatcher.find()) {
            String sentence = sentenceMatcher.group().trim();
            String[] clauses = ATOMIC_CONNECTOR.split(sentence);
            for (String clause : clauses) {
                List<String> citations = citations(clause);
                boolean question = clause.trim().matches(
                        ".*[？?](?:\\s*\\[[sS][^]\\r\\n]{0,30}])*\\s*$");
                String normalized = normalizeClause(
                        VALID_CITATION.matcher(clause).replaceAll(""));
                if (isFactual(normalized, question)) {
                    claims.add(new AtomicClaim(
                            claims.size() + 1, normalized, citations));
                }
            }
        }
        return List.copyOf(claims);
    }

    private List<String> citations(String sentence) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        Matcher matcher = VALID_CITATION.matcher(sentence);
        while (matcher.find()) {
            ids.add(matcher.group(1));
        }
        return List.copyOf(ids);
    }

    private String normalizeClause(String clause) {
        String stripped = MARKDOWN_PREFIX.matcher(clause.trim()).replaceFirst("");
        return stripped.replaceAll("[。！？!?；;]+$", "").trim();
    }

    private boolean isFactual(String text, boolean question) {
        if (text.isBlank() || text.endsWith("：") || text.endsWith(":")) {
            return false;
        }
        String compact = text.replaceAll("[\\p{Punct}，。！？；：、\\s]", "");
        if (compact.isBlank() || NON_FACTUAL.contains(compact)) {
            return false;
        }
        String lower = compact.toLowerCase(Locale.ROOT);
        if (lower.startsWith("建议") || lower.startsWith("可以考虑")
                || lower.startsWith("不妨")) {
            return false;
        }
        return !question;
    }
}
