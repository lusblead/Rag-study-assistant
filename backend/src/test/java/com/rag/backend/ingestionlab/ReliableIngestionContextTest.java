package com.rag.backend.ingestionlab;

import com.rag.backend.agent.retrieval.MilvusKnowledgeRetriever;
import com.rag.backend.agent.retrieval.diversity.DiversitySelector;
import com.rag.backend.ingestionlab.artifact.ReplayableChunkStage;
import com.rag.backend.ingestionlab.artifact.ReplayableParseStage;
import com.rag.backend.ingestionlab.job.DurableJobWorker;
import com.rag.backend.ingestionlab.retrieval.VersionedVectorSearch;
import com.rag.backend.ingestionlab.step.StepExecutor;
import com.rag.backend.ingestionlab.vector.VectorWriteStage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** 证明第 10 章新增的生产对象能在 Mock/H2 环境完成 Spring 组装。 */
@SpringBootTest(properties = {
        "agent.mock=true",
        "vector.provider=local",
        "spring.flyway.enabled=false",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:db/schema-h2.sql",
        "spring.datasource.url=jdbc:h2:mem:reliable-ingestion-context;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "ingestion.scheduling.enabled=false",
        "ingestion.artifact.dir=target/test-artifacts/context"
})
class ReliableIngestionContextTest {
    @Autowired private ReplayableParseStage parseStage;
    @Autowired private ReplayableChunkStage chunkStage;
    @Autowired private StepExecutor stepExecutor;
    @Autowired private VectorWriteStage vectorWriteStage;
    @Autowired private VersionedVectorSearch vectorSearch;
    @Autowired private DurableJobWorker durableJobWorker;
    @Autowired private MilvusKnowledgeRetriever retriever;
    @Autowired private DiversitySelector diversitySelector;

    @Test
    void reliableIngestionProductionGraphCanBeCreated() {
        assertNotNull(parseStage);
        assertNotNull(chunkStage);
        assertNotNull(stepExecutor);
        assertNotNull(vectorWriteStage);
        assertNotNull(vectorSearch);
        assertNotNull(durableJobWorker);
        assertNotNull(retriever);
        assertNotNull(diversitySelector);
        assertFalse(diversitySelector.enabled(),
                "MMR must remain disabled in the default application graph");
    }
}
