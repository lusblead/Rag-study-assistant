package com.rag.backend.agent.grounding;

import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 确定性失败优先；剩余复杂语义只交给已校准 Judge。 */
@Component
public class ClaimSupportEvaluator {
    public static final String VERSION = "claim-support-v1";
    private static final Pattern PRECISE_VALUE = Pattern.compile(
            "(?:\\d{4}[-年/.]\\d{1,2}(?:[-月/.]\\d{1,2}日?)?"
                    + "|\\d+(?:\\.\\d+)?(?:%|天|日|年|月|小时|分钟|元|个|次|页|章|条)?"
                    + "|[A-Za-z][A-Za-z0-9._/-]*\\d[A-Za-z0-9._/-]*)");
    private static final Pattern NEGATION = Pattern.compile(
            "(?:不|未|没有|无|禁止|不得)");
    private final SemanticClaimJudge semanticJudge;

    public ClaimSupportEvaluator(SemanticClaimJudge semanticJudge) {
        this.semanticJudge = Objects.requireNonNull(
                semanticJudge, "semanticJudge");
    }

    public ClaimSupportResult evaluate(
            List<AtomicClaim> claims, CitationCatalog catalog) {
        List<ClaimSupportAssessment> assessments = new ArrayList<>();
        for (AtomicClaim claim : claims) {
            List<CitationSource> evidence = claim.citationSourceIds().stream()
                    .map(catalog::find)
                    .flatMap(java.util.Optional::stream)
                    .toList();
            assessments.add(evaluateClaim(claim, evidence));
        }
        return new ClaimSupportResult(
                assessments, VERSION, semanticJudge.calibrationId());
    }

    private ClaimSupportAssessment evaluateClaim(
            AtomicClaim claim, List<CitationSource> evidence) {
        List<String> sourceIds = evidence.stream()
                .map(CitationSource::sourceId)
                .toList();
        if (evidence.isEmpty()) {
            return assessment(claim, ClaimSupportStatus.UNSUPPORTED,
                    sourceIds, "NO_MAPPED_EVIDENCE");
        }
        String claimText = normalize(claim.text());
        List<String> normalizedEvidence = evidence.stream()
                .map(source -> normalize(source.chunk().content()))
                .toList();

        if (normalizedEvidence.stream().anyMatch(evidenceText ->
                hasExplicitPolarityContradiction(claimText, evidenceText))) {
            return assessment(claim, ClaimSupportStatus.CONTRADICTED,
                    sourceIds, "EXPLICIT_POLARITY_CONTRADICTION");
        }

        Set<String> missingPreciseValues = preciseValues(claim.text());
        missingPreciseValues.removeIf(value -> normalizedEvidence.stream()
                .anyMatch(evidenceText -> containsNormalized(
                        evidenceText, value)));
        if (!missingPreciseValues.isEmpty()) {
            return assessment(claim, ClaimSupportStatus.UNSUPPORTED,
                    sourceIds, "PRECISE_VALUE_NOT_IN_EVIDENCE");
        }

        if (!claimText.isBlank() && normalizedEvidence.stream()
                .anyMatch(evidenceText -> evidenceText.contains(claimText))) {
            return assessment(claim, ClaimSupportStatus.SUPPORTED,
                    sourceIds, "DIRECT_TEXT_SUPPORT");
        }

        if (!semanticJudge.calibrated()) {
            return assessment(claim, ClaimSupportStatus.UNCERTAIN,
                    sourceIds, "SEMANTIC_JUDGE_NOT_CALIBRATED");
        }
        SemanticJudgeDecision judged = Objects.requireNonNull(
                semanticJudge.judge(claim, evidence), "judge result");
        return assessment(claim, judged.status(),
                sourceIds, judged.reasonCode());
    }

    private ClaimSupportAssessment assessment(
            AtomicClaim claim,
            ClaimSupportStatus status,
            List<String> sourceIds,
            String reasonCode) {
        return new ClaimSupportAssessment(
                claim, status, sourceIds, reasonCode);
    }

    private boolean hasExplicitPolarityContradiction(
            String claim, String evidence) {
        boolean claimNegative = NEGATION.matcher(claim).find();
        boolean evidenceNegative = NEGATION.matcher(evidence).find();
        if (claimNegative == evidenceNegative) {
            return false;
        }
        String claimSkeleton = polaritySkeleton(claim);
        String evidenceSkeleton = polaritySkeleton(evidence);
        return claimSkeleton.length() >= 4
                && (claimSkeleton.contains(evidenceSkeleton)
                || evidenceSkeleton.contains(claimSkeleton));
    }

    private String polaritySkeleton(String text) {
        return text
                .replace("不支持", "支持")
                .replace("不允许", "允许")
                .replace("不能", "能")
                .replace("未提供", "提供")
                .replace("未说明", "说明")
                .replace("没有", "")
                .replace("禁止", "")
                .replace("不得", "")
                .replace("无", "")
                .replace("不", "")
                .replace("未", "");
    }

    private Set<String> preciseValues(String text) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        Matcher matcher = PRECISE_VALUE.matcher(text);
        while (matcher.find()) {
            String value = normalize(matcher.group());
            if (!value.matches("S[1-9]\\d*")) {
                values.add(value);
            }
        }
        return values;
    }

    private boolean containsNormalized(String normalizedText, String value) {
        return normalizedText.contains(normalize(value));
    }

    private String normalize(String text) {
        if (text == null) {
            return "";
        }
        return Normalizer.normalize(text, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\[(?:S[1-9]\\d*)]", "")
                .replaceAll("[\\p{Punct}\\s，。！？；：、“”‘’（）《》]", "");
    }
}
