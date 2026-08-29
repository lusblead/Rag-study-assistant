package com.rag.backend.agent.grounding;

import com.rag.backend.observability.trace.TraceContextService;
import com.rag.backend.observability.trace.TraceSpan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.function.Function;

/** 单次生成、一次有界修复与最终安全拒答的统一编排。 */
@Component
public class GroundedAnswerGenerator {
    private final GroundingProperties properties;
    private final GroundingValidator validator;
    private final GroundingRepairPromptTemplate repairPromptTemplate;
    private final GroundingFailureRenderer failureRenderer;
    private final TraceContextService traces;

    @Autowired
    public GroundedAnswerGenerator(
            GroundingProperties properties,
            GroundingValidator validator,
            GroundingRepairPromptTemplate repairPromptTemplate,
            GroundingFailureRenderer failureRenderer,
            TraceContextService traces) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.validator = Objects.requireNonNull(validator, "validator");
        this.repairPromptTemplate = Objects.requireNonNull(
                repairPromptTemplate, "repairPromptTemplate");
        this.failureRenderer = Objects.requireNonNull(
                failureRenderer, "failureRenderer");
        this.traces = Objects.requireNonNull(traces, "traces");
    }

    /** 保留现有测试与手工装配入口。 */
    public GroundedAnswerGenerator(
            GroundingProperties properties,
            GroundingValidator validator,
            GroundingRepairPromptTemplate repairPromptTemplate,
            GroundingFailureRenderer failureRenderer) {
        this(properties, validator, repairPromptTemplate, failureRenderer,
                new TraceContextService());
    }

    public boolean enabled() {
        return properties.isEnabled();
    }

    public GroundedAnswerResult generate(
            String initialPrompt,
            CitationCatalog catalog,
            Function<String, String> modelCall) {
        Objects.requireNonNull(initialPrompt, "initialPrompt");
        Objects.requireNonNull(catalog, "catalog");
        Objects.requireNonNull(modelCall, "modelCall");

        try (TraceSpan span = traces.startSpan("chat.grounding")) {
            try {
                GroundedAnswerResult result = generateWithinTrace(
                        initialPrompt, catalog, modelCall);
                span.result(result.diagnostics().status().name().toLowerCase());
                return result;
            } catch (RuntimeException | Error error) {
                span.error(error);
                throw error;
            }
        }
    }

    private GroundedAnswerResult generateWithinTrace(
            String initialPrompt,
            CitationCatalog catalog,
            Function<String, String> modelCall) {
        String first = invokeModel(initialPrompt, modelCall);
        if (!enabled()) {
            return new GroundedAnswerResult(
                    first, GroundingDiagnostics.disabled(catalog));
        }

        GroundingValidationResult firstValidation = validator.validate(
                first, catalog);
        if (firstValidation.accepted()) {
            return new GroundedAnswerResult(
                    first,
                    GroundingDiagnostics.from(
                            GroundingExecutionStatus.ACCEPTED,
                            1, firstValidation, catalog));
        }

        try (TraceSpan repair = traces.startSpan("chat.grounding.repair")) {
            try {
                String repairPrompt = repairPromptTemplate.render(
                        initialPrompt, first, firstValidation, catalog);
                String repaired = invokeModel(repairPrompt, modelCall);
                GroundingValidationResult repairedValidation = validator.validate(
                        repaired, catalog);
                if (repairedValidation.accepted()) {
                    repair.result("accepted");
                    return new GroundedAnswerResult(
                            repaired,
                            GroundingDiagnostics.from(
                                    GroundingExecutionStatus.REPAIRED,
                                    2, repairedValidation, catalog));
                }
                repair.result("rejected");
                return new GroundedAnswerResult(
                        failureRenderer.render(repairedValidation),
                        GroundingDiagnostics.from(
                                GroundingExecutionStatus.REJECTED,
                                2, repairedValidation, catalog));
            } catch (RuntimeException | Error error) {
                repair.error(error);
                throw error;
            }
        }
    }

    public static GroundedAnswerGenerator disabled() {
        GroundingProperties properties = new GroundingProperties();
        SemanticClaimJudge judge = new UncalibratedSemanticClaimJudge();
        GroundingValidator validator = new GroundingValidator(
                new CitationIntegrityValidator(),
                new ClaimSupportEvaluator(judge));
        return new GroundedAnswerGenerator(
                properties,
                validator,
                new GroundingRepairPromptTemplate(),
                new GroundingFailureRenderer());
    }

    private String modelResult(String result) {
        return result == null ? "" : result;
    }

    private String invokeModel(
            String prompt,
            Function<String, String> modelCall) {
        try (TraceSpan span = traces.startSpan("chat.llm")) {
            try {
                String result = modelResult(modelCall.apply(prompt));
                span.result("success");
                return result;
            } catch (RuntimeException | Error error) {
                span.error(error);
                throw error;
            }
        }
    }
}
