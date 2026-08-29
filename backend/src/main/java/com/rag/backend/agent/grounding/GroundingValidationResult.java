package com.rag.backend.agent.grounding;

import java.util.Objects;

public record GroundingValidationResult(
        CitationIntegrityResult citationIntegrity,
        ClaimSupportResult claimSupport
) {
    public GroundingValidationResult {
        citationIntegrity = Objects.requireNonNull(
                citationIntegrity, "citationIntegrity");
        if (citationIntegrity.valid()) {
            claimSupport = Objects.requireNonNull(
                    claimSupport, "claimSupport");
        }
    }

    public boolean accepted() {
        return citationIntegrity.valid()
                && claimSupport != null
                && claimSupport.allSupported();
    }
}
