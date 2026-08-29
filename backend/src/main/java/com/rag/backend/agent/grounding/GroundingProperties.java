package com.rag.backend.agent.grounding;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 生成后校验默认关闭，直到独立人工 Dev 集完成语义 Judge 校准。 */
@Configuration
@ConfigurationProperties(prefix = "rag.grounding")
public class GroundingProperties {
    private boolean enabled;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
