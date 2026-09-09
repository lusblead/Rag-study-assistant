package com.rag.backend.agent.materials;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name={"ingestion.scheduling.enabled", "rag.materials.cleanup-enabled"}, havingValue="true", matchIfMissing=true)
public class MaterialReclamationScheduler {
    private static final Logger log = LoggerFactory.getLogger(MaterialReclamationScheduler.class);
    private final MaterialReclamationService reclamation;
    public MaterialReclamationScheduler(MaterialReclamationService reclamation) { this.reclamation = reclamation; }

    @Scheduled(fixedDelayString="${rag.materials.cleanup-interval-ms:60000}", initialDelayString="${rag.materials.cleanup-interval-ms:60000}")
    public void collect() {
        for (long id : reclamation.candidates(50)) {
            try {
                if (reclamation.reclaim(id)) log.info("Material version reclaimed: versionId={}", id);
            } catch (RuntimeException error) {
                log.warn("Material reclamation deferred: versionId={}, failureType={}", id, error.getClass().getSimpleName());
            }
        }
    }
}
