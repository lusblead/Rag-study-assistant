package com.rag.backend.agent.settings;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AgentModelSettingsMapper {

    @Select("SELECT * FROM agent_model_settings WHERE id = 1")
    AgentModelSettings selectCurrent();

    @Insert("""
            INSERT INTO agent_model_settings (
                id, llm_provider, llm_base_url, llm_model, llm_api_key,
                embedding_provider, embedding_base_url, embedding_model, embedding_api_key
            ) VALUES (
                1, #{llmProvider}, #{llmBaseUrl}, #{llmModel}, #{llmApiKey},
                #{embeddingProvider}, #{embeddingBaseUrl}, #{embeddingModel}, #{embeddingApiKey}
            )
            ON DUPLICATE KEY UPDATE
                llm_provider = VALUES(llm_provider),
                llm_base_url = VALUES(llm_base_url),
                llm_model = VALUES(llm_model),
                llm_api_key = VALUES(llm_api_key),
                embedding_provider = VALUES(embedding_provider),
                embedding_base_url = VALUES(embedding_base_url),
                embedding_model = VALUES(embedding_model),
                embedding_api_key = VALUES(embedding_api_key)
            """)
    int upsert(AgentModelSettings settings);
}
