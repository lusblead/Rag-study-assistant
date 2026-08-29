package com.rag.backend.agent.evidence;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Configuration;

/** Step 3.2 生成前决策配置；所有数值门槛默认关闭，等待冻结 Dev 集校准。 */
@Configuration
@ConfigurationProperties(prefix = "rag.evidence-decision")
public class EvidenceDecisionProperties implements InitializingBean {
    private boolean enabled = true;
    private Thresholds thresholds = new Thresholds();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Thresholds getThresholds() {
        return thresholds;
    }

    public void setThresholds(Thresholds thresholds) {
        if (thresholds == null) {
            throw new IllegalArgumentException("thresholds must not be null");
        }
        this.thresholds = thresholds;
    }

    public ScoreThreshold threshold(EvidenceScoreKind kind) {
        ScoreThreshold threshold = switch (kind) {
            case REMOTE_RERANK -> thresholds.remoteRerank;
            case LOCAL_RERANK -> thresholds.localRerank;
            case FUSION -> thresholds.fusion;
            case DENSE -> thresholds.dense;
            case LEXICAL -> thresholds.lexical;
            case LEGACY_FINAL -> thresholds.legacyFinal;
            case NONE -> ScoreThreshold.disabled();
        };
        threshold.validate(kind);
        return threshold;
    }

    @Override
    public void afterPropertiesSet() {
        for (EvidenceScoreKind kind : EvidenceScoreKind.values()) {
            threshold(kind);
        }
    }

    public static class Thresholds {
        private ScoreThreshold remoteRerank = new ScoreThreshold();
        private ScoreThreshold localRerank = new ScoreThreshold();
        private ScoreThreshold fusion = new ScoreThreshold();
        private ScoreThreshold dense = new ScoreThreshold();
        private ScoreThreshold lexical = new ScoreThreshold();
        private ScoreThreshold legacyFinal = new ScoreThreshold();

        public ScoreThreshold getRemoteRerank() {
            return remoteRerank;
        }

        public void setRemoteRerank(ScoreThreshold remoteRerank) {
            this.remoteRerank = require(remoteRerank);
        }

        public ScoreThreshold getLocalRerank() {
            return localRerank;
        }

        public void setLocalRerank(ScoreThreshold localRerank) {
            this.localRerank = require(localRerank);
        }

        public ScoreThreshold getFusion() {
            return fusion;
        }

        public void setFusion(ScoreThreshold fusion) {
            this.fusion = require(fusion);
        }

        public ScoreThreshold getDense() {
            return dense;
        }

        public void setDense(ScoreThreshold dense) {
            this.dense = require(dense);
        }

        public ScoreThreshold getLexical() {
            return lexical;
        }

        public void setLexical(ScoreThreshold lexical) {
            this.lexical = require(lexical);
        }

        public ScoreThreshold getLegacyFinal() {
            return legacyFinal;
        }

        public void setLegacyFinal(ScoreThreshold legacyFinal) {
            this.legacyFinal = require(legacyFinal);
        }

        private ScoreThreshold require(ScoreThreshold threshold) {
            if (threshold == null) {
                throw new IllegalArgumentException(
                        "score threshold must not be null");
            }
            return threshold;
        }
    }

    public static class ScoreThreshold {
        private boolean enabled;
        private double minScore;
        private String calibrationId;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public double getMinScore() {
            return minScore;
        }

        public void setMinScore(double minScore) {
            if (!Double.isFinite(minScore)) {
                throw new IllegalArgumentException("minScore must be finite");
            }
            this.minScore = minScore;
        }

        public String getCalibrationId() {
            return calibrationId;
        }

        public void setCalibrationId(String calibrationId) {
            this.calibrationId = calibrationId == null
                    ? null
                    : calibrationId.trim();
        }

        private void validate(EvidenceScoreKind kind) {
            if (enabled && (calibrationId == null
                    || calibrationId.isBlank())) {
                throw new IllegalStateException(
                        kind + " threshold requires calibrationId");
            }
        }

        public static ScoreThreshold disabled() {
            return new ScoreThreshold();
        }
    }
}
