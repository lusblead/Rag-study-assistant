package com.rag.backend.agent.grounding;

import com.rag.backend.agent.retrieval.RetrievedChunk;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CitationIntegrityValidatorTest {
    private final CitationCatalog catalog = catalog(
            "本店支持退货。");
    private final CitationIntegrityValidator validator =
            new CitationIntegrityValidator();

    @Test
    void missingCitationFailsCoverage() {
        CitationIntegrityResult result = validator.validate(
                "本店支持退货。", catalog);

        assertFalse(result.valid());
        assertEquals(0.0, result.claimCoverage());
        assertTrue(result.failures().stream().anyMatch(failure ->
                failure.reason() == CitationIntegrityReason.MISSING_CITATION));
    }

    @Test
    void fabricatedSourceIdFailsWhitelistEvenWhenClaimHasCitation() {
        CitationIntegrityResult result = validator.validate(
                "本店支持退货。[S99]", catalog);

        assertFalse(result.valid());
        assertEquals(List.of("S99"), result.unknownSourceIds());
        assertTrue(result.failures().stream().anyMatch(failure ->
                failure.reason() == CitationIntegrityReason.UNKNOWN_SOURCE_ID));
    }

    @Test
    void legalCitationDoesNotAttemptToProveSemanticSupport() {
        CitationIntegrityResult result = validator.validate(
                "本店支持30天无理由退货。[S1]", catalog);

        assertTrue(result.valid());
        assertEquals(1.0, result.claimCoverage());
    }

    @Test
    void everyAtomicClaimRequiresItsOwnNearbyCitation() {
        CitationIntegrityResult result = validator.validate(
                "本店支持退货，并且期限是30天。[S1]", catalog);

        assertFalse(result.valid());
        assertEquals(2, result.claims().size());
        assertTrue(result.failures().stream().anyMatch(failure ->
                failure.reason() == CitationIntegrityReason.MISSING_CITATION
                        && Integer.valueOf(1).equals(failure.claimIndex())));
    }

    private CitationCatalog catalog(String content) {
        return CitationCatalog.from(List.of(new RetrievedChunk(
                1L, 10L, "规则.md", "退货规则", content, 1, 0.9)));
    }
}
