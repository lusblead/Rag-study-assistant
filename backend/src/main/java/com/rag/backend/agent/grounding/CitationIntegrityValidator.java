package com.rag.backend.agent.grounding;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 只校验引用身份、语法与事实主张覆盖，不判断语义支持。 */
@Component
public class CitationIntegrityValidator {
    public static final String VERSION = "citation-integrity-v1";
    private static final Pattern VALID_CITATION = Pattern.compile(
            "\\[(S[1-9]\\d*)]");
    private static final Pattern CITATION_LIKE = Pattern.compile(
            "\\[[sS][^]\\r\\n]{0,30}]");
    private final AtomicClaimExtractor claimExtractor;

    public CitationIntegrityValidator() {
        this(new AtomicClaimExtractor());
    }

    public CitationIntegrityValidator(AtomicClaimExtractor claimExtractor) {
        this.claimExtractor = claimExtractor;
    }

    public CitationIntegrityResult validate(
            String answer, CitationCatalog catalog) {
        List<CitationIntegrityFailure> failures = new ArrayList<>();
        if (answer == null || answer.isBlank()) {
            failures.add(new CitationIntegrityFailure(
                    CitationIntegrityReason.EMPTY_ANSWER, null,
                    "answer is blank"));
            return result(List.of(), Set.of(), Set.of(), 0.0, failures);
        }

        LinkedHashSet<String> citedIds = validCitations(answer);
        LinkedHashSet<String> unknownIds = new LinkedHashSet<>();
        for (String id : citedIds) {
            if (!catalog.allowedSourceIds().contains(id)) {
                unknownIds.add(id);
            }
        }
        for (String id : unknownIds) {
            failures.add(new CitationIntegrityFailure(
                    CitationIntegrityReason.UNKNOWN_SOURCE_ID, null, id));
        }

        Matcher citationLike = CITATION_LIKE.matcher(answer);
        while (citationLike.find()) {
            String token = citationLike.group();
            if (!VALID_CITATION.matcher(token).matches()) {
                failures.add(new CitationIntegrityFailure(
                        CitationIntegrityReason.MALFORMED_CITATION,
                        null, token));
            }
        }

        List<AtomicClaim> claims = claimExtractor.extract(answer);
        int covered = 0;
        for (AtomicClaim claim : claims) {
            boolean hasAllowedCitation = claim.citationSourceIds().stream()
                    .anyMatch(catalog.allowedSourceIds()::contains);
            if (hasAllowedCitation) {
                covered++;
            } else {
                failures.add(new CitationIntegrityFailure(
                        CitationIntegrityReason.MISSING_CITATION,
                        claim.index(), "factual claim has no allowed citation"));
            }
        }
        double coverage = claims.isEmpty()
                ? 1.0
                : (double) covered / claims.size();
        if (coverage < 1.0) {
            failures.add(new CitationIntegrityFailure(
                    CitationIntegrityReason.COVERAGE_BELOW_REQUIRED,
                    null, Double.toString(coverage)));
        }
        return result(claims, citedIds, unknownIds, coverage, failures);
    }

    private LinkedHashSet<String> validCitations(String answer) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        Matcher matcher = VALID_CITATION.matcher(answer);
        while (matcher.find()) {
            ids.add(matcher.group(1));
        }
        return ids;
    }

    private CitationIntegrityResult result(
            List<AtomicClaim> claims,
            Set<String> citedIds,
            Set<String> unknownIds,
            double coverage,
            List<CitationIntegrityFailure> failures) {
        return new CitationIntegrityResult(
                failures.isEmpty(),
                coverage,
                claims,
                List.copyOf(citedIds),
                List.copyOf(unknownIds),
                failures,
                VERSION + "+" + AtomicClaimExtractor.VERSION);
    }
}
