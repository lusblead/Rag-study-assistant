package com.rag.backend.agent.settings;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

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
            """)
    int insert(AgentModelSettings settings);

    @Update("""
            UPDATE agent_model_settings
            SET llm_provider = #{llmProvider},
                llm_base_url = #{llmBaseUrl},
                llm_model = #{llmModel},
                llm_api_key = #{llmApiKey},
                embedding_provider = #{embeddingProvider},
                embedding_base_url = #{embeddingBaseUrl},
                embedding_model = #{embeddingModel},
                embedding_api_key = #{embeddingApiKey},
                updated_at = CURRENT_TIMESTAMP
            WHERE id = 1
            """)
    int update(AgentModelSettings settings);

    default int upsert(AgentModelSettings settings) {
        return selectCurrent() == null ? insert(settings) : update(settings);
    }
}
