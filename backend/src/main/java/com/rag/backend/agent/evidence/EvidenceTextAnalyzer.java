package com.rag.backend.agent.evidence;

import com.rag.backend.agent.history.ChatMessage;
import com.rag.backend.agent.retrieval.RetrievedChunk;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 高精度、本地且可复现的文本信号提取器。它不声称完成语义蕴含判断。
 */
@Component
public class EvidenceTextAnalyzer {
    private static final Pattern HAN_RUN = Pattern.compile("\\p{IsHan}+");
    private static final Pattern ALPHANUM = Pattern.compile("[a-z0-9]+");
    private static final Pattern QUESTION_STOP = Pattern.compile(
            "请问|请|根据|资料|课程|知识库|告诉我|说明|解释|介绍|讲讲|"
                    + "一下|什么|怎么|如何|为什么|多少|哪些|哪一个|哪个|哪|"
                    + "是否|是不是|吗|呢|的|是|有|会|能|可以|相关|当前");
    private static final Pattern VAGUE_QUESTION = Pattern.compile(
            "^(这个|那个|它|这|那)(是什么|指什么|是什么意思|怎么样|怎么办|"
                    + "怎么处理|有哪些|呢)?$|"
                    + "^(解释|说明|讲讲)(一下)?(这个|那个|它)?$");
    private static final Pattern SCALAR_FACT = Pattern.compile(
            "(?<!\\d)(\\d+(?:\\.\\d+)?)\\s*(%|％|分|天|日|月|年|"
                    + "小时|分钟|秒|次|个|元)");
    private static final Pattern CHINESE_SCALAR_FACT = Pattern.compile(
            "[零一二三四五六七八九十百千万两〇]+\\s*(%|％|分|天|日|月|年|"
                    + "小时|分钟|秒|次|个|元)");
    private static final Pattern CONFLICT_REQUEST = Pattern.compile(
            "冲突|矛盾|不一致|不同说法|分别怎么说|为何不同|为什么不同");
    private static final Pattern NEGATIVE = Pattern.compile(
            "不允许|禁止|不得|不能|不可以|无需|不需要");
    private static final Pattern POSITIVE = Pattern.compile(
            "允许|可以|必须|需要|应当");
    private static final Pattern DEFINITION_OR_ASSERTION = Pattern.compile(
            "是指|指的是|定义为|意味着|表示|包括|采用|使用|会|将|应|必须|"
                    + "默认|means?|refers?to|definedas|includes?|uses?|will");
    private static final Pattern REASON_CUE = Pattern.compile(
            "因为|由于|原因|导致|所以|因此|基于|because|dueto|reason|therefore");
    private static final Pattern PROCEDURE_CUE = Pattern.compile(
            "步骤|先|然后|最后|通过|执行|调用|配置|设置|使用|"
                    + "step|first|then|finally|configure|set|use|call");
    private static final Set<String> ENGLISH_STOP = Set.of(
            "the", "a", "an", "is", "are", "was", "were", "what",
            "why", "how", "when", "which", "who", "does", "do",
            "please", "tell", "explain", "about", "this", "that");

    public QuestionSignals questionSignals(
            String question,
            List<ChatMessage> history) {
        String normalized = compact(question);
        boolean vague = VAGUE_QUESTION.matcher(normalized).matches();
        String effectiveQuestion = question;
        if (vague) {
            String previousUserQuestion = latestSpecificUserQuestion(history);
            if (previousUserQuestion != null) {
                effectiveQuestion = previousUserQuestion;
                vague = false;
            }
        }
        boolean asksAboutConflict = CONFLICT_REQUEST.matcher(
                compact(effectiveQuestion)).find();
        return new QuestionSignals(
                effectiveQuestion,
                terms(effectiveQuestion),
                vague,
                asksAboutConflict);
    }

    public double directLexicalCoverage(
            Set<String> questionTerms,
            List<RetrievedChunk> chunks) {
        if (questionTerms.isEmpty() || chunks.isEmpty()) {
            return 0.0;
        }
        Set<String> evidenceTerms = new HashSet<>();
        for (RetrievedChunk chunk : chunks) {
            evidenceTerms.addAll(terms(evidenceText(chunk)));
        }
        long matched = questionTerms.stream()
                .filter(evidenceTerms::contains)
                .count();
        return matched / (double) questionTerms.size();
    }

    public boolean conflictDetected(
            Set<String> questionTerms,
            List<RetrievedChunk> chunks) {
        Set<String> scalarValues = new HashSet<>();
        boolean positive = false;
        boolean negative = false;
        int relevantChunks = 0;

        for (RetrievedChunk chunk : chunks) {
            String text = evidenceText(chunk);
            if (!sharesAnyTerm(questionTerms, terms(text))) {
                continue;
            }
            relevantChunks++;
            Set<String> valuesInChunk = scalarValues(text);
            if (valuesInChunk.size() == 1) {
                scalarValues.add(valuesInChunk.iterator().next());
            }
            String compact = compact(text);
            if (NEGATIVE.matcher(compact).find()) {
                negative = true;
            }
            String withoutNegative = NEGATIVE.matcher(compact).replaceAll("");
            if (POSITIVE.matcher(withoutNegative).find()) {
                positive = true;
            }
        }
        return relevantChunks >= 2
                && (scalarValues.size() >= 2 || (positive && negative));
    }

