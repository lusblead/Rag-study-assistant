package com.rag.backend.agent.evaluation.decision;

/** Calibration is N/A when the evaluated policy does not emit confidence. */
public record CalibrationReport(
        String status,
        int sampleCount,
        Double brierScore,
        Double expectedCalibrationError,
        Integer binCount,
        String reason
) {
    public static CalibrationReport notApplicable(int sampleCount) {
        return new CalibrationReport(
                "N/A",
                sampleCount,
                null,
                null,
                null,
                "policy predictions did not provide confidence");
    }
}
