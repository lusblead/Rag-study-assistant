package com.rag.backend.agent.grounding;

import com.rag.backend.observability.trace.TraceContextService;
import com.rag.backend.observability.trace.TraceSpan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Objects;

/** 固定执行顺序：先引用完整性，再进行语义支持判断。 */
@Component
public class GroundingValidator {
    private final CitationIntegrityValidator citationValidator;
    private final ClaimSupportEvaluator claimSupportEvaluator;
    private final TraceContextService traces;

    @Autowired
    public GroundingValidator(
            CitationIntegrityValidator citationValidator,
            ClaimSupportEvaluator claimSupportEvaluator,
            TraceContextService traces) {
        this.citationValidator = citationValidator;
        this.claimSupportEvaluator = claimSupportEvaluator;
        this.traces = Objects.requireNonNull(traces, "traces");
    }

    /** 保留现有测试与手工装配入口。 */
    public GroundingValidator(
            CitationIntegrityValidator citationValidator,
            ClaimSupportEvaluator claimSupportEvaluator) {
        this(citationValidator, claimSupportEvaluator,
                new TraceContextService());
    }

    public GroundingValidationResult validate(
            String answer, CitationCatalog catalog) {
        CitationIntegrityResult citation;
        try (TraceSpan span = traces.startSpan("chat.citation_integrity")) {
            try {
                citation = citationValidator.validate(answer, catalog);
                span.result(citation.valid() ? "valid" : "invalid");
            } catch (RuntimeException | Error error) {
                span.error(error);
                throw error;
            }
        }
        if (!citation.valid()) {
            return new GroundingValidationResult(citation, null);
        }
        ClaimSupportResult claimSupport;
        try (TraceSpan span = traces.startSpan("chat.claim_support")) {
            try {
                claimSupport = claimSupportEvaluator.evaluate(
                        citation.claims(), catalog);
                span.result(claimSupport.allSupported()
                        ? "supported"
                        : "not_supported");
            } catch (RuntimeException | Error error) {
                span.error(error);
                throw error;
            }
        }
        return new GroundingValidationResult(citation, claimSupport);
    }
}
