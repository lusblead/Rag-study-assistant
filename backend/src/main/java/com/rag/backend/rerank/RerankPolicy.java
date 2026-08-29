package com.rag.backend.rerank;

/** Step 3.1 的静态策略；provider 与 fail-open 仍由运行时设置决定。 */
public record RerankPolicy(
        Threshold remoteThreshold,
        Threshold localThreshold,
        Composite composite
) {
    public RerankPolicy {
        if (remoteThreshold == null || localThreshold == null
                || composite == null) {
            throw new IllegalArgumentException(
                    "rerank policy parts must not be null");
        }
    }

    public static RerankPolicy defaults() {
        return new RerankPolicy(
                new Threshold(false, 0.0),
                new Threshold(false, 0.0),
                new Composite(false, "normalized-min-max-v1", 0.5, 0.5));
    }

    public record Threshold(boolean enabled, double minimumScore) {
        public Threshold {
            if (!Double.isFinite(minimumScore)) {
                throw new IllegalArgumentException(
                        "threshold minimumScore must be finite");
            }
        }
    }

    public record Composite(
            boolean enabled,
            String version,
            double baseWeight,
            double rerankWeight
    ) {
        public Composite {
            if (version == null || version.isBlank()) {
                throw new IllegalArgumentException(
                        "composite version must not be blank");
            }
            requireWeight("baseWeight", baseWeight);
            requireWeight("rerankWeight", rerankWeight);
            if (baseWeight == 0.0 && rerankWeight == 0.0) {
                throw new IllegalArgumentException(
                        "at least one composite weight must be > 0");
            }
        }

        public double normalizedBaseWeight() {
            return baseWeight / (baseWeight + rerankWeight);
        }

        public double normalizedRerankWeight() {
            return rerankWeight / (baseWeight + rerankWeight);
        }

        private static void requireWeight(String name, double value) {
            if (!Double.isFinite(value) || value < 0.0) {
                throw new IllegalArgumentException(
                        name + " must be finite and >= 0");
            }
        }
    }
}