    public boolean directAnswerShapeObserved(
            String effectiveQuestion,
            Set<String> questionTerms,
            List<RetrievedChunk> chunks) {
        String question = compact(effectiveQuestion);
        StringBuilder relevantEvidence = new StringBuilder();
        for (RetrievedChunk chunk : chunks) {
            String text = evidenceText(chunk);
            if (sharesAnyTerm(questionTerms, terms(text))) {
                relevantEvidence.append(compact(text));
            }
        }
        String evidence = relevantEvidence.toString();
        if (evidence.isBlank()) {
            return false;
        }
        if (question.matches(".*(多少|几分|几天|几年|几次|几个|何时|什么时候|"
                + "howmany|when).*")) {
            return SCALAR_FACT.matcher(evidence).find()
                    || CHINESE_SCALAR_FACT.matcher(evidence).find();
        }
        if (question.matches(".*(是否|是不是|能不能|可不可以|吗|"
                + "whether|can|allowed).*")) {
            return POSITIVE.matcher(evidence).find()
                    || NEGATIVE.matcher(evidence).find();
        }
        if (question.matches(".*(为什么|原因|why).*")) {
            return REASON_CUE.matcher(evidence).find();
        }
        if (question.matches(".*(如何|怎么|怎样|步骤|how).*")) {
            return PROCEDURE_CUE.matcher(evidence).find();
        }
        if (question.matches(".*(什么|含义|定义|意思|mean|define).*")) {
            return DEFINITION_OR_ASSERTION.matcher(evidence).find();
        }
        return DEFINITION_OR_ASSERTION.matcher(evidence).find()
                || POSITIVE.matcher(evidence).find()
                || NEGATIVE.matcher(evidence).find()
                || SCALAR_FACT.matcher(evidence).find()
                || CHINESE_SCALAR_FACT.matcher(evidence).find();
    }

    public boolean conflictCanBeClarified(List<RetrievedChunk> chunks) {
        return chunks.stream()
                .map(RetrievedChunk::documentId)
                .filter(id -> id != null)
                .distinct()
                .limit(2)
                .count() >= 2;
    }

    public String normalizedEvidenceKey(RetrievedChunk chunk) {
        return compact(chunk == null ? null : chunk.content());
    }

    public boolean traceable(RetrievedChunk chunk) {
        if (chunk == null || chunk.chunkId() == null
                || chunk.documentId() == null) {
            return false;
        }
        return hasText(chunk.documentName()) || hasText(chunk.title());
    }

    private Set<String> terms(String text) {
        String normalized = normalized(text);
        Set<String> result = new LinkedHashSet<>();

        Matcher alphanumeric = ALPHANUM.matcher(normalized);
        while (alphanumeric.find()) {
            String token = alphanumeric.group();
            if (token.length() >= 2 && !ENGLISH_STOP.contains(token)) {
                result.add(canonicalEnglishToken(token));
            }
        }

        Matcher hanMatcher = HAN_RUN.matcher(normalized);
        while (hanMatcher.find()) {
            String[] meaningfulRuns = QUESTION_STOP
                    .split(hanMatcher.group());
            for (String run : meaningfulRuns) {
                if (run.length() == 2) {
                    result.add(run);
                } else if (run.length() > 2) {
                    for (int index = 0; index < run.length() - 1; index++) {
                        result.add(run.substring(index, index + 2));
                    }
                }
            }
        }
        return Set.copyOf(result);
    }

    private Set<String> scalarValues(String text) {
        Set<String> values = new HashSet<>();
        Matcher matcher = SCALAR_FACT.matcher(normalized(text));
        while (matcher.find()) {
            values.add(matcher.group(1) + matcher.group(2).replace('％', '%'));
        }
        Matcher chineseMatcher = CHINESE_SCALAR_FACT.matcher(normalized(text));
        while (chineseMatcher.find()) {
            values.add(chineseMatcher.group().replace(" ", "")
                    .replace('％', '%'));
        }
        return values;
    }

    private boolean sharesAnyTerm(Set<String> left, Set<String> right) {
        if (left.isEmpty()) {
            return false;
        }
        return left.stream().anyMatch(right::contains);
    }

    private String evidenceText(RetrievedChunk chunk) {
        if (chunk == null) {
            return "";
        }
        return String.join(" ",
                nullToEmpty(chunk.documentName()),
                nullToEmpty(chunk.title()),
                nullToEmpty(chunk.content()));
    }

    private String latestSpecificUserQuestion(List<ChatMessage> history) {
        for (int index = history.size() - 1; index >= 0; index--) {
            ChatMessage message = history.get(index);
            if (message == null
                    || !ChatMessage.ROLE_USER.equals(message.getRole())
                    || !hasText(message.getContent())) {
                continue;
            }
            String candidate = compact(message.getContent());
            if (!VAGUE_QUESTION.matcher(candidate).matches()) {
                return message.getContent();
            }
        }
        return null;
    }

    private String compact(String text) {
        return normalized(text).replaceAll("[^\\p{IsHan}a-z0-9]+", "");
    }

    private String normalized(String text) {
        return Normalizer.normalize(nullToEmpty(text), Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT);
    }

    private String canonicalEnglishToken(String token) {
        if (token.length() > 3 && token.endsWith("s")) {
            return token.substring(0, token.length() - 1);
        }
        return token;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    public record QuestionSignals(
            String effectiveQuestion,
            Set<String> terms,
            boolean ambiguous,
            boolean asksAboutConflict
    ) {
    }
}
